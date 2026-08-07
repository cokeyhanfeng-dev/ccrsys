package com.ccr.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.application.domain.CcrRelation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 关联人绑定 Mapper(§10.3.21 ccr_relation)
 */
@Mapper
public interface CcrRelationMapper extends BaseMapper<CcrRelation> {
}
