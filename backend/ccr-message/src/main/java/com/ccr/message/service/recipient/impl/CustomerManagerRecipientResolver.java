package com.ccr.message.service.recipient.impl;

import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 客户经理类(CUSTOMER_MANAGER/GROUP_MANAGER/MEMBER_MANAGER):
 * 沿 计划→决议→定价分项→申请 链路解析到申请人(客户经理)
 */
@Component
public class CustomerManagerRecipientResolver implements RecipientResolver {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public boolean supports(String recipientType) {
        return "CUSTOMER_MANAGER".equals(recipientType)
                || "GROUP_MANAGER".equals(recipientType)
                || "MEMBER_MANAGER".equals(recipientType);
    }

    @Override
    public List<String> resolve(String recipientType, String recipientValue, RecipientContext context) {
        if (context.getResolutionId() == null) {
            return Collections.emptyList();
        }
        List<String> ids = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT a.applicant_user_id
                FROM ccr_resolution r
                JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id
                JOIN ccr_application a ON a.id = pi.application_id
                WHERE r.id = ? AND a.applicant_user_id IS NOT NULL
                """,
                String.class, context.getResolutionId());
        return ids == null ? Collections.emptyList() : ids;
    }
}
