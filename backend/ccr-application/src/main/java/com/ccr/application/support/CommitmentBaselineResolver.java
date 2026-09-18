package com.ccr.application.support;

import cn.hutool.core.util.StrUtil;
import com.ccr.common.core.util.ContributionMerger;
import com.ccr.common.core.util.RelatedCustomerResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 承诺基线归并口径单点实现(§关联人贡献度归并;2026-09-16 收敛)。
 *
 * <p><b>为什么要有这个类:</b>此前"当前贡献度归并关联人"存在两套口径——
 * 前端选客户时走 {@code CustomerController.mergeWithRelated}(按 customer_no 反查该客户
 * <b>历史所有申请</b>的关联人,且证件号兜底反查数仓主数据),后端提交时走
 * {@code CcrApplicationServiceImpl.resolveBaseline}(只取当前 application_id 的关联人,无兜底)。
 * 两套口径在「本笔新录关联人 / 历史录过关联人」时结果不同,导致申请页带出的基线值与后端校验
 * 实际使用的基线值不一致——客户经理看着能过、提交被拦。现收敛到本类,两处共用:
 * <b>页面显示什么,后端就校验什么。</b></p>
 *
 * <p>归并规则沿用 {@link ContributionMerger}:同 metric_code 值加总、最新批次、
 * 折算(CONTRIBUTION_AMOUNT)行优先;RATIO 派生指标(存贷款比)不归并;数仓推的关系不参与。</p>
 */
public final class CommitmentBaselineResolver {

    private CommitmentBaselineResolver() {
    }

    /**
     * 承诺基线值(§基线=申请时点当前值):数仓该指标最近批次值,单户归并关联人同码值、集团不归并。
     *
     * <p>2026-09-16 收敛:原实现散在 {@code CcrApplicationServiceImpl.resolveBaseline}(保存草稿时算)
     * 与 {@code ApplicationSubmitServiceImpl}(提交时算)两处,口径易分叉。现单点定义,两处共用——
     * <b>保存草稿时算什么,提交时就重算什么。</b></p>
     *
     * <p>取数号(§2026-09-16 承诺去掉成员维度):<b>单户按客户号,集团申请(customer_no 为空)按集团号</b>——
     * 数仓 dw_contribution_metric 已按集团编号汇总分指标行,与申请页集团贡献度面板同口径。</p>
     *
     * <p>§2026-09-17 用户拍板:<b>数仓无该指标数据时基线按 0</b>(原为空)。返回 null 仅剩三种非「数仓无值」场景:
     * OTHER 手工承诺(无数值目标)、无客户标识(数据异常,前置完整性校验已拦)、指标码已停用被字典收敛。</p>
     *
     * @param metricCode 指标码;为 OTHER 时返回 null
     * @param customerNo 单户客户号;为空则退而用 groupNo
     * @param groupNo    集团号
     */
    public static BigDecimal resolveBaseline(JdbcTemplate jdbcTemplate, String metricCode,
                                            String customerNo, String groupNo) {
        String scopeCustNo = StrUtil.isNotBlank(customerNo) ? customerNo : groupNo;
        if (StrUtil.isBlank(scopeCustNo) || "OTHER".equals(metricCode)) {
            return null;
        }
        // 主客户该指标最近批次值(无数据构造空行供归并)
        // §2026-09-14 修复:必须带出 metric_code——下游 ContributionMerger 按指标码收敛,缺该列会被整体移除,
        // 致索引越界;仅数仓无数据走空行兜底时才由下方补码,故此处显式查询
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT metric_code metricCode, metric_value metricValue, value_type valueType FROM dw_contribution_metric"
                        + " WHERE cust_no = ? AND metric_code = ?"
                        + " AND data_dt = (SELECT MAX(d2.data_dt) FROM dw_contribution_metric d2"
                        + "   WHERE d2.cust_no = dw_contribution_metric.cust_no AND d2.metric_code = dw_contribution_metric.metric_code)"
                        + " LIMIT 1", scopeCustNo, metricCode);
        Map<String, Object> mainRow = rows.isEmpty() ? new HashMap<>() : new HashMap<>(rows.get(0));
        if (mainRow.isEmpty()) {
            mainRow.put("metricCode", metricCode);
        }
        List<Map<String, Object>> contribution = new ArrayList<>();
        contribution.add(mainRow);
        // 单户按客户号取数时归并该客户名下关联人同码值;集团按集团号取数不归并(集团号无关联人)。
        if (StrUtil.isNotBlank(customerNo)) {
            mergeRelated(jdbcTemplate, contribution, scopeCustNo);
        }
        // 归并后可能被指标字典收敛为空(指标码不在 ACTIVE 字典),此时基线留空
        if (contribution.isEmpty()) {
            return null;
        }
        Object value = contribution.get(0).get("metricValue");
        // §2026-09-17 用户拍板:数仓无该指标数据时基线按 0(原为空/不比较)。
        // 口径=页面显示 0、前端与后端校验按 0(目标须严格大于 0)、落库 baseline_value=0,三处同源。
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    /**
     * 归并该客户名下关联人的贡献度(原地修改)。
     *
     * <p>注意:本方法<b>无条件</b>调用 {@link ContributionMerger#mergeRelatedContributions}——
     * 该方法在判断有无关联人之前会先做「按启用指标字典收敛 + 同指标多 value_type 去重」,
     * 若因"无关联人"提前返回会漏掉收敛步骤,导致与页面口径再次分叉。</p>
     *
     * @param contribution 主客户贡献度行,每行含 camelCase 字段 metricCode/metricValue/valueType
     * @param customerNo   主客户号;为空时仅收敛不归并
     * @return 传入的列表(便于链式写法)
     */
    public static List<Map<String, Object>> mergeRelated(JdbcTemplate jdbcTemplate,
                                                        List<Map<String, Object>> contribution,
                                                        String customerNo) {
        if (contribution == null || contribution.isEmpty()) {
            return contribution;
        }
        ContributionMerger.mergeRelatedContributions(jdbcTemplate, contribution, relatedCustomerNos(jdbcTemplate, customerNo));
        return contribution;
    }

    /**
     * 该客户应参与贡献度加总的关联人客户号集合(去重,保持录入顺序):
     * 按 customer_no 反查其历史所有申请的关联人;related_customer_no 为空时按证件号兜底反查数仓主数据补全。
     *
     * @param customerNo 主客户号;为空返回空集合
     */
    public static Set<String> relatedCustomerNos(JdbcTemplate jdbcTemplate, String customerNo) {
        Set<String> relatedNos = new LinkedHashSet<>();
        if (StrUtil.isBlank(customerNo)) {
            return relatedNos;
        }
        List<Map<String, Object>> relations = jdbcTemplate.queryForList(
                "SELECT rp.related_customer_no relatedCustomerNo, rp.cert_type certType, rp.cert_no certNo"
                        + " FROM ccr_application_related_person rp"
                        + " JOIN ccr_application a ON a.id = rp.application_id AND a.del_flag = '0'"
                        + " WHERE a.customer_no = ? AND rp.del_flag = '0'", customerNo);
        RelatedCustomerResolver.resolveBatch(jdbcTemplate, relations);
        for (Map<String, Object> rel : relations) {
            Object no = rel.get("relatedCustomerNo");
            if (no != null && !no.toString().isBlank()) {
                relatedNos.add(no.toString());
            }
        }
        return relatedNos;
    }
}
