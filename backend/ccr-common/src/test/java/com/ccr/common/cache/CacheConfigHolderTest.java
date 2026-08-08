package com.ccr.common.cache;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** CacheConfigHolder 初始空/原子替换/不可变快照(§3.6) */
class CacheConfigHolderTest {

    @Test
    void initialEmpty() {
        CacheConfigHolder holder = new CacheConfigHolder();
        assertNull(holder.getOverride("matrix-effective"));
    }

    @Test
    void replaceAllThenRead() {
        CacheConfigHolder holder = new CacheConfigHolder();
        holder.replaceAll(Map.of("matrix-effective", new CacheItemOverride(false, 600L)));
        CacheItemOverride ov = holder.getOverride("matrix-effective");
        assertEquals(false, ov.enabled());
        assertEquals(600L, ov.ttlSeconds());
        assertNull(holder.getOverride("lpr-effective"));
    }

    @Test
    void snapshotImmutable() {
        CacheConfigHolder holder = new CacheConfigHolder();
        holder.replaceAll(Map.of("rate-limit", new CacheItemOverride(true, null)));
        Map<String, CacheItemOverride> snapshot = holder.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", new CacheItemOverride(true, null)));
    }
}
