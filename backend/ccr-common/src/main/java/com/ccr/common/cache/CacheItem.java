package com.ccr.common.cache;

import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;

/**
 * 缓存项内置种子元数据(详设 §3.6 v2):仅用于启动 seedIfEmpty 生成内置 3 项定义,
 * 以及文档/管理端内置项展示。运行期匹配已由 {@link CacheConfigHolder#matchDef} 按 DB 动态定义驱动。
 */
public enum CacheItem {

    /** LPR 当前生效版本(精确 key) */
    LPR_EFFECTIVE("lpr-effective", "ccr:cfg:lpr:effective", null, "LPR 当前生效版本"),

    /** 利率矩阵全量生效行(精确 key) */
    MATRIX_EFFECTIVE("matrix-effective", "ccr:cfg:matrix:effective", null, "利率矩阵生效行"),

    /** 产品硬边界(前缀匹配动态 key:ccr:cfg:rate-limit:{业务类}:{产品}) */
    RATE_LIMIT("rate-limit", null, "ccr:cfg:rate-limit:", "产品硬边界限流");

    private final String code;
    private final String cacheKey;
    private final String keyPattern;
    private final String desc;

    CacheItem(String code, String cacheKey, String keyPattern, String desc) {
        this.code = code;
        this.cacheKey = cacheKey;
        this.keyPattern = keyPattern;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public String getKeyPattern() {
        return keyPattern;
    }

    /** 内置项展示描述(seed 的 description 列) */
    public String getDesc() {
        return desc;
    }

    /**
     * @deprecated 运行期匹配已改由 {@link CacheConfigHolder#matchDef} 按 DB 动态定义驱动,不再依赖静态枚举
     */
    @Deprecated
    public static CacheItem match(String cacheKey) {
        for (CacheItem i : values()) {
            if (i.cacheKey != null && i.cacheKey.equals(cacheKey)) {
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

    /**
     * @deprecated 管理端校验已改为按 DB 定义存在性判断,不再依赖静态枚举
     */
    @Deprecated
    public static CacheItem fromCode(String code) {
        for (CacheItem i : values()) {
            if (i.code.equals(code)) {
                return i;
            }
        }
        throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "未知缓存项:" + code);
    }
}
