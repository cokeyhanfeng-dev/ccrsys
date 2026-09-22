package com.ccr.message.controller;

import com.ccr.message.mapper.CcrNotificationLogMapper;
import com.ccr.message.service.dto.NotificationLogQuery;
import jakarta.validation.Validation;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NotificationAdminQueryTest {
    @Test void rejectsInvalidPaginationStatusesAndReversedTimeRange() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var query = new NotificationLogQuery();
            assertTrue(validator.validate(query).isEmpty());
            query.setPageNum(0);
            query.setPageSize(101);
            query.setChannel("UNKNOWN");
            query.setStartTime(LocalDateTime.of(2026, 9, 22, 12, 0));
            query.setEndTime(query.getStartTime().minusSeconds(1));
            assertEquals(4, validator.validate(query).size());
            query.setPageNum(1); query.setPageSize(100); query.setChannel("WECHAT");
            query.setEndTime(query.getStartTime());
            assertTrue(validator.validate(query).isEmpty());
        }
    }

    @Test void filteredSqlBindsInputsAndRestrictsReadStateToSystemChannel() {
        var query = new NotificationLogQuery();
        query.setRecipient("' OR 1=1 --");
        query.setRecipientOrgId(10L);
        query.setKeyword("%_'");
        query.setReceiptStatus("UNREAD");
        query.setSendStatus("FAILED");
        query.setStartTime(LocalDateTime.now());
        String sql = sql(query);
        assertTrue(sql.contains("n.del_flag = '0'"));
        assertTrue(sql.contains("u.org_id = ?"));
        assertTrue(sql.contains("n.send_status = ?"));
        assertTrue(sql.contains("n.channel = 'SYSTEM' AND n.receipt_time IS NULL"));
        assertTrue(sql.contains("n.create_time >= ?"));
        assertFalse(sql.contains(query.getRecipient()));
        assertFalse(sql.contains(query.getKeyword()));
        assertTrue(sql.contains("ORDER BY n.create_time DESC, n.id DESC"));
        query.setReceiptStatus("READ");
        assertTrue(sql(query).contains("n.receipt_time IS NOT NULL"));
    }

    @Test void unfilteredAdminQueryIncludesAllChannelsAndUnresolvedRecipients() {
        String sql = sql(new NotificationLogQuery());
        assertTrue(sql.contains("LEFT JOIN ccr_sys_user"));
        assertFalse(sql.contains("AND n.channel ="));
        assertFalse(sql.contains("u.org_id = ?"));
    }

    private String sql(NotificationLogQuery query) {
        var method = java.util.Arrays.stream(CcrNotificationLogMapper.class.getMethods())
            .filter(m -> m.getName().equals("selectAdminPage")).findFirst().orElseThrow();
        String script = String.join(" ", method.getAnnotation(Select.class).value());
        return new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class)
            .getBoundSql(Map.of("q", query)).getSql().replaceAll("\\s+", " ");
    }
}
