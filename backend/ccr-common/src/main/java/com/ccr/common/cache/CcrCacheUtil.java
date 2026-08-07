package com.ccr.common.cache;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存工具(详设 §3.6):统一前缀 {@code ccr:}、TTL 随机抖动、空值缓存、简单分布式锁。
 * <p>所有方法内部 catch Redis 异常降级——Redis 不可用时 {@code get} 返回 null(调用方直查库),
 * 写操作静默,业务不受影响;写路径不依赖缓存。</p>
 */
@Slf4j
public class CcrCacheUtil {

    public static final String KEY_PREFIX = "ccr:";

    /** 全局版本号 key(配置发布失效:发布后递增,业务下拉比对版本号决定是否重建) */
    public static final String GLOBAL_VER_KEY = "ccr:cfg:v";

    /** LPR 当前生效版本缓存 key(路由 loadLpr/版本接口/提交 currentLpr 三处共用,发布时失效) */
    public static final String KEY_LPR_EFFECTIVE = "ccr:cfg:lpr:effective";

    /** 利率矩阵全量生效行缓存 key(路由计算按维度内存过滤,发布时失效) */
    public static final String KEY_MATRIX_EFFECTIVE = "ccr:cfg:matrix:effective";

    /** 空值缓存占位(反序列化后为空串) */
    private static final String EMPTY_MARKER = "";

    private final RedisTemplate<String, Object> redisTemplate;
    private final CcrCacheProperties properties;

    public CcrCacheUtil(RedisTemplate<String, Object> redisTemplate, CcrCacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** 读缓存:未命中、空值占位或 Redis 降级均返回 null */
    public Object get(String key) {
        if (!isEnabled()) return null;
        try {
            Object v = redisTemplate.opsForValue().get(prefix(key));
            if (v instanceof String s && s.isEmpty()) {
                return null; // 空值占位 → 视为未命中
            }
            return v;
        } catch (Exception e) {
            log.warn("[cache] Redis 读取降级 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /** 写缓存:null 值以空占位短 TTL 缓存(防穿透) */
    public void set(String key, Object value) {
        set(key, value, properties.getDefaultTtlSeconds());
    }

    public void set(String key, Object value, long ttlSeconds) {
        if (!isEnabled()) return;
        try {
            String k = prefix(key);
            if (value == null) {
                redisTemplate.opsForValue().set(k, EMPTY_MARKER, Duration.ofSeconds(properties.getEmptyTtlSeconds()));
            } else {
                redisTemplate.opsForValue().set(k, value, Duration.ofSeconds(jitter(ttlSeconds)));
            }
        } catch (Exception e) {
            log.warn("[cache] Redis 写入降级 key={}: {}", key, e.getMessage());
        }
    }

    /** 删除缓存(配置发布失效用) */
    public void delete(String... keys) {
        if (!isEnabled() || keys == null || keys.length == 0) return;
        try {
            for (String key : keys) {
                redisTemplate.delete(prefix(key));
            }
        } catch (Exception e) {
            log.warn("[cache] Redis 删除降级 keys={}: {}", String.join(",", keys), e.getMessage());
        }
    }

    /** 递增并返回新值(全局版本号),Redis 不可用返回 0 */
    public long increment(String key) {
        if (!isEnabled()) return 0;
        try {
            Long v = redisTemplate.opsForValue().increment(prefix(key));
            return v == null ? 0 : v;
        } catch (Exception e) {
            log.warn("[cache] Redis 递增降级 key={}: {}", key, e.getMessage());
            return 0;
        }
    }

    /** 简单分布式锁(SETNX+TTL);Redis 不可用降级放行(由 DB 约束兜底) */
    public boolean tryLock(String key, String token, long expireSeconds) {
        if (!isEnabled()) return true;
        try {
            Boolean ok = redisTemplate.opsForValue()
                    .setIfAbsent(prefix(key), token, Duration.ofSeconds(expireSeconds));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("[cache] Redis 锁降级 key={}: {}", key, e.getMessage());
            return true;
        }
    }

    /** 释放锁:仅 token 持有者可删(Lua 语义由 check-then-delete 模拟,满足常规场景) */
    public void unlock(String key, String token) {
        if (!isEnabled()) return;
        try {
            Object cur = redisTemplate.opsForValue().get(prefix(key));
            if (Objects.equals(token, cur)) {
                redisTemplate.delete(prefix(key));
            }
        } catch (Exception e) {
            log.warn("[cache] Redis 解锁降级 key={}: {}", key, e.getMessage());
        }
    }

    private String prefix(String key) {
        return StrUtil.startWith(key, KEY_PREFIX) ? key : KEY_PREFIX + key;
    }

    /** TTL 随机抖动:ttl*(1 ± jitter*rand),防缓存雪崩 */
    private long jitter(long ttl) {
        double ratio = properties.getTtlJitter();
        double f = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * ratio;
        long v = (long) (ttl * f);
        return Math.max(v, 1);
    }
}
