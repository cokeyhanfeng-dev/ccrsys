package com.ccr.common.cache;

/**
 * 缓存项数据加载器(§3.6 v2 配置化刷新):缓存项配置 data_loader 后,由 {@link CacheConfigService}
 * 手动/定时触发把加载器返回的数据写入 Redis。实现类置于业务模块(如 ccr-application 的数仓表加载器),
 * 被 ccr-admin 启动扫描注入 {@code Map<String, CacheDataLoader>}(按 bean 名,查找需按 {@link #code()} 匹配)。
 */
public interface CacheDataLoader {

    /** 加载器唯一编码(如 DW_TABLE),缓存项 data_loader 字段引用 */
    String code();

    /** 展示名(管理端下拉) */
    String name();

    /** 加载数据;loaderParam 为加载器自定义参数(JSON 字符串);返回值写入 Redis 缓存 */
    Object load(String loaderParam);
}
