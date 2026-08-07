package com.ccr.common.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis 缓存配置(详设 §3.6)。
 * <p>全局开关 {@code ccr.cache.enabled=false} 时缓存全部直查库(Redis 未就绪场景不阻塞业务)。</p>
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
}
