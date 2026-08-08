package com.ccr.common.cache.dto;

import lombok.Data;

/** 数据加载器信息(管理端下拉) */
@Data
public class LoaderInfo {

    /** 加载器编码(缓存项 data_loader 字段值) */
    private String code;

    /** 展示名 */
    private String name;

    public LoaderInfo() {
    }

    public LoaderInfo(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
