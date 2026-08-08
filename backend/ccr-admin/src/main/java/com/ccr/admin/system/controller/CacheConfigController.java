package com.ccr.admin.system.controller;

import com.ccr.common.cache.CacheConfigService;
import com.ccr.common.cache.dto.CacheConfigCreateRequest;
import com.ccr.common.cache.dto.CacheConfigUpdateRequest;
import com.ccr.common.cache.dto.CacheConfigView;
import com.ccr.common.cache.dto.LoaderInfo;
import com.ccr.common.core.domain.R;
import com.ccr.vote.support.CurrentLoginUser;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 缓存项配置管理(详设 §3.6 v2,仅 admin 角色):缓存项定义增删改查 + 配置化刷新
 * (数据加载器手动/定时把数仓数据写入 Redis),修改后立即生效不重启。
 */
@RestController
@RequestMapping("/system/cache-configs")
public class CacheConfigController {

    @Resource
    private CacheConfigService cacheConfigService;

    @Resource
    private CurrentLoginUser currentLoginUser;

    /** 缓存项定义列表(DB 为唯一事实源) */
    @GetMapping
    public R<List<CacheConfigView>> list() {
        requireAdmin();
        return R.ok(cacheConfigService.listEffective());
    }

    /** 新增缓存项(cacheKey/keyPattern 二选一;带数据加载器必须是精确 key 项) */
    @PostMapping
    public R<Void> create(@RequestBody CacheConfigCreateRequest body) {
        requireAdmin();
        cacheConfigService.create(body);
        return R.ok();
    }

    /** 更新缓存项(内置项禁改 cacheKey/keyPattern;立即生效不重启) */
    @PutMapping("/{itemKey}")
    public R<Void> update(@PathVariable String itemKey, @RequestBody CacheConfigUpdateRequest body) {
        requireAdmin();
        cacheConfigService.update(itemKey, body);
        return R.ok();
    }

    /** 删除缓存项(内置项禁止删除) */
    @DeleteMapping("/{itemKey}")
    public R<Void> delete(@PathVariable String itemKey) {
        requireAdmin();
        cacheConfigService.delete(itemKey);
        return R.ok();
    }

    /** 手动刷新缓存项数据:数据加载器加载最新数据写入 Redis */
    @PostMapping("/{itemKey}/refresh")
    public R<Map<String, Object>> refreshData(@PathVariable String itemKey) {
        requireAdmin();
        int count = cacheConfigService.refreshData(itemKey);
        return R.ok(Map.of("count", count));
    }

    /** 数据加载器列表(管理端新增/编辑下拉) */
    @GetMapping("/loaders")
    public R<List<LoaderInfo>> loaders() {
        requireAdmin();
        return R.ok(cacheConfigService.listLoaders());
    }

    private void requireAdmin() {
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_ADMIN);
    }
}
