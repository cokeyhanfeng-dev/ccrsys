package com.ccr.resolution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.application.domain.CcrApplication;
import org.apache.ibatis.annotations.Mapper;

/**
 * CcrApplication 只读 Mapper(跨模块只读视图:核验异常通知定位申请人/客户经理,禁止写操作)
 */
@Mapper
public interface CcrApplicationReadMapper extends BaseMapper<CcrApplication> {
}
