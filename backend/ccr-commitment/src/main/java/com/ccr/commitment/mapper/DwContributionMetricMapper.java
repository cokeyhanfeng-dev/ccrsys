package com.ccr.commitment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.commitment.domain.DwContributionMetric;
import org.apache.ibatis.annotations.Mapper;

/**
 * DwContributionMetric Mapper(数仓只读)
 */
@Mapper
public interface DwContributionMetricMapper extends BaseMapper<DwContributionMetric> {
}
