package com.ccr.common.cache;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.cache.domain.CcrCacheItemConfig;
import com.ccr.common.cache.dto.CacheConfigCreateRequest;
import com.ccr.common.cache.dto.CacheConfigUpdateRequest;
import com.ccr.common.cache.dto.CacheConfigView;
import com.ccr.common.cache.dto.LoaderInfo;
import com.ccr.common.cache.mapper.CcrCacheItemConfigMapper;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 缓存项配置服务(详设 §3.6 v2):DB ccr_cache_config 全量定义 → {@link CacheConfigHolder} 进程内加载,
 * 管理端增删改后立即刷新 + 失效对应 Redis key,配置修改不重启生效。
 * <p>缓存项定义(增删改)+ 配置化刷新(数据加载器手动/定时写入 Redis)。</p>
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

    /** 数据加载器集合(@Resource Map 的 key 是 Spring bean 名,查找需按 code() 匹配);无实现时默认空 Map */
    @Resource
    private Map<String, CacheDataLoader> cacheDataLoaders = new HashMap<>();

    // ---------- 加载 ----------

    /** 全量重载 DB → holder;幂等,启动/定时/管理接口共用 */
    public void refresh() {
        List<CcrCacheItemConfig> rows = cacheConfigMapper.selectList(
                new LambdaQueryWrapper<CcrCacheItemConfig>()
                        .eq(CcrCacheItemConfig::getDelFlag, "0"));
        Map<String, CacheItemDef> map = new HashMap<>();
        for (CcrCacheItemConfig row : rows) {
            map.put(row.getItemKey(), toDef(row));
        }
        cacheConfigHolder.replaceAll(map);
    }

    /**
     * 启动加载:先补内置 3 项种子(幂等,老库行补填结构列),再载入 DB 定义;
     * 失败不阻断启动(DB 未就绪兜底默认值)。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            seedIfEmpty();
            refresh();
        } catch (Exception e) {
            log.warn("[cache] 缓存项配置启动加载失败,使用默认值: {}", e.getMessage());
        }
    }

    /** 内置项种子(幂等):行不存在 → 插入;存在但结构列空(老库 PUT 旧行) → 补填,不动 enabled/ttl */
    public void seedIfEmpty() {
        for (CacheItem item : CacheItem.values()) {
            CcrCacheItemConfig existing = cacheConfigMapper.selectOne(
                    new LambdaQueryWrapper<CcrCacheItemConfig>()
                            .eq(CcrCacheItemConfig::getItemKey, item.getCode())
                            .eq(CcrCacheItemConfig::getDelFlag, "0"));
            if (existing == null) {
                CcrCacheItemConfig row = new CcrCacheItemConfig();
                row.setItemKey(item.getCode());
                row.setCacheKey(item.getCacheKey());
                row.setKeyPattern(item.getKeyPattern());
                row.setDescription(item.getDesc());
                row.setEnabled("Y");
                row.setIsSystem("Y");
                row.setStatus("ENABLE");
                cacheConfigMapper.insert(row);
                log.info("[cache] 种子插入内置缓存项 {}", item.getCode());
            } else if (existing.getCacheKey() == null && existing.getKeyPattern() == null) {
                existing.setCacheKey(item.getCacheKey());
                existing.setKeyPattern(item.getKeyPattern());
                existing.setDescription(item.getDesc());
                existing.setIsSystem("Y");
                cacheConfigMapper.updateById(existing);
                log.info("[cache] 补填内置缓存项结构列 {}", item.getCode());
            }
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

    // ---------- 管理端列表 ----------

    /** 管理端列表:直读 DB 定义(DB 为唯一事实源,不依赖 holder 加载时机) */
    public List<CacheConfigView> listEffective() {
        List<CcrCacheItemConfig> rows = cacheConfigMapper.selectList(
                new LambdaQueryWrapper<CcrCacheItemConfig>()
                        .eq(CcrCacheItemConfig::getDelFlag, "0")
                        .orderByAsc(CcrCacheItemConfig::getCreateTime));
        List<CacheConfigView> views = new ArrayList<>();
        for (CcrCacheItemConfig row : rows) {
            CacheConfigView v = new CacheConfigView();
            v.setItemKey(row.getItemKey());
            v.setKey(row.getCacheKey());
            v.setKeyPattern(row.getKeyPattern());
            v.setEnabled("Y".equalsIgnoreCase(row.getEnabled()));
            v.setTtlSeconds(row.getTtlSeconds());
            v.setDescription(row.getDescription());
            v.setDataLoader(row.getDataLoader());
            v.setLoaderParam(row.getLoaderParam());
            v.setBuiltin("Y".equalsIgnoreCase(row.getIsSystem()));
            v.setSource("DB");
            views.add(v);
        }
        return views;
    }

    /** 数据加载器列表(管理端下拉) */
    public List<LoaderInfo> listLoaders() {
        List<LoaderInfo> result = new ArrayList<>();
        if (cacheDataLoaders != null) {
            for (CacheDataLoader loader : cacheDataLoaders.values()) {
                result.add(new LoaderInfo(loader.code(), loader.name()));
            }
        }
        return result;
    }

    // ---------- 增删改 ----------

    /** 新增缓存项:item_key 唯一、cacheKey/keyPattern 二选一、带 loader 必须是精确 key 项 */
    @Transactional(rollbackFor = Exception.class)
    public void create(CacheConfigCreateRequest req) {
        if (StrUtil.isBlank(req.getItemKey())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "itemKey 必填");
        }
        boolean hasKey = !StrUtil.isBlank(req.getCacheKey());
        boolean hasPattern = !StrUtil.isBlank(req.getKeyPattern());
        if (hasKey == hasPattern) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "cacheKey/keyPattern 必须二选一");
        }
        CcrCacheItemConfig dup = cacheConfigMapper.selectOne(
                new LambdaQueryWrapper<CcrCacheItemConfig>()
                        .eq(CcrCacheItemConfig::getItemKey, req.getItemKey())
                        .eq(CcrCacheItemConfig::getDelFlag, "0"));
        if (dup != null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "缓存项已存在:" + req.getItemKey());
        }
        if (req.getTtlSeconds() != null && req.getTtlSeconds() <= 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "ttlSeconds 必须大于0");
        }
        if (!StrUtil.isBlank(req.getDataLoader())) {
            if (!hasKey) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "带数据加载器的缓存项必须为精确 key 类型");
            }
            requireLoader(req.getDataLoader());
        }

        CcrCacheItemConfig row = new CcrCacheItemConfig();
        row.setItemKey(req.getItemKey());
        row.setCacheKey(req.getCacheKey());
        row.setKeyPattern(req.getKeyPattern());
        row.setEnabled((req.getEnabled() == null || req.getEnabled()) ? "Y" : "N");
        row.setTtlSeconds(req.getTtlSeconds());
        row.setDescription(req.getDescription());
        row.setDataLoader(req.getDataLoader());
        row.setLoaderParam(req.getLoaderParam());
        row.setIsSystem("N");
        row.setStatus("ENABLE");
        cacheConfigMapper.insert(row);
        refresh();
    }

    /** 更新缓存项:内置项禁改 cacheKey/keyPattern;写 DB → 刷新 holder → 失效 key → 递增全局版本号 */
    @Transactional(rollbackFor = Exception.class)
    public void update(String itemKey, CacheConfigUpdateRequest body) {
        CcrCacheItemConfig existing = getByItemKey(itemKey);
        boolean noField = body.getEnabled() == null && body.getTtlSeconds() == null
                && body.getDescription() == null && body.getDataLoader() == null
                && body.getLoaderParam() == null && body.getCacheKey() == null && body.getKeyPattern() == null;
        if (noField) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "至少提供一项修改字段");
        }
        if (body.getTtlSeconds() != null && body.getTtlSeconds() <= 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "ttlSeconds 必须大于0");
        }
        boolean isSystem = "Y".equalsIgnoreCase(existing.getIsSystem());
        boolean keyChanged = !Objects.equals(body.getCacheKey(), existing.getCacheKey())
                || !Objects.equals(body.getKeyPattern(), existing.getKeyPattern());
        if (isSystem && keyChanged) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "内置缓存项禁止修改 cacheKey/keyPattern");
        }
        if (body.getDataLoader() != null && !body.getDataLoader().isBlank()) {
            String effectiveKey = body.getCacheKey() != null ? body.getCacheKey() : existing.getCacheKey();
            if (StrUtil.isBlank(effectiveKey)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "带数据加载器的缓存项必须为精确 key 类型");
            }
            requireLoader(body.getDataLoader());
        }

        String oldCacheKey = existing.getCacheKey();
        String oldKeyPattern = existing.getKeyPattern();

        if (body.getEnabled() != null) {
            existing.setEnabled(body.getEnabled() ? "Y" : "N");
        }
        if (body.getTtlSeconds() != null) {
            existing.setTtlSeconds(body.getTtlSeconds());
        }
        if (body.getDescription() != null) {
            existing.setDescription(body.getDescription());
        }
        if (body.getDataLoader() != null) {
            existing.setDataLoader(body.getDataLoader());
        }
        if (body.getLoaderParam() != null) {
            existing.setLoaderParam(body.getLoaderParam());
        }
        if (body.getCacheKey() != null) {
            existing.setCacheKey(body.getCacheKey());
        }
        if (body.getKeyPattern() != null) {
            existing.setKeyPattern(body.getKeyPattern());
        }
        cacheConfigMapper.updateById(existing);

        refresh();
        if (!Objects.equals(oldCacheKey, existing.getCacheKey())
                || !Objects.equals(oldKeyPattern, existing.getKeyPattern())) {
            // 改了 key/pattern → 失效旧 key
            evictItem(new CacheItemDef(itemKey, oldCacheKey, oldKeyPattern,
                    true, null, null, null, null, isSystem));
        }
        evictItem(toDef(existing));
        cacheUtil.increment(CcrCacheUtil.GLOBAL_VER_KEY);
    }

    /** 删除缓存项:内置项禁止删除;物理删除(规避逻辑删除 + 唯一键冲突无法重建) */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String itemKey) {
        CcrCacheItemConfig existing = getByItemKey(itemKey);
        if ("Y".equalsIgnoreCase(existing.getIsSystem())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "内置缓存项禁止删除");
        }
        cacheConfigMapper.physicalDeleteByItemKey(itemKey);
        refresh();
        evictItem(toDef(existing));
        cacheUtil.increment(CcrCacheUtil.GLOBAL_VER_KEY);
    }

    // ---------- 配置化刷新 ----------

    /** 手动刷新缓存项数据:加载器加载 → 写入 Redis(TTL 取缓存项定义值/全局默认) → 返回行数 */
    public int refreshData(String itemKey) {
        CacheItemDef def = cacheConfigHolder.getDef(itemKey);
        if (def == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "缓存项不存在:" + itemKey);
        }
        if (StrUtil.isBlank(def.dataLoader())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "缓存项未配置数据加载器:" + itemKey);
        }
        if (StrUtil.isBlank(def.cacheKey())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "前缀型缓存项不支持数据刷新:" + itemKey);
        }
        CacheDataLoader loader = findLoader(def.dataLoader());
        if (loader == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "数据加载器不存在:" + def.dataLoader());
        }
        Object data = loader.load(def.loaderParam());
        long ttl = def.ttlSeconds() != null && def.ttlSeconds() > 0
                ? def.ttlSeconds() : properties.getDefaultTtlSeconds();
        cacheUtil.set(def.cacheKey(), data, ttl);
        int count = (data instanceof Collection<?> c) ? c.size() : (data == null ? 0 : 1);
        log.info("[cache] 刷新缓存项 {} → {} ({} 行)", itemKey, def.cacheKey(), count);
        return count;
    }

    /** 定时刷新:对启用 + 带加载器 + 精确 key 项逐个刷新,单项失败不阻断 */
    @Scheduled(cron = "${ccr.cache.data.refresh-cron:0 0 * * * *}")
    public void scheduledDataRefresh() {
        for (CacheItemDef def : cacheConfigHolder.snapshot().values()) {
            if (!def.enabled() || StrUtil.isBlank(def.dataLoader()) || StrUtil.isBlank(def.cacheKey())) {
                continue;
            }
            try {
                int n = refreshData(def.itemKey());
                log.info("[cache] 定时刷新缓存项 {} 完成,{} 行", def.itemKey(), n);
            } catch (Exception e) {
                log.warn("[cache] 定时刷新缓存项 {} 失败: {}", def.itemKey(), e.getMessage());
            }
        }
    }

    // ---------- 私有 ----------

    private CcrCacheItemConfig getByItemKey(String itemKey) {
        CcrCacheItemConfig existing = cacheConfigMapper.selectOne(
                new LambdaQueryWrapper<CcrCacheItemConfig>()
                        .eq(CcrCacheItemConfig::getItemKey, itemKey)
                        .eq(CcrCacheItemConfig::getDelFlag, "0"));
        if (existing == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "缓存项不存在:" + itemKey);
        }
        return existing;
    }

    private CacheItemDef toDef(CcrCacheItemConfig row) {
        return new CacheItemDef(row.getItemKey(), row.getCacheKey(), row.getKeyPattern(),
                "Y".equalsIgnoreCase(row.getEnabled()), row.getTtlSeconds(),
                row.getDescription(), row.getDataLoader(), row.getLoaderParam(),
                "Y".equalsIgnoreCase(row.getIsSystem()));
    }

    /** 失效对应缓存:精确 key 删单键;前缀型删全部变体 */
    private void evictItem(CacheItemDef def) {
        if (def == null) {
            return;
        }
        if (def.cacheKey() != null) {
            cacheUtil.delete(def.cacheKey());
        } else if (def.keyPattern() != null) {
            cacheUtil.deleteByPrefix(def.keyPattern());
        }
    }

    /** 按 code() 查找数据加载器(@Resource Map 的 key 是 bean 名,非 code) */
    private CacheDataLoader findLoader(String code) {
        if (cacheDataLoaders == null) {
            return null;
        }
        for (CacheDataLoader loader : cacheDataLoaders.values()) {
            if (code.equals(loader.code())) {
                return loader;
            }
        }
        return null;
    }

    private void requireLoader(String code) {
        if (findLoader(code) == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "数据加载器不存在:" + code);
        }
    }
}
