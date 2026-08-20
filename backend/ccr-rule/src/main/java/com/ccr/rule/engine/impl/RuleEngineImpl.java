package com.ccr.rule.engine.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.cache.CcrCacheUtil;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrProductRateLimit;
import com.ccr.rule.domain.CcrRateRule;
import com.ccr.rule.domain.CcrRateRuleSet;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.dto.RuleInput;
import com.ccr.rule.engine.RuleEngine;
import com.ccr.rule.mapper.CcrProductRateLimitMapper;
import com.ccr.rule.mapper.CcrRateRuleMapper;
import com.ccr.rule.mapper.CcrRateRuleSetMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 利率规则引擎实现
 * 匹配规则:空条件=通配;数值区间含下界不含上界 [min,max);优先级低值优先;同优先级多条→多匹配错误
 */
@Service
public class RuleEngineImpl implements RuleEngine {

    @Resource
    private CcrRateRuleMapper ruleMapper;

    @Resource
    private CcrRateRuleSetMapper ruleSetMapper;

    @Resource
    private CcrProductRateLimitMapper productRateLimitMapper;

    @Resource
    private CcrCacheUtil cacheUtil;

    @Override
    public RouteResult calcRoute(Long ruleSetId, RuleInput input) {
        if (input == null || StrUtil.isBlank(input.getBusinessType())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "业务类型必填");
        }
        // 仅允许使用已生效的规则集版本(§8.4 版本生命周期)
        CcrRateRuleSet ruleSet = ruleSetMapper.selectById(ruleSetId);
        if (ruleSet == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "规则集不存在(id=" + ruleSetId + ")");
        }
        if (!"EFFECTIVE".equals(ruleSet.getStatus())) {
            throw new ServiceException(ErrorCode.RULE_NO_MATCH.getCode(),
                    "规则集未生效(id=" + ruleSetId + ",status=" + ruleSet.getStatus() + "),禁止使用草稿/停用版本路由");
        }
        List<CcrRateRule> rules = ruleMapper.selectList(
                new LambdaQueryWrapper<CcrRateRule>()
                        .eq(CcrRateRule::getSetId, ruleSetId)
                        .eq(CcrRateRule::getStatus, "ACTIVE")
                        .eq(CcrRateRule::getBusinessType, input.getBusinessType())
                        .orderByAsc(CcrRateRule::getPriority));

        // 用可变列表,允许后续 sort
        List<CcrRateRule> candidates = rules.stream()
                .filter(r -> match(r, input))
                .collect(Collectors.toCollection(ArrayList::new));

        if (candidates.isEmpty()) {
            throw new ServiceException(ErrorCode.RULE_NO_MATCH.getCode(),
                    "无匹配的利率规则(businessType=" + input.getBusinessType() + ", productCode=" + input.getProductCode() + ")");
        }

        // 按优先级取最优(priority 低值优先)
        candidates.sort(Comparator.comparing(r -> r.getPriority() == null ? 0 : r.getPriority()));
        CcrRateRule best = candidates.get(0);
        long samePriorityCount = candidates.stream()
                .filter(r -> Objects.equals(safePriority(r), safePriority(best)))
                .count();
        if (samePriorityCount > 1) {
            throw new ServiceException(ErrorCode.RULE_MULTI_MATCH.getCode(),
                    "规则多匹配:同优先级命中 " + samePriorityCount + " 条,需配置互斥条件");
        }

        RouteResult result = new RouteResult();
        result.setStartNodeCode(best.getStartNodeCode());
        result.setFinalNodeCode(best.getStartNodeCode());
        result.setRouteChain(List.of(best.getStartNodeCode()));
        result.setRateDirection(best.getRateDirection());
        result.setMatchedRuleCode(best.getRuleCode());
        result.setMatchedRuleName(best.getRuleName());
        result.setMessage("命中规则 [" + best.getRuleCode() + "] 起始节点 [" + best.getStartNodeCode() + "]");
        return result;
    }

    @Override
    public String validateContinuity(Long ruleSetId) {
        List<CcrRateRule> rules = ruleMapper.selectList(
                new LambdaQueryWrapper<CcrRateRule>().eq(CcrRateRule::getSetId, ruleSetId));
        if (rules.isEmpty()) {
            return "规则集为空,无需校验";
        }
        // 按业务维度组合分组(不同担保/产品/业务类型的区间可独立连续)
        Map<String, List<CcrRateRule>> groups = rules.stream()
                .collect(Collectors.groupingBy(RuleEngineImpl::groupKey));
        for (Map.Entry<String, List<CcrRateRule>> entry : groups.entrySet()) {
            String issue = checkContinuityGroup(entry.getValue());
            if (issue != null) {
                return issue;
            }
        }
        return null;
    }

    @Override
    public BigDecimal checkHardBoundary(String businessType, String productCode, BigDecimal rate) {
        if (StrUtil.isBlank(businessType)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "业务类型必填");
        }
        // 业务大类归一:LOAN_PUBLIC/LOAN_PERSONAL→LOAN;DEPOSIT/MARGIN→DEPOSIT
        // PRD 口径硬边界:对公贷款 3.0%、个人经营贷 3.8%(新增不设绝对下限,走本产品硬边界表)
        boolean isLoan = businessType.startsWith("LOAN");
        String limitBusinessType = isLoan ? "LOAN" : "DEPOSIT";
        // 产品硬边界缓存(§3.6 key ccr:cfg:rate-limit:{业务类}:{产品};产品边界发布时失效)
        String boundaryKey = "ccr:cfg:rate-limit:" + limitBusinessType + ":" + productCode;
        Object cached = cacheUtil.get(boundaryKey);
        CcrProductRateLimit limit = cached instanceof CcrProductRateLimit l ? l : null;
        if (limit == null) {
            limit = productRateLimitMapper.selectOne(
                    new LambdaQueryWrapper<CcrProductRateLimit>()
                            .eq(CcrProductRateLimit::getStatus, "EFFECTIVE")
                            .eq(CcrProductRateLimit::getBusinessType, limitBusinessType)
                            .eq(CcrProductRateLimit::getProductCode, productCode)
                            .le(CcrProductRateLimit::getEffectiveFrom, LocalDateTime.now())
                            .and(w -> w.isNull(CcrProductRateLimit::getEffectiveTo)
                                    .or().gt(CcrProductRateLimit::getEffectiveTo, LocalDateTime.now()))
                            .last("limit 1"));
            if (limit != null) {
                cacheUtil.set(boundaryKey, limit);
            }
        }
        if (limit == null || rate == null) {
            return null;
        }
        // §用户要求:取消硬边界限制——任何利率均可申请/审批,突破边界不再抛异常阻断;
        // 仅返回边界值供前端展示(路由预览/提交前校验/审批调价可见边界,不拦截)。
        return limit.getHardBoundaryRate();
    }

    // ---------- 私有 ----------

    private boolean match(CcrRateRule r, RuleInput in) {
        if (StrUtil.isNotBlank(r.getProductCode()) && !r.getProductCode().equals(in.getProductCode())) return false;
        if (StrUtil.isNotBlank(r.getCustomerType()) && !r.getCustomerType().equals(in.getCustomerType())) return false;
        if (StrUtil.isNotBlank(r.getNewOrExisting()) && !r.getNewOrExisting().equals(in.getNewOrExisting())) return false;
        if (StrUtil.isNotBlank(r.getStateOwnedFlag()) && !r.getStateOwnedFlag().equals(in.getStateOwnedFlag())) return false;
        if (StrUtil.isNotBlank(r.getGuaranteeType()) && !r.getGuaranteeType().equals(in.getGuaranteeType())) return false;
        if (StrUtil.isNotBlank(r.getLprTerm()) && !r.getLprTerm().equals(in.getLprTerm())) return false;
        if (StrUtil.isNotBlank(r.getOrgCode()) && !r.getOrgCode().equals(in.getOrgCode())) return false;
        if (StrUtil.isNotBlank(r.getCurrency()) && !r.getCurrency().equals(in.getCurrency())) return false;
        // §B18:金额档默认按集团综合授信批复总额度;显式 APPLY_AMOUNT 或无集团额度时按本笔申请金额
        BigDecimal amountBasis = in.getGroupCreditTotal() != null && !"APPLY_AMOUNT".equals(in.getAmountBasis())
                ? in.getGroupCreditTotal() : in.getApplyAmount();
        if (amountBasis != null) {
            if (r.getAmountMin() != null && amountBasis.compareTo(r.getAmountMin()) < 0) return false;
            if (r.getAmountMax() != null && amountBasis.compareTo(r.getAmountMax()) >= 0) return false;
        }
        if (in.getGroupCreditTotal() != null) {
            if (r.getGroupCreditMin() != null && in.getGroupCreditTotal().compareTo(r.getGroupCreditMin()) < 0) return false;
            if (r.getGroupCreditMax() != null && in.getGroupCreditTotal().compareTo(r.getGroupCreditMax()) >= 0) return false;
        }
        if (in.getTermValue() != null) {
            if (r.getTermMin() != null && in.getTermValue() < r.getTermMin()) return false;
            if (r.getTermMax() != null && in.getTermValue() > r.getTermMax()) return false;
        }
        return true;
    }

    private int safePriority(CcrRateRule r) {
        return r.getPriority() == null ? 0 : r.getPriority();
    }

    /** 维度组合分组键(含期限维度:不同期限档的金额区间可独立连续) */
    private static String groupKey(CcrRateRule r) {
        return r.getBusinessType() + "|" + nvl(r.getProductCode()) + "|" + nvl(r.getGuaranteeType())
                + "|" + nvl(r.getCustomerType()) + "|" + nvl(r.getNewOrExisting())
                + "|" + nvl(r.getTermUnit()) + "|" + r.getTermMin() + "|" + r.getTermMax();
    }

    /** 组内金额区间连续性检查 */
    private String checkContinuityGroup(List<CcrRateRule> group) {
        List<CcrRateRule> sorted = group.stream()
                .filter(r -> r.getAmountMin() != null && r.getAmountMax() != null)
                .sorted(Comparator.comparing(r -> r.getAmountMin() == null ? BigDecimal.ZERO : r.getAmountMin()))
                .collect(Collectors.toList());
        BigDecimal prevMax = null;
        for (CcrRateRule r : sorted) {
            if (r.getAmountMin().compareTo(r.getAmountMax()) >= 0) {
                return "规则 [" + r.getRuleCode() + "] 金额区间非法:min>=max";
            }
            if (prevMax != null && r.getAmountMin().compareTo(prevMax) < 0) {
                return "规则 [" + r.getRuleCode() + "] 与同维度上一规则金额区间重叠";
            }
            if (prevMax != null && r.getAmountMin().compareTo(prevMax) > 0) {
                return "规则 [" + r.getRuleCode() + "] 与同维度上一规则金额区间存在空档";
            }
            prevMax = r.getAmountMax();
        }
        return null;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
