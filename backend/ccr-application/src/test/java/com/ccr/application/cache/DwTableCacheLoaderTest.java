package com.ccr.application.cache;

import com.ccr.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DwTableCacheLoader 数仓表最新批次加载:表名白名单防注入、limit、SQL 模式(§3.6 v2 配置化刷新) */
@ExtendWith(MockitoExtension.class)
class DwTableCacheLoaderTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DwTableCacheLoader loader;

    @Test
    void codeAndName() {
        assertEquals("DW_TABLE", loader.code());
        assertEquals("数仓表最新批次", loader.name());
    }

    @Test
    void loadValidTableQueriesLatestBatch() {
        Map<String, Object> row = Map.of("data_dt", "2026-08-01", "etl_md5", "abc");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));

        Object result = loader.load("{\"table\":\"dw_loan_contract_snapshot\",\"limit\":10}");

        verify(jdbcTemplate).queryForList(
                "SELECT * FROM dw_loan_contract_snapshot WHERE data_dt = (SELECT MAX(data_dt) FROM dw_loan_contract_snapshot) LIMIT 10");
        assertEquals(1, ((List<?>) result).size());
    }

    @Test
    void loadDefaultLimitWhenAbsent() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        loader.load("{\"table\":\"dw_contribution_metric\"}");

        verify(jdbcTemplate).queryForList(
                "SELECT * FROM dw_contribution_metric WHERE data_dt = (SELECT MAX(data_dt) FROM dw_contribution_metric) LIMIT 5000");
    }

    @Test
    void loadRejectsSqlInjectionTable() {
        assertThrows(ServiceException.class, () ->
                loader.load("{\"table\":\"dw_x; DROP TABLE ccr_cache_config\"}"));
        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    void loadRejectsNonDwPrefixTable() {
        assertThrows(ServiceException.class, () -> loader.load("{\"table\":\"users\"}"));
        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    void loadRejectsBlankTable() {
        assertThrows(ServiceException.class, () -> loader.load("{\"limit\":10}"));
    }

    @Test
    void loadRejectsBadJson() {
        assertThrows(ServiceException.class, () -> loader.load("not-a-json"));
    }

    @Test
    void loadRejectsBlankParam() {
        assertThrows(ServiceException.class, () -> loader.load(null));
        assertThrows(ServiceException.class, () -> loader.load("  "));
    }
}
