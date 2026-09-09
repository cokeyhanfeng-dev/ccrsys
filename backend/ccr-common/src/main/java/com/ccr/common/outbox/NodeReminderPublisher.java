package com.ccr.common.outbox;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 节点待办提醒随业务事务落 Outbox，外部发送由消费端完成。 */
@Component
public class NodeReminderPublisher {
    private final OutboxService outbox;

    public NodeReminderPublisher(OutboxService outbox) {
        this.outbox = outbox;
    }

    public void publish(Long applicationId, String nodeCode, String transitionKey, Long roundId, Long userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "NODE_REMINDER");
        payload.put("applicationId", applicationId);
        payload.put("nodeCode", nodeCode);
        payload.put("roundId", roundId);
        payload.put("userId", userId);
        String key = "NODE:" + DigestUtil.sha256Hex(applicationId + "|" + nodeCode + "|" + transitionKey).substring(0, 48);
        payload.put("messageKey", key);
        outbox.publish(OutboxEventType.NOTIFY, key, JSONUtil.toJsonStr(payload));
    }
}
