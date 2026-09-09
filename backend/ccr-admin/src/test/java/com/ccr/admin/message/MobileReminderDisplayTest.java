package com.ccr.admin.message;

import com.ccr.admin.mobile.MobileQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MobileReminderDisplayTest {
    @Test void mobileShowsOneReminderPerNodeAndBindsCurrentUser() {
        var service = new MobileQueryService();
        var jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        service.messages(42L);
        verify(jdbc).queryForList(contains("recipient_id=? AND del_flag='0' AND NOT (channel='WECHAT' AND message_key LIKE 'NR:%')"), eq("42"));
    }
}
