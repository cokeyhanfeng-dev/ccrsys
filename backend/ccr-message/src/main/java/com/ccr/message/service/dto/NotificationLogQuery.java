package com.ccr.message.service.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

/** 管理员投递记录查询；时间按消息生成时间筛选。 */
@Data
public class NotificationLogQuery {
    @Min(1) private int pageNum = 1;
    @Min(1) @Max(100) private int pageSize = 20;
    @Size(max = 100) private String recipient;
    @Positive private Long recipientOrgId;
    @Size(max = 200) private String keyword;
    @Pattern(regexp = "SYSTEM|WECHAT|SMS|EMAIL") private String channel;
    @Pattern(regexp = "PENDING|PROCESSING|SUCCESS|FAILED|RETRYING") private String sendStatus;
    @Pattern(regexp = "READ|UNREAD") private String receiptStatus;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private LocalDateTime startTime;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private LocalDateTime endTime;
    @AssertTrue(message = "结束时间不能早于开始时间")
    public boolean isTimeRangeValid() {
        return startTime == null || endTime == null || !endTime.isBefore(startTime);
    }
}
