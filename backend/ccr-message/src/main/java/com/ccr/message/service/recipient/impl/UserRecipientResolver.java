package com.ccr.message.service.recipient.impl;

import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 指定用户(USER):recipient_value 为用户id
 */
@Component
public class UserRecipientResolver implements RecipientResolver {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public boolean supports(String recipientType) {
        return "USER".equals(recipientType);
    }

    @Override
    public List<String> resolve(String recipientType, String recipientValue, RecipientContext context) {
        if (recipientValue == null || recipientValue.isBlank()) {
            return Collections.emptyList();
        }
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ccr_sys_user WHERE id = ? AND status = 'ENABLE' AND del_flag = '0'",
                String.class, recipientValue.trim());
        return ids == null ? Collections.emptyList() : ids;
    }
}
