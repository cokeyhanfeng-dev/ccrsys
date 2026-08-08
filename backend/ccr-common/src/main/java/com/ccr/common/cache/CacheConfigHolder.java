package com.ccr.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 缓存项运行时定义 holder(§3.6 v2):进程内保存 DB ccr_cache_config 全量定义,
 * 由 {@link CacheConfigService} 在启动/定时/管理接口更新后载入,改配置立即生效不重启。
 * <p>初始为空 Map(未加载 DB 时 matchDef 返回 null → 全部回退默认,行为与改造前无配置一致);
 * 引用原子替换,无锁读。</p>
 */
@Slf4j
@Component
public class CacheConfigHolder {

    /** item_code → 定义;线程安全(引用原子替换) */
    private final AtomicReference<Map<String, CacheItemDef>> defs =
            new AtomicReference<>(Map.of());

    public void replaceAll(Map<String, CacheItemDef> newDefs) {
        defs.set(Collections.unmodifiableMap(new HashMap<>(newDefs)));
        log.info("[cache] 缓存项运行时定义已刷新: {}", newDefs.keySet());
    }

    /** 无该缓存项定义返回 null → 调用方回退默认(启用,TTL 用显式/全局) */
    public CacheItemDef getDef(String itemCode) {
        return defs.get().get(itemCode);
    }

    /** 按缓存 key 匹配缓存项定义:精确 cacheKey 优先,再最长 keyPattern 前缀命中;未命中返回 null */
    public CacheItemDef matchDef(String cacheKey) {
        Map<String, CacheItemDef> map = defs.get();
        for (CacheItemDef d : map.values()) {
            if (d.cacheKey() != null && d.cacheKey().equals(cacheKey)) {
                return d;
            }
        }
        CacheItemDef best = null;
        int longest = -1;
        for (CacheItemDef d : map.values()) {
            if (d.keyPattern() != null && cacheKey.startsWith(d.keyPattern())
                    && d.keyPattern().length() > longest) {
                longest = d.keyPattern().length();
                best = d;
            }
        }
        return best;
    }

    public Map<String, CacheItemDef> snapshot() {
        return defs.get();
    }
}
