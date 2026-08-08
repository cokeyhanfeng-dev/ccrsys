package com.ccr.common.cache.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 缓存项定义(ccr_cache_config,详设 §3.6 v2):缓存项全量定义存于 DB,运行期由
 * {@code CacheConfigService} 载入进程内 holder,管理端增删改后立即生效不重启。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_cache_config")
public class CcrCacheItemConfig extends BaseEntity {

    /** 缓存项编码(唯一;内置 lpr-effective / matrix-effective / rate-limit) */
    private String itemKey;

    /** 精确 Redis key(与 keyPattern 二选一) */
    private String cacheKey;

    /** key 前缀(与 cacheKey 二选一,前缀匹配动态 key) */
    private String keyPattern;

    /** 写入开关 Y 启用(写缓存) / N 禁用(直查库) */
    private String enabled;

    /** TTL 秒;NULL=用全局默认 */
    private Long ttlSeconds;

    /** 展示描述 */
    private String description;

    /** 数据加载器编码(如 DW_TABLE;空=业务代码写缓存,无刷新能力) */
    private String dataLoader;

    /** 加载器参数 JSON(如 {"table":"dw_x","limit":5000}) */
    private String loaderParam;

    /** 内置项标记 Y(不可删除/不可改 cacheKey/keyPattern) / N(自定义) */
    private String isSystem;
}
