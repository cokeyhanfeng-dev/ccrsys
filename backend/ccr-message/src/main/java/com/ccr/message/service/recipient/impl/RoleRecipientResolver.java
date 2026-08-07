package com.ccr.message.service.recipient.impl;

import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 指定角色(ROLE)及类角色类型(DEPT_GM/VICE_PRESIDENT):recipient_value 为角色编码,
 * 为空时按类型取默认角色编码
 */
@Component
public class RoleRecipientResolver implements RecipientResolver {

    /** 类角色类型 → 默认角色编码 */
    private static final Map<String, String> DEFAULT_ROLE = Map.of(
            "DEPT_GM", "dept_gm",
            "VICE_PRESIDENT", "vice_president");

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public boolean supports(String recipientType) {
        return "ROLE".equals(recipientType) || DEFAULT_ROLE.containsKey(recipientType);
    }

    @Override
    public List<String> resolve(String recipientType, String recipientValue, RecipientContext context) {
        if (recipientValue == null || recipientValue.isBlank()) {
            recipientValue = DEFAULT_ROLE.get(recipientType);
        }
        return queryByRole(recipientValue);
    }

    /** 供 ORG_POST 等复用:按角色编码查启用用户 */
    public List<String> queryByRole(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return Collections.emptyList();
        }
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ccr_sys_user WHERE role_code = ? AND status = 'ENABLE' AND del_flag = '0'",
                String.class, roleCode.trim());
        return ids == null ? Collections.emptyList() : ids;
    }
}
