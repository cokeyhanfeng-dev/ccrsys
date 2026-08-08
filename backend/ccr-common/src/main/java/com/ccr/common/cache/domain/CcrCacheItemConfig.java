package com.ccr.common.cache.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 缓存项运行时配置(ccr_cache_config,详设 §3.6):覆盖 application.yml 静态默认,
 * 修改后经 {@code CacheConfigService.update} 立即刷新进程内 holder,不重启生效。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_cache_config")
public class CcrCacheItemConfig extends BaseEntity {

    /** 缓存项编码(lpr-effective / matrix-effective / rate-limit,对应 CacheItem.code) */
    private String itemKey;

    /** 写入开关 Y 启用(写缓存) / N 禁用(直查库) */
    private String enabled;

    /** TTL 秒;NULL=回退 yml/全局默认 */
    private Long ttlSeconds;
}
