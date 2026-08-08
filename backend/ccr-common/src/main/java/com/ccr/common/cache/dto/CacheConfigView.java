package com.ccr.common.cache.dto;

import lombok.Data;

/** 缓存项配置管理端列表视图(生效值 + 来源 YML/DB) */
@Data
public class CacheConfigView {

    /** 缓存项编码(lpr-effective 等) */
    private String itemKey;

    /** 精确 key(前缀型为 null) */
    private String key;

    /** key 前缀(精确型为 null) */
    private String keyPattern;

    /** 生效的写入开关 */
    private Boolean enabled;

    /** 生效的 TTL 秒(null=用全局默认) */
    private Long ttlSeconds;

    /** 配置来源:YML / DB */
    private String source;
}
