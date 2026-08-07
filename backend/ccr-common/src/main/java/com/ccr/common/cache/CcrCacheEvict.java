package com.ccr.common.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 缓存失效注解(详设 §3.6):标注在配置发布/变更方法上,方法执行前删除对应缓存键。
 * <p>key 支持 {@code {i}} 占位引用入参;{@code allEntries=true} 时按 key 作为前缀删除。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CcrCacheEvict {

    /** 缓存 key(前缀匹配用) */
    String key();

    /** 是否按前缀删除该 key 下全部缓存 */
    boolean allEntries() default false;
}
