package com.ccr.common.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CcrCacheUtil 缓存项配置:disabled 不触碰 Redis、TTL 优先级 DB>yml>显式、前缀匹配、deleteByPrefix(§3.6) */
@ExtendWith(MockitoExtension.class)
class CcrCacheUtilTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> ops;

    private CcrCacheProperties properties;
    private CacheConfigHolder holder;
    private CcrCacheUtil util;

    @BeforeEach
    void setUp() {
        properties = new CcrCacheProperties();
        holder = new CacheConfigHolder();
        // lenient:部分用例(禁用不触 Redis / 前缀删除)故意不调用 opsForValue
        lenient().when(redisTemplate.opsForValue()).thenReturn(ops);
        util = new CcrCacheUtil(redisTemplate, properties, holder);
    }

    private void assertDurationNear(Duration actual, long expectedSeconds) {
        long s = actual.toSeconds();
        assertTrue(s >= expectedSeconds * 0.8 && s <= expectedSeconds * 1.2,
                "TTL " + s + "s 应接近 " + expectedSeconds + "s(±20% 抖动)");
    }

    @Test
    void disabledItemGetReturnsNullAndSetSkips() {
        holder.replaceAll(Map.of("matrix-effective", new CacheItemOverride(false, null)));
        assertNull(util.get(CcrCacheUtil.KEY_MATRIX_EFFECTIVE));
        util.set(CcrCacheUtil.KEY_MATRIX_EFFECTIVE, "v", 300);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void dbOverrideTtlWinsOverExplicit() {
        holder.replaceAll(Map.of("matrix-effective", new CacheItemOverride(true, 600L)));
        util.set(CcrCacheUtil.KEY_MATRIX_EFFECTIVE, "v", 300);
        ArgumentCaptor<Duration> cap = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("ccr:cfg:matrix:effective"), eq("v"), cap.capture());
        assertDurationNear(cap.getValue(), 600);
    }

    @Test
    void ymlItemTtlOverridesExplicit() {
        CcrCacheProperties.CacheItemProperties yml = new CcrCacheProperties.CacheItemProperties();
        yml.setEnabled(true);
        yml.setTtlSeconds(300L);
        properties.getItems().put("matrix-effective", yml);
        util.set(CcrCacheUtil.KEY_MATRIX_EFFECTIVE, "v", 120);
        ArgumentCaptor<Duration> cap = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("ccr:cfg:matrix:effective"), eq("v"), cap.capture());
        assertDurationNear(cap.getValue(), 300);
    }

    @Test
    void explicitTtlUsedWhenNoItemConfig() {
        // 无 DB 覆盖、无 yml item → 用显式 TTL 120s
        util.set("ccr:cfg:other:key", "v", 120);
        ArgumentCaptor<Duration> cap = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("ccr:cfg:other:key"), eq("v"), cap.capture());
        assertDurationNear(cap.getValue(), 120);
    }

    @Test
    void twoArgSetUsesDefaultTtl() {
        util.set(CcrCacheUtil.KEY_LPR_EFFECTIVE, "v");
        ArgumentCaptor<Duration> cap = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("ccr:cfg:lpr:effective"), eq("v"), cap.capture());
        assertDurationNear(cap.getValue(), properties.getDefaultTtlSeconds());
    }

    @Test
    void prefixItemMatchesRateLimitKey() {
        holder.replaceAll(Map.of("rate-limit", new CacheItemOverride(true, 60L)));
        util.set("ccr:cfg:rate-limit:LOAN:PUB_LOAN_01", "limit", 300);
        ArgumentCaptor<Duration> cap = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("ccr:cfg:rate-limit:LOAN:PUB_LOAN_01"), eq("limit"), cap.capture());
        assertDurationNear(cap.getValue(), 60);
    }

    @Test
    void deleteByPrefix() {
        when(redisTemplate.keys("ccr:cfg:rate-limit:*"))
                .thenReturn(Set.of("ccr:cfg:rate-limit:LOAN:A", "ccr:cfg:rate-limit:DEPOSIT:B"));
        util.deleteByPrefix("ccr:cfg:rate-limit:");
        verify(redisTemplate).delete(Set.of("ccr:cfg:rate-limit:LOAN:A", "ccr:cfg:rate-limit:DEPOSIT:B"));
    }

    @Test
    void globalVerKeyNotAffectedByItems() {
        // GLOBAL_VER_KEY 不命中任何 item,increment 行为不变
        holder.replaceAll(Map.of("matrix-effective", new CacheItemOverride(false, null)));
        when(ops.increment("ccr:cfg:v")).thenReturn(1L);
        assertEquals(1L, util.increment(CcrCacheUtil.GLOBAL_VER_KEY));
    }

    @Test
    void getHitReturnsValueWhenEnabled() {
        when(ops.get("ccr:cfg:lpr:effective")).thenReturn("lpr-v");
        assertEquals("lpr-v", util.get(CcrCacheUtil.KEY_LPR_EFFECTIVE));
    }
}
