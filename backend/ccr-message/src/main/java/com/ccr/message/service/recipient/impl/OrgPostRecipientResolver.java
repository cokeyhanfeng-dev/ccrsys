package com.ccr.message.service.recipient.impl;

import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 指定机构岗位(ORG_POST):recipient_value 格式 "orgId:roleCode",
 * orgId 可省略(":roleCode")表示取触发上下文机构
 */
@Component
public class OrgPostRecipientResolver implements RecipientResolver {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public boolean supports(String recipientType) {
        return "ORG_POST".equals(recipientType);
    }

    @Override
    public List<String> resolve(String recipientType, String recipientValue, RecipientContext context) {
        if (recipientValue == null || recipientValue.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = recipientValue.split(":", 2);
        String roleCode = parts.length == 2 ? parts[1] : parts[0];
        Long orgId = context.getOrgId();
        if (parts.length == 2 && !parts[0].isBlank()) {
            orgId = Long.valueOf(parts[0].trim());
        }
        if (orgId == null || roleCode.isBlank()) {
            return Collections.emptyList();
        }
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ccr_sys_user WHERE org_id = ? AND role_code = ? AND status = 'ENABLE' AND del_flag = '0'",
                String.class, orgId, roleCode.trim());
        return ids == null ? Collections.emptyList() : ids;
    }
}
