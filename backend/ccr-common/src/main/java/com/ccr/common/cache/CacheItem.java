package com.ccr.common.cache;

import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;

/**
 * 缓存项静态定义(详设 §3.6)。code 用于 application.yml {@code ccr.cache.items} 键
 * 与 ccr_cache_config.item_key;key 精确匹配,keyPattern 前缀匹配(动态 key,如 ccr:cfg:rate-limit:LOAN:xxx)。
 */
public enum CacheItem {

    /** LPR 当前生效版本(精确 key) */
    LPR_EFFECTIVE("lpr-effective", "ccr:cfg:lpr:effective", null),

    /** 利率矩阵全量生效行(精确 key) */
    MATRIX_EFFECTIVE("matrix-effective", "ccr:cfg:matrix:effective", null),

    /** 产品硬边界(前缀匹配动态 key:ccr:cfg:rate-limit:{业务类}:{产品}) */
    RATE_LIMIT("rate-limit", null, "ccr:cfg:rate-limit:");

    private final String code;
    private final String key;
    private final String keyPattern;

    CacheItem(String code, String key, String keyPattern) {
        this.code = code;
        this.key = key;
        this.keyPattern = keyPattern;
    }

    public String getCode() {
        return code;
    }

    public String getKey() {
        return key;
    }

    public String getKeyPattern() {
        return keyPattern;
    }

    /** 精确匹配优先,再前缀匹配;未命中返回 null */
    public static CacheItem match(String cacheKey) {
        for (CacheItem i : values()) {
            if (i.key != null && i.key.equals(cacheKey)) {
                return i;
            }
        }
        for (CacheItem i : values()) {
            if (i.keyPattern != null && cacheKey.startsWith(i.keyPattern)) {
                return i;
            }
        }
        return null;
    }

    /** 按配置编码解析;未知编码抛 400 */
    public static CacheItem fromCode(String code) {
        for (CacheItem i : values()) {
            if (i.code.equals(code)) {
                return i;
            }
        }
        throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "未知缓存项:" + code);
    }
}
