package com.ccr.common.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis 缓存配置(详设 §3.6)。
 * <p>全局开关 {@code ccr.cache.enabled=false} 时缓存全部直查库(Redis 未就绪场景不阻塞业务);
 * {@code items} 为每项缓存静态默认,DB ccr_cache_config 覆盖值优先级更高(修改后不重启生效)。</p>
 */
@Data
@ConfigurationProperties(prefix = "ccr.cache")
public class CcrCacheProperties {

    /** 缓存总开关 */
    private boolean enabled = true;

    /** 默认 TTL(秒);普通键、无显式 TTL 的键使用 */
    private long defaultTtlSeconds = 300;

    /** TTL 随机抖动比例(±,防雪崩):实际 TTL = ttl*(1 ± ttlJitter*rand) */
    private double ttlJitter = 0.2;

    /** 空值缓存 TTL(秒,防穿透) */
    private long emptyTtlSeconds = 60;

    /** 分布式锁默认过期秒 */
    private long lockExpireSeconds = 10;

    /** 每项缓存静态默认(key 为 {@link CacheItem} code;enabled/ttlSeconds 均可为 null=未配置) */
    private Map<String, CacheItemProperties> items = new HashMap<>();

    /** 每项缓存静态默认值(enabled null=默认启用;ttlSeconds null=回退显式/全局默认) */
    @Data
    public static class CacheItemProperties {
        private Boolean enabled;
        private Long ttlSeconds;
    }
}
