package com.ccr.admin.system.controller;

import com.ccr.common.cache.CacheConfigService;
import com.ccr.common.cache.dto.CacheConfigUpdateRequest;
import com.ccr.common.cache.dto.CacheConfigView;
import com.ccr.common.core.domain.R;
import com.ccr.vote.support.CurrentLoginUser;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 缓存项配置管理(详设 §3.6,仅 admin 角色):查看各缓存项生效配置(来源 YML/DB),
 * 更新 TTL/写入开关后立即生效不重启。
 */
@RestController
@RequestMapping("/system/cache-configs")
public class CacheConfigController {

    @Resource
    private CacheConfigService cacheConfigService;

    @Resource
    private CurrentLoginUser currentLoginUser;

    /** 缓存项配置列表(各项生效值 + 来源) */
    @GetMapping
    public R<List<CacheConfigView>> list() {
        requireAdmin();
        return R.ok(cacheConfigService.listEffective());
    }

    /** 更新缓存项配置(enabled/ttlSeconds 至少一项;立即生效不重启) */
    @PutMapping("/{itemKey}")
    public R<Void> update(@PathVariable String itemKey, @RequestBody CacheConfigUpdateRequest body) {
        requireAdmin();
        cacheConfigService.update(itemKey, body.getEnabled(), body.getTtlSeconds());
        return R.ok();
    }

    private void requireAdmin() {
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_ADMIN);
    }
}
