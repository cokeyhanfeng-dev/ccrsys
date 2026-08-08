package com.ccr.common.cache.dto;

import lombok.Data;

/** 缓存项配置管理端列表视图(§3.6 v2):DB 定义为唯一事实源,来源恒为 DB */
@Data
public class CacheConfigView {

    /** 缓存项编码 */
    private String itemKey;

    /** 精确 key(前缀型为 null) */
    private String key;

    /** key 前缀(精确型为 null) */
    private String keyPattern;

    /** 生效的写入开关 */
    private Boolean enabled;

    /** 生效的 TTL 秒(null=用全局默认) */
    private Long ttlSeconds;

    /** 展示描述 */
    private String description;

    /** 数据加载器编码(空=业务代码写缓存) */
    private String dataLoader;

    /** 加载器参数 JSON */
    private String loaderParam;

    /** 是否内置项(Y=系统内置,不可删/不可改 key) */
    private Boolean builtin;

    /** 配置来源(恒为 DB) */
    private String source;
}
