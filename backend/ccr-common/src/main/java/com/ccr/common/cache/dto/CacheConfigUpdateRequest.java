package com.ccr.common.cache.dto;

import lombok.Data;

/** 缓存项配置更新请求体(§3.6 v2);字段为 null 表示不修改 */
@Data
public class CacheConfigUpdateRequest {

    /** 写入开关;null=不改 */
    private Boolean enabled;

    /** TTL 秒;null=不改 */
    private Long ttlSeconds;

    /** 展示描述;null=不改 */
    private String description;

    /** 数据加载器编码;null=不改(内置项可配 loader) */
    private String dataLoader;

    /** 加载器参数 JSON;null=不改 */
    private String loaderParam;

    /** 精确 key;null=不改(内置项禁止修改) */
    private String cacheKey;

    /** key 前缀;null=不改(内置项禁止修改) */
    private String keyPattern;
}
