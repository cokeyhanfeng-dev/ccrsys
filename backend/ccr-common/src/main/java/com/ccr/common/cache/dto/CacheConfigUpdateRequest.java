package com.ccr.common.cache.dto;

import lombok.Data;

/** 缓存项配置更新请求体;字段为 null 表示不修改(enabled 传 null 保持原状,ttlSeconds 传 null 清空 DB 覆盖回退 yml) */
@Data
public class CacheConfigUpdateRequest {

    /** 写入开关;null=不改 */
    private Boolean enabled;

    /** TTL 秒;null=清空 DB 覆盖回退 yml/全局默认 */
    private Long ttlSeconds;
}
