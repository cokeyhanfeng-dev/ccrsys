package com.ccr.common.cache.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.common.cache.domain.CcrCacheItemConfig;
import org.apache.ibatis.annotations.Mapper;

/** 缓存项配置表 Mapper(被 CcrApplication @MapperScan("com.ccr.**.mapper") 覆盖) */
@Mapper
public interface CcrCacheItemConfigMapper extends BaseMapper<CcrCacheItemConfig> {
}
