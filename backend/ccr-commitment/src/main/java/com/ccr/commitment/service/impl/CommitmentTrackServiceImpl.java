package com.ccr.commitment.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ccr.commitment.domain.CcrCommitmentTrack;
import com.ccr.commitment.domain.DwContributionMetric;
import com.ccr.commitment.mapper.CommitmentTrackMapper;
import com.ccr.commitment.mapper.DwContributionMetricMapper;
import com.ccr.commitment.service.CommitmentTrackService;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 承诺跟踪服务实现(v2·无定时任务版,docs/28)
 * 在途行不存当前值(实时取数仓);终态行固化 final_* 定案字段。数仓取数 CONTRIBUTION_AMOUNT 行优先、
 * 无则取最新 data_dt 批次全行合计(与 CommitmentServiceImpl.fetchActual 同口径)。
 */
@Slf4j
@Service
public class CommitmentTrackServiceImpl implements CommitmentTrackService {

    private static final String VALUE_TYPE_CONTRIBUTION = "CONTRIBUTION_AMOUNT";
    private static final String STATUS_TRACKING = "TRACKING";
    private static final String STATUS_MET = "FINISHED_MET";
    private static final String STATUS_UNMET = "FINISHED_UNMET";
    /** 惰性结算单批上限(超出留到下次读触发,保证大库不一次拉爆) */
    private static final int SETTLE_BATCH = 200;

    @Resource
    private CommitmentTrackMapper trackMapper;
    @Resource
    private DwContributionMetricMapper dwMetricMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 登录用户数据范围(fullView 全量 / customer_manager 本人 / 其余按机构) */
    private record Scope(boolean fullView, boolean customerManager, Long operatorId, Long userOrgId) {
    }

    private Scope currentScope() {
        Long operatorId = StpUtil.getLoginIdAsLong();
        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "SELECT role_code roleCode, org_id orgId FROM ccr_sys_user WHERE id = ? AND del_flag = '0'",
                operatorId);
        if (users.isEmpty()) {
            throw new ServiceException(ErrorCode.UNAUTHORIZED.getCode(), "登录用户不存在");
        }
        Map<String, Object> u = users.get(0);
        String roleCode = u.get("roleCode") == null ? null : u.get("roleCode").toString();
        boolean fullView = "committee_member".equals(roleCode) || "president".equals(roleCode)
                || "admin".equals(roleCode) || "auditor".equals(roleCode);
        return new Scope(fullView, "customer_manager".equals(roleCode), operatorId,
                u.get("orgId") == null ? null : Long.valueOf(u.get("orgId").toString()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createTracks(List<CcrCommitmentTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        // 跟踪编号:TRK+日期+同申请序号(幂等重放不复用已存在编号)
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = 1;
        for (CcrCommitmentTrack t : tracks) {
            // uk_track 幂等:同申请同指标同成员已存在则跳过(整单重放/事件重试不重复)
            // 成员号为 NULL 必须用 IS NULL,不能用 eq(= NULL 恒 false)
            LambdaQueryWrapper<CcrCommitmentTrack> existsW = new LambdaQueryWrapper<CcrCommitmentTrack>()
                    .eq(CcrCommitmentTrack::getApplicationId, t.getApplicationId())
                    .eq(CcrCommitmentTrack::getMetricCode, t.getMetricCode());
            if (StrUtil.isBlank(t.getMemberCustomerNo())) {
                existsW.isNull(CcrCommitmentTrack::getMemberCustomerNo);
            } else {
                existsW.eq(CcrCommitmentTrack::getMemberCustomerNo, t.getMemberCustomerNo());
            }
            Long exists = trackMapper.selectCount(existsW);
            if (exists != null && exists > 0) {
                log.info("申请 {} 指标 {} 成员 {} 已存在跟踪,幂等跳过", t.getApplicationId(), t.getMetricCode(), t.getMemberCustomerNo());
                continue;
            }
            t.setTrackNo("TRK" + day + String.format("%04d", seq++));
            // 状态/机构/客户经理由调用方显式赋值,此处兜底默认(不依赖 MetaObjectHandler session 填充)
            if (StrUtil.isBlank(t.getStatus())) {
                t.setStatus(STATUS_TRACKING);
            }
            trackMapper.insert(t);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int settleExpired() {
        int settled = 0;
        // 分批:每次捞一批 TRACKING 且已到期,处理完再查下一批(避免一次全量)
        while (true) {
            List<CcrCommitmentTrack> expired = trackMapper.selectList(
                    new LambdaQueryWrapper<CcrCommitmentTrack>()
                            .eq(CcrCommitmentTrack::getStatus, STATUS_TRACKING)
                            .lt(CcrCommitmentTrack::getEndDate, LocalDate.now())
                            .last("LIMIT " + SETTLE_BATCH));
            if (expired.isEmpty()) {
                break;
            }
            for (CcrCommitmentTrack t : expired) {
                settleOne(t);
            }
            settled += expired.size();
            if (expired.size() < SETTLE_BATCH) {
                break;
            }
        }
        if (settled > 0) {
            log.info("承诺跟踪到期结算 {} 行(按 data_dt<=end_date 最近批次定案)", settled);
        }
        return settled;
    }

    /** 单行到期定案:按 data_dt<=end_date 最近批次取数;条件更新 WHERE status='TRACKING' 幂等 */
    private void settleOne(CcrCommitmentTrack t) {
        WarehouseData data = fetchLatest(t.getCustomerNo(), t.getMemberCustomerNo(), t.getMetricCode(), t.getEndDate());
        boolean met;
        String remark = null;
        if (data.actual() == null) {
            // 截止日前无任何批次:按未完成判定,记 NULL + "数仓无数据"(D5 已拍板)
            met = false;
            remark = "数仓无数据";
        } else {
            met = data.actual().compareTo(t.getTargetValue()) >= 0;
        }
        BigDecimal ratio = data.actual() == null || t.getTargetValue() == null
                || t.getTargetValue().compareTo(BigDecimal.ZERO) == 0
                ? null : data.actual().divide(t.getTargetValue(), 4, RoundingMode.HALF_UP);
        // 显式 .set() 覆盖 null(MP update(entity, wrapper) 不更新 null 字段)
        trackMapper.update(null, new LambdaUpdateWrapper<CcrCommitmentTrack>()
                .eq(CcrCommitmentTrack::getId, t.getId())
                .eq(CcrCommitmentTrack::getStatus, STATUS_TRACKING)
                .set(CcrCommitmentTrack::getStatus, met ? STATUS_MET : STATUS_UNMET)
                .set(CcrCommitmentTrack::getFinalActual, data.actual())
                .set(CcrCommitmentTrack::getFinalRatio, ratio)
                .set(CcrCommitmentTrack::getFinalDataDt, data.dataDt())
                .set(CcrCommitmentTrack::getFinishTime, LocalDateTime.now())
                .set(CcrCommitmentTrack::getRemark, remark));
    }

    @Override
    public List<Map<String, Object>> listTracks(Long orgId, Long managerId, String customerNo, String status) {
        settleExpired();
        Scope scope = currentScope();
        LambdaQueryWrapper<CcrCommitmentTrack> w = new LambdaQueryWrapper<CcrCommitmentTrack>()
                .eq(orgId != null, CcrCommitmentTrack::getOrgId, orgId)
                .eq(managerId != null, CcrCommitmentTrack::getManagerId, managerId)
                .eq(StrUtil.isNotBlank(customerNo), CcrCommitmentTrack::getCustomerNo, customerNo)
                .eq(StrUtil.isNotBlank(status), CcrCommitmentTrack::getStatus, status);
        if (!scope.fullView()) {
            if (scope.customerManager()) {
                w.eq(CcrCommitmentTrack::getManagerId, scope.operatorId());
            } else if (scope.userOrgId() != null) {
                // 普通审批人按归属机构(新表无审批记录关联,退化为机构维度)
                w.eq(CcrCommitmentTrack::getOrgId, scope.userOrgId());
            }
        }
        w.orderByDesc(CcrCommitmentTrack::getCreateTime);
        List<CcrCommitmentTrack> rows = trackMapper.selectList(w);
        Set<String> customerNos = new LinkedHashSet<>();
        for (CcrCommitmentTrack t : rows) {
            customerNos.add(StrUtil.blankToDefault(t.getMemberCustomerNo(), t.getCustomerNo()));
        }
        Map<String, String> names = resolveCustomerNames(customerNos);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CcrCommitmentTrack t : rows) {
            result.add(toView(t, names));
        }
        return result;
    }

    @Override
    public Map<String, Object> trackDetail(Long trackId) {
        settleExpired();
        CcrCommitmentTrack t = trackMapper.selectById(trackId);
        if (t == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "承诺跟踪记录不存在: " + trackId);
        }
        Set<String> customerNos = new LinkedHashSet<>();
        customerNos.add(StrUtil.blankToDefault(t.getMemberCustomerNo(), t.getCustomerNo()));
        Map<String, Object> view = toView(t, resolveCustomerNames(customerNos));
        // 所属申请摘要(业务类型/状态/金额)
        if (t.getApplicationId() != null) {
            List<Map<String, Object>> app = jdbcTemplate.queryForList(
                    "SELECT a.application_no, a.business_type, a.status, a.submit_time, "
                            + "(SELECT SUM(pi2.pricing_amount) FROM ccr_pricing_item pi2 WHERE pi2.application_id = a.id) application_amount "
                            + "FROM ccr_application a WHERE a.id = ? AND a.del_flag = '0'", t.getApplicationId());
            if (!app.isEmpty()) {
                view.put("application", app.get(0));
            }
        }
        return view;
    }

    @Override
    public List<Map<String, Object>> orgAchievement(Long orgId) {
        settleExpired();
        StringBuilder sql = new StringBuilder("""
                SELECT org_id AS orgId,
                       COUNT(*) AS finishedTotal,
                       SUM(status = 'FINISHED_MET') AS metCount,
                       ROUND(SUM(status = 'FINISHED_MET') / COUNT(*), 4) AS metRate,
                       ROUND(AVG(LEAST(COALESCE(final_ratio, 0), 1)), 4) AS avgRatio
                FROM ccr_commitment_track
                WHERE del_flag = '0' AND status IN ('FINISHED_MET','FINISHED_UNMET')
                """);
        List<Object> params = new ArrayList<>();
        if (orgId != null) {
            sql.append(" AND org_id = ?");
            params.add(orgId);
        }
        sql.append(" GROUP BY org_id ORDER BY metRate DESC, finishedTotal DESC");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        // 机构名称补充
        for (Map<String, Object> row : rows) {
            Object orgIdVal = row.get("orgId");
            if (orgIdVal != null) {
                List<Map<String, Object>> dept = jdbcTemplate.queryForList(
                        "SELECT dept_name FROM ccr_sys_dept WHERE id = ? AND del_flag = '0'", orgIdVal);
                row.put("orgName", dept.isEmpty() ? null : dept.get(0).get("dept_name"));
            }
        }
        return rows;
    }

    /** 单条转视图:TRACKING 实时算完成度(数仓最新批次÷目标,无批次标暂无数据),终态读 final_* */
    private Map<String, Object> toView(CcrCommitmentTrack t, Map<String, String> names) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", t.getId());
        view.put("trackNo", t.getTrackNo());
        view.put("applicationId", t.getApplicationId());
        view.put("applicationNo", t.getApplicationNo());
        view.put("customerNo", t.getCustomerNo());
        view.put("memberCustomerNo", t.getMemberCustomerNo());
        String key = StrUtil.blankToDefault(t.getMemberCustomerNo(), t.getCustomerNo());
        view.put("customerName", names.get(key));
        view.put("orgId", t.getOrgId());
        view.put("managerId", t.getManagerId());
        view.put("metricCode", t.getMetricCode());
        view.put("metricName", t.getMetricName());
        view.put("targetKind", t.getTargetKind());
        view.put("targetValue", t.getTargetValue());
        view.put("unit", t.getUnit());
        view.put("endDate", t.getEndDate());
        view.put("status", t.getStatus());
        view.put("finishTime", t.getFinishTime());
        view.put("remark", t.getRemark());
        if (STATUS_TRACKING.equals(t.getStatus())) {
            // 实时完成度:数仓最新批次(不限 end_date,当前口径),无批次 → 暂无数据
            WarehouseData cur = fetchLatest(t.getCustomerNo(), t.getMemberCustomerNo(), t.getMetricCode(), null);
            if (cur.actual() == null) {
                view.put("actualValue", null);
                view.put("ratio", null);
                view.put("dataDt", null);
                view.put("dataStatus", "NO_DATA");
            } else {
                view.put("actualValue", cur.actual());
                view.put("ratio", computeRatio(cur.actual(), t.getTargetValue()));
                view.put("dataDt", cur.dataDt());
                view.put("dataStatus", "OK");
            }
            view.put("finalActual", null);
            view.put("finalRatio", null);
            view.put("finalDataDt", null);
        } else {
            view.put("actualValue", t.getFinalActual());
            view.put("ratio", t.getFinalRatio());
            view.put("dataDt", t.getFinalDataDt());
            view.put("dataStatus", "OK");
            view.put("finalActual", t.getFinalActual());
            view.put("finalRatio", t.getFinalRatio());
            view.put("finalDataDt", t.getFinalDataDt());
        }
        return view;
    }

    private BigDecimal computeRatio(BigDecimal actual, BigDecimal target) {
        if (actual == null || target == null || target.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return actual.divide(target, 4, RoundingMode.HALF_UP);
    }

    /**
     * 数仓取数:<= upTo(可空=最新)最近批次,CONTRIBUTION_AMOUNT 行优先,无则最新批次全行合计。
     * 成员承诺取成员客户号,否则主客户号。
     */
    private WarehouseData fetchLatest(String customerNo, String memberCustomerNo, String metricCode, LocalDate upTo) {
        String custNo = StrUtil.blankToDefault(memberCustomerNo, customerNo);
        if (StrUtil.isBlank(custNo) || StrUtil.isBlank(metricCode)) {
            return new WarehouseData(null, null);
        }
        LambdaQueryWrapper<DwContributionMetric> w = new LambdaQueryWrapper<DwContributionMetric>()
                .eq(DwContributionMetric::getCustNo, custNo)
                .eq(DwContributionMetric::getMetricCode, metricCode)
                .le(upTo != null, DwContributionMetric::getDataDt, upTo)
                .orderByDesc(DwContributionMetric::getDataDt);
        List<DwContributionMetric> rows = dwMetricMapper.selectList(w);
        if (rows.isEmpty()) {
            return new WarehouseData(null, null);
        }
        LocalDate latestDt = rows.get(0).getDataDt();
        List<DwContributionMetric> batch = new ArrayList<>();
        for (DwContributionMetric r : rows) {
            if (latestDt.equals(r.getDataDt())) {
                batch.add(r);
            }
        }
        List<DwContributionMetric> contribution = batch.stream()
                .filter(r -> VALUE_TYPE_CONTRIBUTION.equals(r.getValueType())).toList();
        List<DwContributionMetric> effective = contribution.isEmpty() ? batch : contribution;
        BigDecimal total = effective.stream()
                .map(r -> r.getMetricValue() == null ? BigDecimal.ZERO : r.getMetricValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new WarehouseData(total, latestDt);
    }

    /** 客户名批查:对公/个人主数据最新批次(按客户自身最新 data_dt) */
    private Map<String, String> resolveCustomerNames(Set<String> customerNos) {
        Map<String, String> names = new LinkedHashMap<>();
        if (customerNos.isEmpty()) {
            return names;
        }
        for (String custNo : customerNos) {
            if (StrUtil.isBlank(custNo)) {
                continue;
            }
            List<Map<String, Object>> corp = jdbcTemplate.queryForList(
                    "SELECT cust_name FROM caps_corp_cust_basic_info WHERE cust_no = ? "
                            + "AND data_dt = (SELECT MAX(data_dt) FROM caps_corp_cust_basic_info WHERE cust_no = ?)",
                    custNo, custNo);
            if (!corp.isEmpty() && corp.get(0).get("cust_name") != null) {
                names.put(custNo, corp.get(0).get("cust_name").toString());
                continue;
            }
            List<Map<String, Object>> indv = jdbcTemplate.queryForList(
                    "SELECT cust_nm FROM caps_indv_cust_basic_info WHERE cust_no = ? "
                            + "AND data_dt = (SELECT MAX(data_dt) FROM caps_indv_cust_basic_info WHERE cust_no = ?)",
                    custNo, custNo);
            if (!indv.isEmpty() && indv.get(0).get("cust_nm") != null) {
                names.put(custNo, indv.get(0).get("cust_nm").toString());
            }
        }
        return names;
    }

    /** 数仓取值结果:最近批次值 + 批次日期 */
    private record WarehouseData(BigDecimal actual, LocalDate dataDt) {
    }
}
