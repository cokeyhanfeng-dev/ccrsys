package com.ccr.common.cache.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.common.cache.domain.CcrCacheItemConfig;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/** 缓存项配置表 Mapper(被 CcrApplication @MapperScan("com.ccr.**.mapper") 覆盖) */
@Mapper
public interface CcrCacheItemConfigMapper extends BaseMapper<CcrCacheItemConfig> {

    /**
     * 物理删除缓存项(按 item_key):规避逻辑删除 + uk_cache_item 唯一键冲突
     * (逻辑删除后同 item_key 重建报 DuplicateKey,无法恢复)。
     */
    @Delete("DELETE FROM ccr_cache_config WHERE item_key = #{itemKey}")
    int physicalDeleteByItemKey(String itemKey);
}
