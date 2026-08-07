package com.ccr.common.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 缓存读穿注解(详设 §3.6):标注在热点读方法上,
 * 由 {@link CacheFallbackAdvice} 拦截实现"读缓存→未命中执行方法→回填"。
 * <p>key 支持 {@code {i}} 占位引用第 i 个入参(0 起),统一前缀 {@code ccr:} 自动加。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CcrCacheable {

    /** 缓存 key,支持 {i} 占位引用入参 */
    String key();

    /** TTL 秒;<=0 用默认 TTL */
    long ttlSeconds() default -1;
}
