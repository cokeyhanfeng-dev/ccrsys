package com.ccr.common.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.cache.domain.CcrCacheItemConfig;
import com.ccr.common.cache.dto.CacheConfigView;
import com.ccr.common.cache.mapper.CcrCacheItemConfigMapper;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存项配置服务(详设 §3.6):DB ccr_cache_config 覆盖值 → {@link CacheConfigHolder} 进程内加载,
 * 管理端更新后立即刷新 + 失效对应 Redis key,配置修改不重启生效。
 * <p>优先级:DB 覆盖 > yml ccr.cache.items > 显式 TTL > 全局默认;启动加载失败不阻断(回退 yml)。</p>
 */
@Slf4j
@Service
public class CacheConfigService {

    @Resource
    private CcrCacheItemConfigMapper cacheConfigMapper;

    @Resource
    private CacheConfigHolder cacheConfigHolder;

    @Resource
    private CcrCacheUtil cacheUtil;

    @Resource
    private CcrCacheProperties properties;

    /** 全量重载 DB → holder;幂等,启动/定时/管理接口共用 */
    public void refresh() {
        List<CcrCacheItemConfig> rows = cacheConfigMapper.selectList(
                new LambdaQueryWrapper<CcrCacheItemConfig>()
                        .eq(CcrCacheItemConfig::getDelFlag, "0"));
        Map<String, CacheItemOverride> map = new HashMap<>();
        for (CcrCacheItemConfig row : rows) {
            map.put(row.getItemKey(),
                    new CacheItemOverride("Y".equalsIgnoreCase(row.getEnabled()), row.getTtlSeconds()));
        }
        cacheConfigHolder.replaceAll(map);
    }

    /** 启动加载:应用就绪后拉取 DB 覆盖值,失败不阻断启动(DB 未就绪兜底 yml) */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn("[cache] 缓存项配置启动加载失败,使用 yml 默认值: {}", e.getMessage());
        }
    }

    /** 定时兜底(外部直改 DB / 多实例):仅重载 holder,不做 Redis 失效 */
    @Scheduled(cron = "${ccr.cache.config.refresh-cron:0 */1 * * * *}")
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn("[cache] 缓存项配置定时刷新失败: {}", e.getMessage());
        }
    }

    /** 管理端列表:枚举全量 + 生效值 + 来源 */
    public List<CacheConfigView> listEffective() {
        List<CacheConfigView> views = new ArrayList<>();
        for (CacheItem item : CacheItem.values()) {
            CacheConfigView v = new CacheConfigView();
            v.setItemKey(item.getCode());
            v.setKey(item.getKey());
            v.setKeyPattern(item.getKeyPattern());
            CacheItemOverride override = cacheConfigHolder.getOverride(item.getCode());
            CcrCacheProperties.CacheItemProperties yml = properties.getItems().get(item.getCode());
            if (override != null) {
                v.setSource("DB");
                v.setEnabled(override.enabled());
                v.setTtlSeconds(override.ttlSeconds() != null ? override.ttlSeconds()
                        : (yml != null && yml.getTtlSeconds() != null ? yml.getTtlSeconds() : null));
            } else {
                v.setSource("YML");
                v.setEnabled(yml == null || yml.getEnabled() == null || yml.getEnabled());
                v.setTtlSeconds(yml == null ? null : yml.getTtlSeconds());
            }
            views.add(v);
        }
        return views;
    }

    /** 管理端更新:写 DB → 刷新 holder → 失效对应 Redis key → 递增全局版本号(立即生效不重启) */
    @Transactional(rollbackFor = Exception.class)
    public void update(String itemKey, Boolean enabled, Long ttlSeconds) {
        CacheItem.fromCode(itemKey); // 非法编码抛 400
        if (enabled == null && ttlSeconds == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "enabled/ttlSeconds 至少提供一项");
        }
        if (ttlSeconds != null && ttlSeconds <= 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "ttlSeconds 必须大于0(回退 yml 请传 null)");
        }
        upsert(itemKey, enabled, ttlSeconds);
        refresh();
        evictItem(itemKey);
        cacheUtil.increment(CcrCacheUtil.GLOBAL_VER_KEY);
    }

    /** 写配置表(item_key 唯一,存在则更新覆盖值) */
    private void upsert(String itemKey, Boolean enabled, Long ttlSeconds) {
        CcrCacheItemConfig existing = cacheConfigMapper.selectOne(
                new LambdaQueryWrapper<CcrCacheItemConfig>()
                        .eq(CcrCacheItemConfig::getItemKey, itemKey)
                        .eq(CcrCacheItemConfig::getDelFlag, "0"));
        if (existing == null) {
            CcrCacheItemConfig row = new CcrCacheItemConfig();
            row.setItemKey(itemKey);
            row.setEnabled((enabled == null || enabled) ? "Y" : "N"); // 新记录默认启用
            row.setTtlSeconds(ttlSeconds);
            row.setStatus("ENABLE");
            cacheConfigMapper.insert(row);
        } else {
            if (enabled != null) {
                existing.setEnabled(enabled ? "Y" : "N");
            }
            existing.setTtlSeconds(ttlSeconds); // null=清空 DB 覆盖,回退 yml
            cacheConfigMapper.updateById(existing);
        }
    }

    /** 失效对应缓存:精确 key 删单键;前缀型 item 删全部变体 */
    private void evictItem(String itemKey) {
        CacheItem item = CacheItem.fromCode(itemKey);
        if (item.getKey() != null) {
            cacheUtil.delete(item.getKey());
        } else if (item.getKeyPattern() != null) {
            cacheUtil.deleteByPrefix(item.getKeyPattern());
        }
    }
}
