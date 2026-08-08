package com.ccr.common.cache;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** CacheConfigHolder 初始空/原子替换/不可变快照 + matchDef 精确/最长前缀匹配(§3.6 v2) */
class CacheConfigHolderTest {

    private static CacheItemDef def(String itemKey, String cacheKey, String keyPattern,
                                    boolean enabled, Long ttl) {
        return new CacheItemDef(itemKey, cacheKey, keyPattern, enabled, ttl, null, null, null, false);
    }

    @Test
    void initialEmpty() {
        CacheConfigHolder holder = new CacheConfigHolder();
        assertNull(holder.getDef("matrix-effective"));
        assertNull(holder.matchDef("ccr:cfg:matrix:effective"));
    }

    @Test
    void replaceAllThenRead() {
        CacheConfigHolder holder = new CacheConfigHolder();
        holder.replaceAll(Map.of("matrix-effective", def("matrix-effective", "ccr:cfg:matrix:effective", null, false, 600L)));
        CacheItemDef d = holder.getDef("matrix-effective");
        assertEquals(false, d.enabled());
        assertEquals(600L, d.ttlSeconds());
        assertNull(holder.getDef("lpr-effective"));
    }

    @Test
    void snapshotImmutable() {
        CacheConfigHolder holder = new CacheConfigHolder();
        holder.replaceAll(Map.of("rate-limit", def("rate-limit", null, "ccr:cfg:rate-limit:", true, null)));
        Map<String, CacheItemDef> snapshot = holder.snapshot();
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put("x", def("x", "ccr:cfg:x", null, true, null)));
    }

    @Test
    void matchDefExactKeyFirst() {
        CacheConfigHolder holder = new CacheConfigHolder();
        holder.replaceAll(Map.of(
                "matrix-effective", def("matrix-effective", "ccr:cfg:matrix:effective", null, true, 600L),
                "rate-limit", def("rate-limit", null, "ccr:cfg:rate-limit:", true, 60L)));
        // 精确 key 命中(优先于前缀)
        CacheItemDef hit = holder.matchDef("ccr:cfg:matrix:effective");
        assertEquals("matrix-effective", hit.itemKey());
        assertEquals(600L, hit.ttlSeconds());
    }

    @Test
    void matchDefLongestPrefixWins() {
        CacheConfigHolder holder = new CacheConfigHolder();
        holder.replaceAll(Map.of(
                "rate-limit", def("rate-limit", null, "ccr:cfg:rate-limit:", true, 60L),
                "rate-limit-loan", def("rate-limit-loan", null, "ccr:cfg:rate-limit:LOAN:", true, 30L)));
        CacheItemDef hit = holder.matchDef("ccr:cfg:rate-limit:LOAN:PUB_LOAN_01");
        assertEquals("rate-limit-loan", hit.itemKey());
        assertEquals(30L, hit.ttlSeconds());
    }

    @Test
    void matchDefNoHit() {
        CacheConfigHolder holder = new CacheConfigHolder();
        holder.replaceAll(Map.of("rate-limit", def("rate-limit", null, "ccr:cfg:rate-limit:", true, null)));
        assertNull(holder.matchDef("ccr:cfg:v"));
        assertNull(holder.matchDef("ccr:other"));
    }
}
