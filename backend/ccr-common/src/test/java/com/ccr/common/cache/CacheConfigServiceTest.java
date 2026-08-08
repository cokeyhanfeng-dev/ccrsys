package com.ccr.common.cache;

import com.ccr.common.cache.domain.CcrCacheItemConfig;
import com.ccr.common.cache.mapper.CcrCacheItemConfigMapper;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CacheConfigService:update 写库+刷新+失效链路、400 校验、refresh 映射(§3.6) */
@ExtendWith(MockitoExtension.class)
class CacheConfigServiceTest {

    @Mock
    private CcrCacheItemConfigMapper cacheConfigMapper;
    @Mock
    private CacheConfigHolder cacheConfigHolder;
    @Mock
    private CcrCacheUtil cacheUtil;

    @InjectMocks
    private CacheConfigService service;

    @Test
    void updateExactItemDisablesAndEvicts() {
        CcrCacheItemConfig existing = new CcrCacheItemConfig();
        existing.setItemKey("matrix-effective");
        existing.setEnabled("Y");
        when(cacheConfigMapper.selectOne(any())).thenReturn(existing);
        when(cacheConfigMapper.selectList(any())).thenReturn(List.of());

        service.update("matrix-effective", false, null);

        verify(cacheConfigMapper).updateById(existing);
        verify(cacheConfigHolder).replaceAll(any());
        verify(cacheUtil).delete("ccr:cfg:matrix:effective");
        verify(cacheUtil).increment(CcrCacheUtil.GLOBAL_VER_KEY);
    }

    @Test
    void updatePrefixItemTtlEvictsByPrefix() {
        when(cacheConfigMapper.selectOne(any())).thenReturn(null);
        when(cacheConfigMapper.selectList(any())).thenReturn(List.of());

        service.update("rate-limit", null, 600L);

        verify(cacheConfigMapper).insert(any(CcrCacheItemConfig.class));
        verify(cacheUtil).deleteByPrefix("ccr:cfg:rate-limit:");
    }

    @Test
    void updateUnknownItemThrows400() {
        assertThrows(ServiceException.class, () -> service.update("unknown", true, null));
        verify(cacheConfigMapper, never()).insert(any(CcrCacheItemConfig.class));
    }

    @Test
    void updateNoFieldThrows400() {
        assertThrows(ServiceException.class, () -> service.update("matrix-effective", null, null));
    }

    @Test
    void updateNonPositiveTtlThrows400() {
        assertThrows(ServiceException.class, () -> service.update("matrix-effective", null, 0L));
    }

    @Test
    void refreshMapsRowsToHolder() {
        CcrCacheItemConfig lpr = new CcrCacheItemConfig();
        lpr.setItemKey("lpr-effective");
        lpr.setEnabled("Y");
        lpr.setTtlSeconds(600L);
        CcrCacheItemConfig matrix = new CcrCacheItemConfig();
        matrix.setItemKey("matrix-effective");
        matrix.setEnabled("N");
        when(cacheConfigMapper.selectList(any())).thenReturn(List.of(lpr, matrix));

        service.refresh();

        verify(cacheConfigHolder).replaceAll(Map.of(
                "lpr-effective", new CacheItemOverride(true, 600L),
                "matrix-effective", new CacheItemOverride(false, null)));
    }

    @Test
    void updateEnabledOnlyKeepsTtlNull() {
        CcrCacheItemConfig existing = new CcrCacheItemConfig();
        existing.setItemKey("lpr-effective");
        existing.setEnabled("Y");
        when(cacheConfigMapper.selectOne(any())).thenReturn(existing);
        when(cacheConfigMapper.selectList(any())).thenReturn(List.of());

        service.update("lpr-effective", true, null);

        verify(cacheConfigMapper).updateById(existing);
        verify(cacheUtil).delete(eq("ccr:cfg:lpr:effective"));
    }
}
