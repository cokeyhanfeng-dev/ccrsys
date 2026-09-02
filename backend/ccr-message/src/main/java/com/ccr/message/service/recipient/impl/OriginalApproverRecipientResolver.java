package com.ccr.message.service.recipient.impl;

import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 原审批人(ORIGINAL_APPROVER)/原小组成员(ORIGINAL_VOTER):
 * 沿决议→分项→申请链路查审批操作人 / 表决委员
 */
@Component
public class OriginalApproverRecipientResolver implements RecipientResolver {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public boolean supports(String recipientType) {
        return "ORIGINAL_APPROVER".equals(recipientType) || "ORIGINAL_VOTER".equals(recipientType);
    }

    @Override
    public List<String> resolve(String recipientType, String recipientValue, RecipientContext context) {
        if (context.getResolutionId() == null) {
            return Collections.emptyList();
        }
        // 整单化后决议按申请维度落库、无分项关联(pricing_item_id 为 NULL,2026-08-29 起);
        // 原审批人/表决委员按申请维度查全部分项,兼容旧逐分项决议(2026-09-02)
        if ("ORIGINAL_APPROVER".equals(recipientType)) {
            List<String> ids = jdbcTemplate.queryForList(
                    """
                    SELECT DISTINCT aa.operator_id
                    FROM ccr_resolution r
                    LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id
                    JOIN ccr_pricing_item p2 ON p2.application_id = COALESCE(r.application_id, pi.application_id)
                    JOIN ccr_approval_action aa ON aa.pricing_item_id = p2.id AND aa.del_flag = '0'
                    WHERE r.id = ?
                    """,
                    String.class, context.getResolutionId());
            return ids == null ? Collections.emptyList() : ids;
        }
        List<String> ids = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT va.voter_user_id
                FROM ccr_resolution r
                LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id
                JOIN ccr_vote_round vr ON vr.application_id = COALESCE(r.application_id, pi.application_id) AND vr.del_flag = '0'
                JOIN ccr_vote_assignment va ON va.round_id = vr.id AND va.del_flag = '0'
                WHERE r.id = ?
                """,
                String.class, context.getResolutionId());
        return ids == null ? Collections.emptyList() : ids;
    }
}
