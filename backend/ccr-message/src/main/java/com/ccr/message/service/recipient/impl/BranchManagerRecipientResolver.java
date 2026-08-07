package com.ccr.message.service.recipient.impl;

import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 支行行长(BRANCH_MANAGER):计划归属机构下角色为 branch_manager 的启用用户
 */
@Component
public class BranchManagerRecipientResolver implements RecipientResolver {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public boolean supports(String recipientType) {
        return "BRANCH_MANAGER".equals(recipientType);
    }

    @Override
    public List<String> resolve(String recipientType, String recipientValue, RecipientContext context) {
        if (context.getOrgId() == null) {
            return Collections.emptyList();
        }
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ccr_sys_user WHERE org_id = ? AND role_code = 'branch_manager' AND status = 'ENABLE' AND del_flag = '0'",
                String.class, context.getOrgId());
        return ids == null ? Collections.emptyList() : ids;
    }
}
