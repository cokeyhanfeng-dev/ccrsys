package com.ccr.application.cache;

import cn.hutool.core.util.StrUtil;
import com.ccr.common.cache.CacheDataLoader;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数仓表数据加载器(§3.6 v2 配置化刷新):把指定 dw_ 表 / caps_ 表的最新批次数据
 * ({@code data_dt=(SELECT MAX(data_dt))})加载为 {@code List<Map>} 供缓存项刷新写入 Redis。
 * <p>loaderParam JSON: {@code {"table":"dw_loan_contract_snapshot","limit":5000}}
 * (limit 缺省/超界用默认 5000,防整表超大写入)。表名白名单校验防 SQL 注入。</p>
 */
@Component
public class DwTableCacheLoader implements CacheDataLoader {

    /** 表名白名单:字母开头 + 字母数字下划线,且以数仓/外部落地表前缀开头 */
    private static final Pattern TABLE_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    /** 默认行数上限,防整表超大写入 Redis */
    private static final int DEFAULT_LIMIT = 5000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DwTableCacheLoader(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String code() {
        return "DW_TABLE";
    }

    @Override
    public String name() {
        return "数仓表最新批次";
    }

    @Override
    public Object load(String loaderParam) {
        String table;
        int limit = DEFAULT_LIMIT;
        if (StrUtil.isNotBlank(loaderParam)) {
            try {
                Map<String, Object> p = objectMapper.readValue(loaderParam, new TypeReference<Map<String, Object>>() {});
                table = p.get("table") == null ? null : String.valueOf(p.get("table"));
                Number l = p.get("limit") instanceof Number n ? n : null;
                if (l != null && l.longValue() > 0 && l.longValue() <= DEFAULT_LIMIT) {
                    limit = l.intValue();
                }
            } catch (JsonProcessingException e) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "加载器参数 JSON 解析失败:" + loaderParam);
            }
        } else {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "加载器参数必填({\"table\":\"dw_x\"})");
        }
        if (StrUtil.isBlank(table) || !TABLE_PATTERN.matcher(table).matches()
                || !(table.startsWith("dw_") || table.startsWith("caps_"))) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "非法数仓表名:" + table);
        }
        String sql = "SELECT * FROM " + table + " WHERE data_dt = (SELECT MAX(data_dt) FROM " + table + ") LIMIT " + limit;
        return jdbcTemplate.queryForList(sql);
    }
}
