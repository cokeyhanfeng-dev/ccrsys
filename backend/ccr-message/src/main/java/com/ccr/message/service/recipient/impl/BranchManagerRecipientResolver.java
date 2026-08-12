package com.ccr.message.service.recipient.impl;

import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 支行行长(BRANCH_MANAGER):按触发机构 branch_code 定位所属支行，再解析该支行启用行长。
 * 触发机构可为支行或下辖网点。
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
                """
                SELECT u.id
                FROM ccr_sys_user u
                JOIN ccr_sys_dept manager_dept ON manager_dept.id = u.org_id
                JOIN ccr_sys_dept source_dept ON source_dept.id = ?
                WHERE u.role_code = 'branch_manager' AND u.status = 'ENABLE' AND u.del_flag = '0'
                  AND manager_dept.status = 'ENABLE' AND manager_dept.del_flag = '0'
                  AND manager_dept.org_type = 'BRANCH'
                  AND manager_dept.branch_code = source_dept.branch_code
                """,
                String.class, context.getOrgId());
        return ids == null ? Collections.emptyList() : ids;
    }
}
