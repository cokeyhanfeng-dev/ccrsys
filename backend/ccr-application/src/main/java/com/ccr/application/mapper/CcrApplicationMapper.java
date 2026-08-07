package com.ccr.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.application.domain.CcrApplication;
import org.apache.ibatis.annotations.Mapper;

/**
 * 申请主单 Mapper
 */
@Mapper
public interface CcrApplicationMapper extends BaseMapper<CcrApplication> {
}
