package com.ccr.common.cache.dto;

import lombok.Data;

/** 缓存项创建请求体(§3.6 v2):cacheKey/keyPattern 二选一;dataLoader 非空必须是精确 key 项 */
@Data
public class CacheConfigCreateRequest {

    /** 缓存项编码(唯一) */
    private String itemKey;

    /** 精确 Redis key(与 keyPattern 二选一) */
    private String cacheKey;

    /** key 前缀(与 cacheKey 二选一,前缀匹配动态 key) */
    private String keyPattern;

    /** 写入开关(null=启用) */
    private Boolean enabled;

    /** TTL 秒(null=用全局默认) */
    private Long ttlSeconds;

    /** 展示描述 */
    private String description;

    /** 数据加载器编码(空=业务代码写缓存;非空必须是精确 key 项) */
    private String dataLoader;

    /** 加载器参数 JSON */
    private String loaderParam;
}
