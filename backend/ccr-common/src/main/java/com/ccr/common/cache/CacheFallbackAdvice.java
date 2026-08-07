package com.ccr.common.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 缓存读穿/失效 AOP(详设 §3.6,降级回 DB)。
 * <ul>
 *   <li>{@link CcrCacheable}:读缓存命中即返回;未命中执行方法体(直查库),成功后回填;Redis 异常由 {@link CcrCacheUtil} 内部降级,null 回库,不阻断业务。</li>
 *   <li>{@link CcrCacheEvict}:方法执行前删除缓存(配置发布失效)。</li>
 * </ul>
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class CacheFallbackAdvice {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([0-9]+)\\}");

    private final CcrCacheUtil cacheUtil;

    @Around("@annotation(ccrCacheable)")
    public Object aroundCacheable(ProceedingJoinPoint pjp, CcrCacheable ccrCacheable) throws Throwable {
        String key = resolveKey(ccrCacheable.key(), pjp);
        Object cached = cacheUtil.get(key);
        if (cached != null) {
            return cached;
        }
        Object result = pjp.proceed();
        if (ccrCacheable.ttlSeconds() > 0) {
            cacheUtil.set(key, result, ccrCacheable.ttlSeconds());
        } else {
            cacheUtil.set(key, result);
        }
        return result;
    }

    @Around("@annotation(ccrCacheEvict)")
    public Object aroundCacheEvict(ProceedingJoinPoint pjp, CcrCacheEvict ccrCacheEvict) throws Throwable {
        String key = resolveKey(ccrCacheEvict.key(), pjp);
        if (ccrCacheEvict.allEntries()) {
            // 按前缀删除:遍历后 delete(前缀 key 本身一并删除)
            cacheUtil.delete(key);
        } else {
            cacheUtil.delete(key);
        }
        return pjp.proceed();
    }

    /** 解析 key:{i} 引用第 i 个入参 */
    private String resolveKey(String template, ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            String rep = idx < args.length && args[idx] != null ? args[idx].toString() : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
