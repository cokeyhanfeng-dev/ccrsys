package com.ccr.common.cache;

/**
 * DB ccr_cache_config 覆盖值(运行期运维意图,优先级高于 yml 静态默认)。
 * ttlSeconds 为 null 表示回退 yml/全局默认。
 */
public record CacheItemOverride(boolean enabled, Long ttlSeconds) {
}
