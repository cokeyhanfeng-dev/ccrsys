package com.ccr.common.cache;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存读穿/失效 AOP 单测(详设 §3.6):{i} 占位符解析、命中短路、未命中回填、evict 前置失效。
 */
@ExtendWith(MockitoExtension.class)
class CacheFallbackAdviceTest {

    @Mock
    private CcrCacheUtil cacheUtil;

    @Mock
    private ProceedingJoinPoint pjp;

    private CacheFallbackAdvice advice;

    @BeforeEach
    void setUp() {
        advice = new CacheFallbackAdvice(cacheUtil);
    }

    @Test
    void aroundCacheable_key含占位符_替换为第i个入参() throws Throwable {
        CcrCacheable ann = mock(CcrCacheable.class);
        when(ann.key()).thenReturn("ccr:route:{1}:{0}");
        when(ann.ttlSeconds()).thenReturn(0L);
        when(pjp.getArgs()).thenReturn(new Object[]{"LPR", "2026-08-01"});
        Object result = new Object();
        when(pjp.proceed()).thenReturn(result);
        when(cacheUtil.get("ccr:route:2026-08-01:LPR")).thenReturn(null);

        assertEquals(result, advice.aroundCacheable(pjp, ann));
        verify(cacheUtil).set("ccr:route:2026-08-01:LPR", result); // ttl<=0 走默认 TTL(两参数)
    }

    @Test
    void aroundCacheable_缓存命中_短路不执行方法体() throws Throwable {
        CcrCacheable ann = mock(CcrCacheable.class);
        when(ann.key()).thenReturn("ccr:k");
        Object cached = new Object();
        when(pjp.getArgs()).thenReturn(new Object[0]);
        when(cacheUtil.get("ccr:k")).thenReturn(cached);

        assertEquals(cached, advice.aroundCacheable(pjp, ann));
        verify(pjp, never()).proceed();
    }

    @Test
    void aroundCacheEvict_方法执行前失效() throws Throwable {
        CcrCacheEvict ann = mock(CcrCacheEvict.class);
        when(ann.key()).thenReturn("ccr:cfg:v");
        when(pjp.proceed()).thenReturn("ok");
        assertEquals("ok", advice.aroundCacheEvict(pjp, ann));
        verify(cacheUtil).delete("ccr:cfg:v");
    }
}
