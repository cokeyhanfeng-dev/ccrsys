package com.ccr.common.cache;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存工具(详设 §3.6):统一前缀 {@code ccr:}、TTL 随机抖动、空值缓存、简单分布式锁、缓存项级配置。
 * <p>所有方法内部 catch Redis 异常降级——Redis 不可用时 {@code get} 返回 null(调用方直查库),
 * 写操作静默,业务不受影响;写路径不依赖缓存。
 * 每项缓存(见 {@link CacheItem})经 {@link CacheConfigHolder} 解析 DB 覆盖值:disabled 时
 * {@code get} 返回 null 直查库、{@code set} 跳过写入;TTL 优先级 DB覆盖 > yml items > 显式 > 全局默认。</p>
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
    private final CacheConfigHolder cacheConfigHolder;

    public CcrCacheUtil(RedisTemplate<String, Object> redisTemplate,
                        CcrCacheProperties properties,
                        CacheConfigHolder cacheConfigHolder) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.cacheConfigHolder = cacheConfigHolder;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** 读缓存:未命中、空值占位、Redis 降级或缓存项禁用均返回 null(禁用时直查库) */
    public Object get(String key) {
        if (!isEnabled() || !itemEnabled(key)) return null;
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
        if (!isEnabled() || !itemEnabled(key)) return;
        try {
            String k = prefix(key);
            if (value == null) {
                redisTemplate.opsForValue().set(k, EMPTY_MARKER, Duration.ofSeconds(properties.getEmptyTtlSeconds()));
            } else {
                redisTemplate.opsForValue().set(k, value, Duration.ofSeconds(jitter(resolveTtlSeconds(key, ttlSeconds))));
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

    /** 按前缀删除(缓存项配置变更失效前缀型 key 用,如 ccr:cfg:rate-limit:*;当前 key 规模小,KEYS 可接受) */
    public void deleteByPrefix(String prefix) {
        if (!isEnabled() || StrUtil.isBlank(prefix)) return;
        try {
            Set<String> keys = redisTemplate.keys(prefix(prefix) + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("[cache] 前缀删除 {} 共 {} 个 key", prefix, keys.size());
            }
        } catch (Exception e) {
            log.warn("[cache] Redis 前缀删除降级 prefix={}: {}", prefix, e.getMessage());
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

    // ---------- 缓存项级配置解析 ----------

    /** 命中缓存项且被禁用 → false;未命中任何项视为启用(行为与改造前一致) */
    private boolean itemEnabled(String key) {
        CacheItem item = CacheItem.match(key);
        if (item == null) return true;
        CacheItemOverride override = cacheConfigHolder.getOverride(item.getCode());
        if (override != null) return override.enabled();
        CcrCacheProperties.CacheItemProperties yml = properties.getItems().get(item.getCode());
        return yml == null || yml.getEnabled() == null || yml.getEnabled();
    }

    /** TTL 优先级:DB item 覆盖 > yml item > 显式/调用方 > 全局默认 */
    private long resolveTtlSeconds(String key, long explicitTtl) {
        CacheItem item = CacheItem.match(key);
        if (item != null) {
            CacheItemOverride override = cacheConfigHolder.getOverride(item.getCode());
            if (override != null && override.ttlSeconds() != null && override.ttlSeconds() > 0) {
                return override.ttlSeconds();
            }
            CcrCacheProperties.CacheItemProperties yml = properties.getItems().get(item.getCode());
            if (yml != null && yml.getTtlSeconds() != null && yml.getTtlSeconds() > 0) {
                return yml.getTtlSeconds();
            }
        }
        return explicitTtl;
    }
}
