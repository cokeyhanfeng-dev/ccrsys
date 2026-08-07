package com.ccr.common.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.common.outbox.domain.OutboxAlertNotification;
import org.apache.ibatis.annotations.Mapper;

/**
 * Outbox 告警通知 Mapper(ccr_notification_log 最小写入)
 */
@Mapper
public interface OutboxAlertNotificationMapper extends BaseMapper<OutboxAlertNotification> {
}
