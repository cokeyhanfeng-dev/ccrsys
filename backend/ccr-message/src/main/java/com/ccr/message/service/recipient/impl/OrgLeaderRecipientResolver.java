package com.ccr.message.service.recipient.impl;

import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 集团管理机构负责人(GROUP_ORG_LEADER):机构表负责人姓名 → 用户表匹配
 */
@Component
public class OrgLeaderRecipientResolver implements RecipientResolver {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public boolean supports(String recipientType) {
        return "GROUP_ORG_LEADER".equals(recipientType);
    }

    @Override
    public List<String> resolve(String recipientType, String recipientValue, RecipientContext context) {
        if (context.getOrgId() == null) {
            return Collections.emptyList();
        }
        List<String> ids = jdbcTemplate.queryForList(
                """
                SELECT u.id FROM ccr_sys_dept d
                JOIN ccr_sys_user u ON u.nick_name = d.manager AND u.status = 'ENABLE' AND u.del_flag = '0'
                WHERE d.id = ? AND d.del_flag = '0'
                """,
                String.class, context.getOrgId());
        return ids == null ? Collections.emptyList() : ids;
    }
}
