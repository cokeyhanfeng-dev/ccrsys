package com.ccr.common.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 缓存统一状态机单测(详设 §3.6, lane-01 增量验收)。
 * 覆盖:统一前缀 / TTL 抖动 / 空值占位防穿透 / Redis 异常降级 / 分布式锁 token 校验 / 全局版本号。
 */
@ExtendWith(MockitoExtension.class)
class CcrCacheUtilTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    private CcrCacheProperties properties;

    private CcrCacheUtil cacheUtil;

    @BeforeEach
    void setUp() {
        properties = new CcrCacheProperties();
        properties.setEnabled(true);
        properties.setDefaultTtlSeconds(300);
        properties.setEmptyTtlSeconds(60);
        properties.setTtlJitter(0.2);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheUtil = new CcrCacheUtil(redisTemplate, properties);
    }

    // ---------- 统一前缀 ----------

    @Test
    void get_无前缀key_自动补ccr前缀() {
        cacheUtil.get("cfg:x");
        verify(valueOps).get("ccr:cfg:x");
    }

    @Test
    void get_已含前缀key_不重复拼接() {
        cacheUtil.get(CcrCacheUtil.KEY_LPR_EFFECTIVE);
        verify(valueOps).get(CcrCacheUtil.KEY_LPR_EFFECTIVE);
    }

    // ---------- 命中 / 空值占位 ----------

    @Test
    void get_命中_返回原值() {
        Object expected = new Object();
        when(valueOps.get("ccr:k")).thenReturn(expected);
        assertEquals(expected, cacheUtil.get("k"));
    }

    @Test
    void get_空值占位_视为未命中返回null() {
        when(valueOps.get("ccr:k")).thenReturn(""); // EMPTY_MARKER 反序列化为空串
        assertNull(cacheUtil.get("k"));
    }

    @Test
    void get_未命中_返回null() {
        when(valueOps.get("ccr:k")).thenReturn(null);
        assertNull(cacheUtil.get("k"));
    }

    // ---------- 空值缓存防穿透 ----------

    @Test
    void set_null_以空占位短TTL缓存() {
        cacheUtil.set("k", null);
        verify(valueOps).set("ccr:k", "", Duration.ofSeconds(60));
    }

    @Test
    void set_非空_使用抖动TTL() {
        cacheUtil.set("k", "v");
        verify(valueOps).set(eq("ccr:k"), eq("v"),
                argThat(d -> d.getSeconds() >= 240 && d.getSeconds() <= 360)); // 300*(1±0.2)
    }

    // ---------- 禁用开关 ----------

    @Test
    void disabled_全部直查库_不触达Redis() {
        properties.setEnabled(false);
        assertNull(cacheUtil.get("k"));
        assertTrue(cacheUtil.tryLock("lk", "t", 10)); // 禁用时锁降级放行,由 DB 约束兜底
        assertEquals(0, cacheUtil.increment(CcrCacheUtil.GLOBAL_VER_KEY));
        cacheUtil.set("k", "v");
        cacheUtil.delete("k");
        cacheUtil.unlock("lk", "t");
        verifyNoInteractions(valueOps);
    }

    // ---------- Redis 异常降级 ----------

    @Test
    void get_Redis异常_降级返回null() {
        when(valueOps.get("ccr:k")).thenThrow(new RuntimeException("conn refused"));
        assertNull(cacheUtil.get("k"));
    }

    @Test
    void set_Redis异常_静默不抛() {
        org.mockito.Mockito.doThrow(new RuntimeException("conn refused"))
                .when(valueOps).set(any(), any(), any(Duration.class));
        cacheUtil.set("k", "v"); // 不抛异常
    }

    @Test
    void increment_Redis异常_降级返回0() {
        when(valueOps.increment("ccr:cfg:v")).thenThrow(new RuntimeException("conn refused"));
        assertEquals(0, cacheUtil.increment(CcrCacheUtil.GLOBAL_VER_KEY));
    }

    @Test
    void delete_Redis异常_静默不抛() {
        org.mockito.Mockito.doThrow(new RuntimeException("conn refused"))
                .when(redisTemplate).delete("ccr:k");
        cacheUtil.delete("k"); // 不抛异常
    }

    // ---------- 全局版本号 ----------

    @Test
    void increment_正常_返回新值() {
        when(valueOps.increment("ccr:cfg:v")).thenReturn(3L);
        assertEquals(3, cacheUtil.increment(CcrCacheUtil.GLOBAL_VER_KEY));
    }

    // ---------- 分布式锁 ----------

    @Test
    void tryLock_获取成功() {
        when(valueOps.setIfAbsent("ccr:lk", "t", Duration.ofSeconds(10))).thenReturn(true);
        assertTrue(cacheUtil.tryLock("lk", "t", 10));
    }

    @Test
    void tryLock_已被占用_返回false() {
        when(valueOps.setIfAbsent("ccr:lk", "t", Duration.ofSeconds(10))).thenReturn(false);
        assertFalse(cacheUtil.tryLock("lk", "t", 10));
    }

    @Test
    void tryLock_Redis异常_降级放行() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
                .thenThrow(new RuntimeException("conn refused"));
        assertTrue(cacheUtil.tryLock("lk", "t", 10));
    }

    @Test
    void unlock_token匹配_删除锁() {
        when(valueOps.get("ccr:lk")).thenReturn("t");
        cacheUtil.unlock("lk", "t");
        verify(redisTemplate).delete("ccr:lk");
    }

    @Test
    void unlock_token不匹配_不删除() {
        when(valueOps.get("ccr:lk")).thenReturn("other");
        cacheUtil.unlock("lk", "t");
        verify(redisTemplate, never()).delete("ccr:lk");
    }

    @Test
    void delete_多key_逐个前缀化删除() {
        cacheUtil.delete("a", CcrCacheUtil.KEY_MATRIX_EFFECTIVE);
        verify(redisTemplate).delete("ccr:a");
        verify(redisTemplate).delete(CcrCacheUtil.KEY_MATRIX_EFFECTIVE);
    }
}
