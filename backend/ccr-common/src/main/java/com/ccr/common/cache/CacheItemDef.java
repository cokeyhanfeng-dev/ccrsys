package com.ccr.common.cache;

/**
 * 缓存项运行时定义(§3.6 v2):DB ccr_cache_config 全量定义,替代原 {@code CacheItemOverride} 覆盖值。
 * <p>cacheKey 与 keyPattern 二选一(精确 key vs 前缀);dataLoader 非空表示该缓存项由加载器
 * 刷新写入(business 代码写缓存时为空);builtin 内置项不可删除/不可改 cacheKey/keyPattern。</p>
 */
public record CacheItemDef(String itemKey, String cacheKey, String keyPattern,
                           boolean enabled, Long ttlSeconds,
                           String description, String dataLoader, String loaderParam,
                           boolean builtin) {
}
