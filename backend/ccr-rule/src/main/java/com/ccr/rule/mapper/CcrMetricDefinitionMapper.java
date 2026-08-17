package com.ccr.rule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.rule.domain.CcrMetricDefinition;
import org.apache.ibatis.annotations.Mapper;

/**
 * CcrMetricDefinition 贡献度指标定义 Mapper(§9,指标字典只读)
 */
@Mapper
public interface CcrMetricDefinitionMapper extends BaseMapper<CcrMetricDefinition> {
}
