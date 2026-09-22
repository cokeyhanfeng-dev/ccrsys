package com.ccr.admin.system.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.ccr.admin.mobile.MobileAccessService;
import com.ccr.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.util.*;

/** 只读枚举本系统会话：使用 SCAN，避免 Sa-Token 1.38 默认搜索内部的 Redis KEYS。 */
@Service
@RequiredArgsConstructor
public class OnlineSessionReader {
    public static final String LOGIN_TIME = "onlineLoginTime";
    public static final String LOGIN_IP = "onlineLoginIp";
    private static final int MAX_SESSIONS = 20000;
    private final StringRedisTemplate redis;

    /** 不把原始令牌、Redis key 或 TokenSession 对象交给响应层。 */
    public record Session(long userId, String client, Long loginTime, Long lastAccessTime, String loginIp) {}

    public List<Session> read() {
        StpLogic logic = StpUtil.getStpLogic();
        String prefix = logic.splicingKeyTokenValue("");
        Set<String> keys = new LinkedHashSet<>();
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(500).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
                if (keys.size() > MAX_SESSIONS) {
                    throw new ServiceException(503, "会话数量超过在线清单查询上限，请联系管理员");
                }
            }
        }
        List<Session> result = new ArrayList<>();
        for (String key : keys) {
            if (!key.startsWith(prefix)) continue;
            String token = key.substring(prefix.length());
            Object loginId = logic.getLoginIdByToken(token);
            if (loginId == null) continue; // 扫描期间退出、到期、被踢出的会话
            long userId;
            try { userId = Long.parseLong(loginId.toString()); }
            catch (NumberFormatException e) { continue; }
            if (userId <= 0) continue;
            long timeout = logic.getTokenTimeout(token);
            long activeTimeout = logic.getTokenActiveTimeoutByToken(token);
            if ((timeout < 0 && timeout != -1) || (activeTimeout < 0 && activeTimeout != -1)) continue;
            // false：旧会话没有 TokenSession 时不可因管理员查询而新建，也不续期或刷新活跃时间。
            SaSession metadata = logic.getTokenSessionByToken(token, false);
            String client = metadata != null && MobileAccessService.CLIENT.equals(metadata.get("client"))
                    || "mobile".equals(logic.getLoginDeviceByToken(token)) ? "MOBILE" : "PC";
            Long loginTime = metadata == null ? null : positiveLong(metadata.get(LOGIN_TIME));
            long lastActive = logic.getTokenLastActiveTime(token);
            String ip = metadata == null || metadata.get(LOGIN_IP) == null ? null : metadata.get(LOGIN_IP).toString();
            // 再次核验，尽量消除读取元数据期间已退出的会话；清单仍为请求时快照。
            if (!Objects.equals(loginId, logic.getLoginIdByToken(token))) continue;
            result.add(new Session(userId, client, loginTime, lastActive > 0 ? lastActive : null, ip));
        }
        return result;
    }

    private static Long positiveLong(Object value) {
        if (value instanceof Number number && number.longValue() > 0) return number.longValue();
        return null;
    }
}
