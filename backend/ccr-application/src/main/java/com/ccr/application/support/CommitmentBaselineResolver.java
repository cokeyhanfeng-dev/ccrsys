package com.ccr.application.support;

import cn.hutool.core.util.StrUtil;
import com.ccr.common.core.util.ContributionMerger;
import com.ccr.common.core.util.RelatedCustomerResolver;
import org.springframework.jdbc.core.JdbcTemplate;

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
