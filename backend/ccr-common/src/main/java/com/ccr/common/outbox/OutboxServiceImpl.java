package com.ccr.common.outbox;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.common.outbox.domain.CcrOutboxEvent;
import com.ccr.common.outbox.mapper.CcrOutboxEventMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Outbox 事件发布实现:event_no 确定性生成 + uk_event_no 唯一约束幂等
 */
@Slf4j
@Service
public class OutboxServiceImpl implements OutboxService {

    /** 待消费 */
    public static final String STATUS_PENDING = "PENDING";
    /** 消费中(乐观认领) */
    public static final String STATUS_PROCESSING = "PROCESSING";
    /** 消费成功 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 超过最大重试,终态 */
    public static final String STATUS_FAILED = "FAILED";

    @Value("${ccr.outbox.max-retry:5}")
    private int maxRetry = 5;

    @Resource
    private CcrOutboxEventMapper outboxEventMapper;

    @Override
    public CcrOutboxEvent publish(String eventType, String bizKey, String payloadJson) {
        if (StrUtil.isBlank(eventType) || StrUtil.isBlank(bizKey)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "Outbox 事件类型与业务键必填");
        }
        if (StrUtil.isBlank(payloadJson)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "Outbox 事件载荷必填");
        }
        CcrOutboxEvent event = new CcrOutboxEvent();
        event.setEventNo(eventType + ":" + bizKey);
        event.setEventType(eventType);
        event.setPayload(payloadJson);
        event.setStatus(STATUS_PENDING);
        event.setRetryCount(0);
        event.setMaxRetry(maxRetry);
        // 立即可被消费者扫描
        event.setNextRetryTime(LocalDateTime.now());
        try {
            outboxEventMapper.insert(event);
            return event;
        } catch (DuplicateKeyException e) {
            // event_no 唯一约束:重复发布幂等,返回已存在事件
            log.info("Outbox 事件重复发布,幂等跳过: {}", event.getEventNo());
            return outboxEventMapper.selectOne(new LambdaQueryWrapper<CcrOutboxEvent>()
                    .eq(CcrOutboxEvent::getEventNo, event.getEventNo()));
        }
    }
}
