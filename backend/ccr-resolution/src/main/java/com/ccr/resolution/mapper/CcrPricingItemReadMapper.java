package com.ccr.resolution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.application.domain.CcrPricingItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * CcrPricingItem 只读 Mapper(跨模块只读视图:回填校验读 ccr_pricing_item,禁止写操作)
 */
@Mapper
public interface CcrPricingItemReadMapper extends BaseMapper<CcrPricingItem> {
}
