package com.ccr.rule.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.cache.CcrCacheUtil;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrLprVersion;
import com.ccr.rule.domain.CcrProductRateLimit;
import com.ccr.rule.domain.CcrRateMatrix;
import com.ccr.rule.dto.MatrixRouteInput;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.mapper.CcrLprVersionMapper;
import com.ccr.rule.mapper.CcrProductRateLimitMapper;
import com.ccr.rule.mapper.CcrRateMatrixMapper;
import com.ccr.rule.service.RateMatrixRouter;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PRD V2 §7.2 权限矩阵路由实现
 * 1BP = 0.01%;边界利率 = LPR(lpr_term) ± boundary_bp*0.01%,并取不低于绝对下限
 * 存量降幅(SPREAD):边界 = 原利率 - boundary_bp*0.01%,且不低于 boundary_min_rate
 * 路由口径(用户拍板):
 *   所有贷款/存款申请必经支行行长首节点(B13);
 *   支行行长仅在 PRD 矩阵"权限内"单元格(非国企新增&lt;5000万、个人新增)有终审权,
 *   终审边界=该单元格部门总经理线(挂牌价口径),突破边界逐级上送 部门总经理→分管行领导→小组;
 *   存款/保证金无部门层级(D16b),支行行长过手后一律合批上会小组(与审批阶段"双轨消除"一致)。
 */
@Service
public class RateMatrixRouterImpl implements RateMatrixRouter {

    /** 审批链首节点(必经):支行行长 */
    private static final String FIRST_NODE = "BRANCH_MANAGER";

    /** 上会兜底岗位:存贷款利率审批小组 */
    private static final String GROUP_NODE = "SIX_PEOPLE_GROUP";

    private static final BigDecimal ONE_BP = new BigDecimal("0.01");
    private static final BigDecimal FIVE_THOUSAND = new BigDecimal("5000");

    @Resource
    private CcrRateMatrixMapper matrixMapper;

    @Resource
    private CcrLprVersionMapper lprVersionMapper;

    @Resource
    private CcrProductRateLimitMapper productRateLimitMapper;

    @Resource
    private CcrCacheUtil cacheUtil;

    @Override
    public RouteResult calcRoute(MatrixRouteInput input) {
        if (input == null || StrUtil.isBlank(input.getBusinessBigType())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "业务大类必填");
        }
        // 冻结口径:按提交时冻结的生效日期取矩阵行(§8.4),缺省取当前时间
        LocalDateTime asOf = input.getAsOfDate() == null ? LocalDateTime.now() : input.getAsOfDate();
        List<CcrRateMatrix> rows = effectiveRows(input.getBusinessBigType(), input.getNewOrExisting(), asOf);
        if (rows.isEmpty()) {
            throw new ServiceException(ErrorCode.RULE_NO_MATCH.getCode(),
                    "权限矩阵无匹配(业务大类=" + input.getBusinessBigType() + ",存量新增=" + input.getNewOrExisting() + ")");
        }
        List<CcrRateMatrix> matched = rows.stream()
                .filter(r -> match(r, input))
                .sorted(Comparator.comparing(r -> r.getPriority() == null ? 0 : r.getPriority()))
                .toList();
        if (matched.isEmpty()) {
            throw new ServiceException(ErrorCode.RULE_NO_MATCH.getCode(),
                    "权限矩阵无匹配(客户类型=" + input.getCustomerType() + ",金额/期限档不符)");
        }
        // 同维度同优先级多命中 → 互斥配置错误
        Map<Integer, Long> byPriority = matched.stream()
                .collect(Collectors.groupingBy(r -> r.getPriority() == null ? 0 : r.getPriority(), Collectors.counting()));
        byPriority.forEach((prio, cnt) -> {
            if (cnt > 1) {
                throw new ServiceException(ErrorCode.RULE_MULTI_MATCH.getCode(),
                        "权限矩阵多匹配:优先级" + prio + "命中" + cnt + "行,需配置互斥条件");
            }
        });

        boolean isLoan = input.getBusinessBigType().startsWith("LOAN");
        BigDecimal rate = input.getRequestedRate();
        // 产品硬边界(§8.2 D3):贷款=全行下限,存款=全行上限;终审边界=矩阵边界与硬边界取交集
        BigDecimal hardBoundary = loadProductHardBoundary(input, asOf, isLoan);

        // 存款/保证金:无部门层级(D16b),阈值为上限语义——高于上限才上会小组;
        // 未超上限(含等于)由支行行长终审(用户拍板口径:超过挂牌价才提交上级)
        if (!isLoan) {
            CcrRateMatrix row = matched.get(0);
            BigDecimal upper = calcBoundary(row, input, null);
            if (rate != null && upper != null && rate.compareTo(upper) <= 0) {
                return buildResult(matched, row, FIRST_NODE, upper, hardBoundary, null,
                        "申请利率" + rate + "% 未高于期限上限" + upper + "%,支行行长权限内终审");
            }
            return buildResult(matched, row, GROUP_NODE, upper, hardBoundary, null,
                    upper == null ? "存款/保证金一律直接上会小组(D16b)"
                            : "申请利率" + rate + "% 高于期限上限" + upper + "%,提交小组表决(≥4票)");
        }

        // 贷款:按优先级从低到高,首个满足 rate≥boundary 的节点终审,小组兜底
        CcrLprVersion lpr = loadLpr(input, asOf);
        Map<String, BigDecimal> lprMap = Map.of("1Y", lpr.getLpr1y(), "5Y", lpr.getLpr5y());
        for (CcrRateMatrix row : matched) {
            if (GROUP_NODE.equals(row.getStartNodeCode())) {
                continue; // 上会兜底行最后处理
            }
            BigDecimal boundary = calcBoundary(row, input, lprMap);
            if (boundary == null) {
                // 无边界 = 权限内即终审(D3)
                return buildResult(matched, row, row.getStartNodeCode(), null, hardBoundary, lpr,
                        "该岗位权限内即终审(D3)");
            }
            if (rate != null && rate.compareTo(boundary) >= 0) {
                String msg = FIRST_NODE.equals(row.getStartNodeCode())
                        ? "申请利率" + rate + "% ≥ 支行行长终审边界(部门总经理线)" + boundary + "%,支行行长终审"
                        : "申请利率" + rate + "% ≥ 岗位下限" + boundary + "%," + row.getStartNodeCode() + "终审";
                return buildResult(matched, row, row.getStartNodeCode(), boundary, hardBoundary, lpr, msg);
            }
        }
        // 无岗位可终审 → 上会小组(≥4票)
        return matched.stream()
                .filter(r -> GROUP_NODE.equals(r.getStartNodeCode()))
                .findFirst()
                .map(r -> buildResult(matched, r, GROUP_NODE, calcBoundary(r, input, lprMap), hardBoundary, lpr,
                        "利率低于全部岗位下限,提交小组表决(≥4票)"))
                .orElseThrow(() -> new ServiceException(ErrorCode.RULE_NO_MATCH.getCode(), "未配置上会兜底行"));
    }

    // ---------- 私有 ----------

    /**
     * 按业务维度取生效矩阵行(§3.6 缓存 key ccr:cfg:matrix:effective):
     * 全量生效行整体缓存,按 businessBigType/newOrExisting/生效窗口内存过滤;矩阵发布时失效。
     */
    private List<CcrRateMatrix> effectiveRows(String businessBigType, String newOrExisting, LocalDateTime asOf) {
        List<CcrRateMatrix> effective = readMatrixEffectiveCache();
        if (effective == null) {
            effective = matrixMapper.selectList(new LambdaQueryWrapper<CcrRateMatrix>()
                    .eq(CcrRateMatrix::getStatus, "EFFECTIVE"));
            cacheUtil.set(CcrCacheUtil.KEY_MATRIX_EFFECTIVE, effective);
        }
        return effective.stream()
                .filter(r -> businessBigType.equals(r.getBusinessBigType()))
                .filter(r -> newOrExisting.equals(r.getNewOrExisting()))
                .filter(r -> r.getEffectiveFrom() == null || !r.getEffectiveFrom().isAfter(asOf))
                .filter(r -> r.getEffectiveTo() == null || r.getEffectiveTo().isAfter(asOf))
                .toList();
    }

    /** 读矩阵缓存:Redis JSON 反序列化会丢失实体类型(元素退化为 LinkedHashMap),
     * 类型不符或为空一律按缓存失效处理,交由调用方直查库重建缓存 */
    @SuppressWarnings("unchecked")
    private List<CcrRateMatrix> readMatrixEffectiveCache() {
        Object cached = cacheUtil.get(CcrCacheUtil.KEY_MATRIX_EFFECTIVE);
        if (cached instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof CcrRateMatrix) {
            return (List<CcrRateMatrix>) list;
        }
        return null;
    }

    private boolean match(CcrRateMatrix r, MatrixRouteInput in) {
        if (StrUtil.isNotBlank(r.getCustomerType()) && !r.getCustomerType().equals(in.getCustomerType())) return false;
        if (StrUtil.isNotBlank(r.getProductCode()) && !r.getProductCode().equals(in.getProductCode())) return false;
        if (StrUtil.isNotBlank(r.getGuaranteeType()) && !r.getGuaranteeType().equals(in.getGuaranteeType())) return false;
        if (StrUtil.isNotBlank(r.getAmountTier())) {
            // §B18:默认按集团综合授信批复总额度定档,无集团额度时退回申请金额
            BigDecimal basis = in.getGroupCreditTotal() != null
                    && !MatrixRouteInput.AMOUNT_BASIS_APPLY_AMOUNT.equals(in.getAmountBasis())
                    ? in.getGroupCreditTotal()
                    : (in.getAmount() == null ? BigDecimal.ZERO : in.getAmount());
            String tier = basis.compareTo(FIVE_THOUSAND) < 0 ? "LT_5000" : "GE_5000";
            if (!r.getAmountTier().equals(tier)) return false;
        }
        if (StrUtil.isNotBlank(r.getTermTier())) {
            String tier = toTermTier(in.getBusinessBigType(), in.getTermValue(), in.getTermUnit());
            if (tier == null || !r.getTermTier().equals(tier)) return false;
        }
        return true;
    }

    /**
     * 期限档分轨:
     * 贷款 → 1Y(≤1年)/3Y(≤3年)/5Y(3年以上,用5Y+ LPR)
     * 存款/保证金 → 3M/6M/1Y/2Y/3Y;通知存款按日 1D/7D
     */
    private String toTermTier(String businessBigType, Integer termValue, String termUnit) {
        if (termValue == null) return null;
        if ("DAY".equals(termUnit) || "日".equals(termUnit)) {
            if (termValue <= 1) return "1D";
            if (termValue <= 7) return "7D";
            return null;
        }
        int months = "YEAR".equals(termUnit) || "年".equals(termUnit) ? termValue * 12 : termValue;
        boolean deposit = !businessBigType.startsWith("LOAN");
        if (!deposit) {
            if (months <= 12) return "1Y";
            if (months <= 36) return "3Y";
            return "5Y";
        }
        if (months <= 3) return "3M";
        if (months <= 6) return "6M";
        if (months <= 12) return "1Y";
        if (months <= 24) return "2Y";
        return "3Y";
    }

    /** 计算岗位边界利率(存款/保证金场景 lprMap 传 null,仅支持 RATE 直接利率) */
    private BigDecimal calcBoundary(CcrRateMatrix r, MatrixRouteInput in, Map<String, BigDecimal> lprMap) {
        // 存量降幅:边界 = 原利率 - BP,且不低于绝对下限
        if ("SPREAD".equals(r.getBoundaryType())) {
            BigDecimal base = in.getOriginalRate();
            if (base == null) return r.getBoundaryMinRate();
            BigDecimal b = base.subtract(BP(r.getBoundaryBp()));
            if (r.getBoundaryMinRate() != null && b.compareTo(r.getBoundaryMinRate()) < 0) {
                b = r.getBoundaryMinRate();
            }
            return b;
        }
        // LPR±BP
        if (r.getBoundaryBp() != null && StrUtil.isNotBlank(r.getLprTerm())) {
            BigDecimal lpr = lprMap == null ? null : lprMap.get(r.getLprTerm());
            if (lpr == null) return r.getBoundaryMinRate();
            BigDecimal b = "+".equals(r.getBpSign())
                    ? lpr.add(BP(r.getBoundaryBp()))
                    : lpr.subtract(BP(r.getBoundaryBp()));
            if (r.getBoundaryMinRate() != null && b.compareTo(r.getBoundaryMinRate()) < 0) {
                b = r.getBoundaryMinRate();
            }
            return b;
        }
        return r.getBoundaryMinRate();
    }

    private BigDecimal BP(Integer bp) {
        return bp == null ? BigDecimal.ZERO : BigDecimal.valueOf(bp).multiply(ONE_BP);
    }

    /**
     * 加载 LPR 版本(§8.4 冻结:入参指定版本主键则按冻结版本取,否则取当前生效版本)。
     * 查不到生效 LPR 不得按 0 静默错算,直接抛 LPR_NOT_EFFECTIVE。
     */
    private CcrLprVersion loadLpr(MatrixRouteInput input, LocalDateTime asOf) {
        if (input.getLprVersionId() != null) {
            CcrLprVersion frozen = lprVersionMapper.selectById(input.getLprVersionId());
            if (frozen == null) {
                throw new ServiceException(ErrorCode.LPR_NOT_EFFECTIVE.getCode(),
                        "冻结的LPR版本不存在(id=" + input.getLprVersionId() + ")");
            }
            return frozen;
        }
        // 当前生效版本走缓存(§3.6 key ccr:cfg:lpr:effective;发布/停用时递增全局版本号感知)
        Object cached = cacheUtil.get(CcrCacheUtil.KEY_LPR_EFFECTIVE);
        if (cached instanceof CcrLprVersion v) {
            return v;
        }
        CcrLprVersion lpr = lprVersionMapper.selectOne(new LambdaQueryWrapper<CcrLprVersion>()
                .eq(CcrLprVersion::getStatus, "EFFECTIVE")
                .le(CcrLprVersion::getEffectiveFrom, asOf)
                .and(w -> w.isNull(CcrLprVersion::getEffectiveTo)
                        .or().gt(CcrLprVersion::getEffectiveTo, asOf))
                .orderByDesc(CcrLprVersion::getEffectiveFrom)
                .last("limit 1"));
        if (lpr == null) {
            throw new ServiceException(ErrorCode.LPR_NOT_EFFECTIVE.getCode(), "当前无生效的LPR版本,请先维护并发布LPR");
        }
        cacheUtil.set(CcrCacheUtil.KEY_LPR_EFFECTIVE, lpr);
        return lpr;
    }

    /**
     * 查询产品硬边界(§8.2):按 product_code + 业务类型(LOAN/DEPOSIT)取生效窗口内最新一版。
     * 入参未指定产品或该产品未配置硬边界时返回 null(不收紧矩阵边界)。
     */
    private BigDecimal loadProductHardBoundary(MatrixRouteInput input, LocalDateTime asOf, boolean isLoan) {
        if (StrUtil.isBlank(input.getProductCode())) {
            return null;
        }
        CcrProductRateLimit limit = productRateLimitMapper.selectOne(new LambdaQueryWrapper<CcrProductRateLimit>()
                .eq(CcrProductRateLimit::getStatus, "EFFECTIVE")
                .eq(CcrProductRateLimit::getProductCode, input.getProductCode())
                .eq(CcrProductRateLimit::getBusinessType, isLoan ? "LOAN" : "DEPOSIT")
                .le(CcrProductRateLimit::getEffectiveFrom, asOf)
                .and(w -> w.isNull(CcrProductRateLimit::getEffectiveTo)
                        .or().gt(CcrProductRateLimit::getEffectiveTo, asOf))
                .orderByDesc(CcrProductRateLimit::getEffectiveFrom)
                .last("limit 1"));
        return limit == null ? null : limit.getHardBoundaryRate();
    }

    /**
     * 终审节点有效边界(§8.2 D3):矩阵边界与产品硬边界取交集。
     * 贷款=下限语义取 max(矩阵下限,产品下限);存款/保证金=上限语义取 min(矩阵上限,产品上限);
     * 一侧缺失时取另一侧,均缺失返回 null。
     */
    private BigDecimal intersectBoundary(BigDecimal matrixBoundary, BigDecimal hardBoundary, boolean isLoan) {
        if (matrixBoundary == null) {
            return hardBoundary;
        }
        if (hardBoundary == null) {
            return matrixBoundary;
        }
        return isLoan ? matrixBoundary.max(hardBoundary) : matrixBoundary.min(hardBoundary);
    }

    /**
     * 组装路由结果:首节点恒为支行行长(必经),终审岗位为命中行岗位;
     * 链路保留首节点至终审岗位之间的中间层级(部门总经理行调价后可达终审)。
     */
    private RouteResult buildResult(List<CcrRateMatrix> matched, CcrRateMatrix terminalRow, String finalNode,
                                    BigDecimal boundary, BigDecimal hardBoundary, CcrLprVersion lpr, String msg) {
        boolean isLoan = terminalRow.getBusinessBigType().startsWith("LOAN");
        RouteResult result = new RouteResult();
        result.setStartNodeCode(FIRST_NODE);
        result.setFinalNodeCode(finalNode);
        result.setRouteChain(buildChain(matched, finalNode));
        result.setRateDirection(isLoan ? "LOWER_BETTER" : "HIGHER_BETTER");
        result.setMatchedRuleCode(terminalRow.getMatrixNo());
        result.setMatchedRuleName(terminalRow.getRemark());
        // 终审命中行编号(小组兜底行同样记录,审计溯源 §8.6)
        result.setMatchedMatrixNo(terminalRow.getMatrixNo());
        // 终审节点有效边界:矩阵边界 ∩ 产品硬边界(D3)
        result.setBoundaryRate(intersectBoundary(boundary, hardBoundary, isLoan));
        if (lpr != null) {
            result.setLprVersionId(lpr.getId());
            result.setLprVersionCode(lpr.getVersionCode());
        }
        result.setMessage(msg);
        return result;
    }

    /** 路由链路:BRANCH_MANAGER(必经) → …中间层级… → 终审岗位 */
    private List<String> buildChain(List<CcrRateMatrix> matched, String finalNode) {
        Set<String> chain = new LinkedHashSet<>();
        chain.add(FIRST_NODE);
        for (CcrRateMatrix row : matched) {
            // 兜底小组行仅在终审为小组时进入链路
            if (GROUP_NODE.equals(row.getStartNodeCode()) && !GROUP_NODE.equals(finalNode)) {
                continue;
            }
            chain.add(row.getStartNodeCode());
            if (row.getStartNodeCode().equals(finalNode)) {
                break;
            }
        }
        chain.add(finalNode);
        return new ArrayList<>(chain);
    }
}
