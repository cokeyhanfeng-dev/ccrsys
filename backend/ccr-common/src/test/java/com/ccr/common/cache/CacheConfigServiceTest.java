package com.ccr.common.cache;

import com.ccr.common.cache.domain.CcrCacheItemConfig;
import com.ccr.common.cache.dto.CacheConfigCreateRequest;
import com.ccr.common.cache.dto.CacheConfigUpdateRequest;
import com.ccr.common.cache.mapper.CcrCacheItemConfigMapper;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CacheConfigService:create/update/delete/refreshData 链路、内置项保护、400/404 校验、refresh 映射(§3.6 v2) */
@ExtendWith(MockitoExtension.class)
class CacheConfigServiceTest {

    @Mock
    private CcrCacheItemConfigMapper cacheConfigMapper;
    @Mock
    private CacheConfigHolder cacheConfigHolder;
    @Mock
    private CcrCacheUtil cacheUtil;
    @Mock
    private CcrCacheProperties properties;
    @Mock
    private CacheDataLoader dwLoader;

    @InjectMocks
    private CacheConfigService service;

    private static CcrCacheItemConfig row(String itemKey, String cacheKey, String keyPattern,
                                          String enabled, Long ttl, String isSystem) {
        CcrCacheItemConfig r = new CcrCacheItemConfig();
        r.setItemKey(itemKey);
        r.setCacheKey(cacheKey);
        r.setKeyPattern(keyPattern);
        r.setEnabled(enabled);
        r.setTtlSeconds(ttl);
        r.setIsSystem(isSystem);
        return r;
    }

    private static CacheItemDef def(String itemKey, String cacheKey, String keyPattern,
                                    boolean enabled, Long ttl, String dataLoader, String loaderParam, boolean builtin) {
        return new CacheItemDef(itemKey, cacheKey, keyPattern, enabled, ttl, null, dataLoader, loaderParam, builtin);
    }

    private void injectLoaders(Map<String, CacheDataLoader> loaders) {
        ReflectionTestUtils.setField(service, "cacheDataLoaders", loaders);
    }

    // ---------- update ----------

    @Test
    void updateDisablesExactItemAndEvicts() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(row("matrix-effective", "ccr:cfg:matrix:effective", null, "Y", null, "Y"));
        when(cacheConfigMapper.selectList(any())).thenReturn(List.of());
        CacheConfigUpdateRequest req = new CacheConfigUpdateRequest();
        req.setEnabled(false);

        service.update("matrix-effective", req);

        verify(cacheConfigMapper).updateById(any(CcrCacheItemConfig.class));
        verify(cacheConfigHolder).replaceAll(any());
        verify(cacheUtil).delete("ccr:cfg:matrix:effective");
        verify(cacheUtil).increment(CcrCacheUtil.GLOBAL_VER_KEY);
    }

    @Test
    void updatePrefixItemTtlEvictsByPrefix() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(row("rate-limit", null, "ccr:cfg:rate-limit:", "Y", null, "Y"));
        when(cacheConfigMapper.selectList(any())).thenReturn(List.of());
        CacheConfigUpdateRequest req = new CacheConfigUpdateRequest();
        req.setTtlSeconds(600L);

        service.update("rate-limit", req);

        verify(cacheUtil).deleteByPrefix("ccr:cfg:rate-limit:");
    }

    @Test
    void updateSystemItemKeyChangeThrows400() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(row("matrix-effective", "ccr:cfg:matrix:effective", null, "Y", null, "Y"));
        CacheConfigUpdateRequest req = new CacheConfigUpdateRequest();
        req.setCacheKey("ccr:cfg:matrix:new");

        assertThrows(ServiceException.class, () -> service.update("matrix-effective", req));
        verify(cacheConfigMapper, never()).updateById(any(CcrCacheItemConfig.class));
    }

    @Test
    void updateSystemItemEnabledAllowed() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(row("matrix-effective", "ccr:cfg:matrix:effective", null, "Y", null, "Y"));
        when(cacheConfigMapper.selectList(any())).thenReturn(List.of());
        CacheConfigUpdateRequest req = new CacheConfigUpdateRequest();
        req.setEnabled(true); // 内置项只改开关,不触发 key 改动校验

        service.update("matrix-effective", req);

        verify(cacheConfigMapper).updateById(any(CcrCacheItemConfig.class));
    }

    @Test
    void updateUnknownItemThrows404() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.update("unknown", new CacheConfigUpdateRequest()));
    }

    @Test
    void updateNoFieldThrows400() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(row("matrix-effective", "ccr:cfg:matrix:effective", null, "Y", null, "Y"));
        assertThrows(ServiceException.class, () -> service.update("matrix-effective", new CacheConfigUpdateRequest()));
    }

    @Test
    void updateNonPositiveTtlThrows400() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(row("matrix-effective", "ccr:cfg:matrix:effective", null, "Y", null, "Y"));
        CacheConfigUpdateRequest req = new CacheConfigUpdateRequest();
        req.setTtlSeconds(0L);
        assertThrows(ServiceException.class, () -> service.update("matrix-effective", req));
    }

    @Test
    void updateUnknownLoaderThrows400() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(row("custom", "ccr:cfg:custom", null, "Y", null, "N"));
        injectLoaders(Map.of("dw", dwLoader));
        when(dwLoader.code()).thenReturn("DW_TABLE");
        CacheConfigUpdateRequest req = new CacheConfigUpdateRequest();
        req.setDataLoader("UNKNOWN");
        assertThrows(ServiceException.class, () -> service.update("custom", req));
    }

    // ---------- create ----------

    @Test
    void createValidExactItemWithLoader() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(null);
        injectLoaders(Map.of("dw", dwLoader));
        when(dwLoader.code()).thenReturn("DW_TABLE");
        CacheConfigCreateRequest req = new CacheConfigCreateRequest();
        req.setItemKey("dw-table-test");
        req.setCacheKey("ccr:cfg:dw:test");
        req.setTtlSeconds(600L);
        req.setDataLoader("DW_TABLE");
        req.setLoaderParam("{\"table\":\"dw_x\"}");

        service.create(req);

        verify(cacheConfigMapper).insert(any(CcrCacheItemConfig.class));
        verify(cacheConfigHolder).replaceAll(any());
    }

    @Test
    void createRequiresKeyOrPattern() {
        CacheConfigCreateRequest req = new CacheConfigCreateRequest();
        req.setItemKey("x");
        assertThrows(ServiceException.class, () -> service.create(req));
    }

    @Test
    void createDuplicateItemThrows400() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(row("custom", "ccr:cfg:custom", null, "Y", null, "N"));
        CacheConfigCreateRequest req = new CacheConfigCreateRequest();
        req.setItemKey("custom");
        req.setCacheKey("ccr:cfg:custom");
        assertThrows(ServiceException.class, () -> service.create(req));
    }

    @Test
    void createPrefixWithLoaderThrows400() {
        CacheConfigCreateRequest req = new CacheConfigCreateRequest();
        req.setItemKey("x");
        req.setKeyPattern("ccr:cfg:x:");
        req.setDataLoader("DW_TABLE");
        assertThrows(ServiceException.class, () -> service.create(req));
    }

    @Test
    void createUnknownLoaderThrows400() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(null);
        injectLoaders(Map.of("dw", dwLoader));
        when(dwLoader.code()).thenReturn("DW_TABLE");
        CacheConfigCreateRequest req = new CacheConfigCreateRequest();
        req.setItemKey("x");
        req.setCacheKey("ccr:cfg:x");
        req.setDataLoader("UNKNOWN");
        assertThrows(ServiceException.class, () -> service.create(req));
    }

    // ---------- delete ----------

    @Test
    void deleteSystemItemThrows400() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(row("lpr-effective", "ccr:cfg:lpr:effective", null, "Y", null, "Y"));
        assertThrows(ServiceException.class, () -> service.delete("lpr-effective"));
    }

    @Test
    void deleteCustomItemPhysicalDeletesAndEvicts() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(row("custom", "ccr:cfg:custom", null, "Y", null, "N"));
        when(cacheConfigMapper.selectList(any())).thenReturn(List.of());

        service.delete("custom");

        verify(cacheConfigMapper).physicalDeleteByItemKey("custom");
        verify(cacheUtil).delete("ccr:cfg:custom");
        verify(cacheUtil).increment(CcrCacheUtil.GLOBAL_VER_KEY);
    }

    // ---------- refreshData ----------

    @Test
    void refreshDataNoLoaderThrows400() {
        when(cacheConfigHolder.getDef("x")).thenReturn(def("x", "ccr:cfg:x", null, true, null, null, null, false));
        assertThrows(ServiceException.class, () -> service.refreshData("x"));
    }

    @Test
    void refreshDataPrefixItemThrows400() {
        when(cacheConfigHolder.getDef("rate")).thenReturn(def("rate", null, "ccr:cfg:rate:", true, null, "DW_TABLE", null, false));
        assertThrows(ServiceException.class, () -> service.refreshData("rate"));
    }

    @Test
    void refreshDataUnknownLoaderThrows400() {
        when(cacheConfigHolder.getDef("x")).thenReturn(def("x", "ccr:cfg:x", null, true, null, "UNKNOWN", null, false));
        injectLoaders(Map.of("dw", dwLoader));
        when(dwLoader.code()).thenReturn("DW_TABLE");
        assertThrows(ServiceException.class, () -> service.refreshData("x"));
    }

    @Test
    void refreshDataWritesToCache() {
        when(cacheConfigHolder.getDef("dw-table"))
                .thenReturn(def("dw-table", "ccr:cfg:dw:test", null, true, 600L, "DW_TABLE", "{\"table\":\"dw_x\"}", false));
        injectLoaders(Map.of("dw", dwLoader));
        when(dwLoader.code()).thenReturn("DW_TABLE");
        List<Map<String, Object>> data = List.of(Map.of("a", 1), Map.of("b", 2));
        when(dwLoader.load("{\"table\":\"dw_x\"}")).thenReturn(data);

        int count = service.refreshData("dw-table");

        assertEquals(2, count);
        verify(cacheUtil).set("ccr:cfg:dw:test", data, 600L);
    }

    // ---------- refresh 映射 ----------

    @Test
    void refreshMapsRowsToHolder() {
        CcrCacheItemConfig lpr = row("lpr-effective", "ccr:cfg:lpr:effective", null, "Y", 600L, "Y");
        lpr.setDescription("LPR 当前生效版本");
        CcrCacheItemConfig matrix = row("matrix-effective", "ccr:cfg:matrix:effective", null, "N", null, "Y");
        when(cacheConfigMapper.selectList(any())).thenReturn(List.of(lpr, matrix));

        service.refresh();

        verify(cacheConfigHolder).replaceAll(Map.of(
                "lpr-effective", new CacheItemDef("lpr-effective", "ccr:cfg:lpr:effective", null,
                        true, 600L, "LPR 当前生效版本", null, null, true),
                "matrix-effective", new CacheItemDef("matrix-effective", "ccr:cfg:matrix:effective", null,
                        false, null, null, null, null, true)));
    }
}
