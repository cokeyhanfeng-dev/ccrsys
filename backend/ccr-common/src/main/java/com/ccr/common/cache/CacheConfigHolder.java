package com.ccr.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 缓存项运行时配置 holder(详设 §3.6):进程内保存 DB ccr_cache_config 覆盖值,
 * 由 {@link CacheConfigService} 在启动/定时/管理接口更新后载入,改配置立即生效不重启。
 * <p>初始为空 Map(未加载 DB 时 getOverride 返回 null → 全部回退 yml/全局默认,行为与现状一致);
 * 引用原子替换,无锁读。</p>
 */
@Slf4j
@Component
public class CacheConfigHolder {

    /** item_code → 覆盖值;线程安全(引用原子替换) */
    private final AtomicReference<Map<String, CacheItemOverride>> overrides =
            new AtomicReference<>(Map.of());

    public void replaceAll(Map<String, CacheItemOverride> newOverrides) {
        overrides.set(Collections.unmodifiableMap(new HashMap<>(newOverrides)));
        log.info("[cache] 缓存项运行时配置已刷新: {}", newOverrides.keySet());
    }

    /** 无 DB 覆盖返回 null → 调用方回退 yml/默认 */
    public CacheItemOverride getOverride(String itemCode) {
        return overrides.get().get(itemCode);
    }

    public Map<String, CacheItemOverride> snapshot() {
        return overrides.get();
    }
}
