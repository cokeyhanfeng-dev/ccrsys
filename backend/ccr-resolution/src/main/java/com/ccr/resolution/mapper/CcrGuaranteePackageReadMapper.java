package com.ccr.resolution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.application.domain.CcrGuaranteePackage;
import org.apache.ibatis.annotations.Mapper;

/**
 * CcrGuaranteePackage 只读 Mapper(跨模块只读视图:回填校验取担保主类型,禁止写操作)
 */
@Mapper
public interface CcrGuaranteePackageReadMapper extends BaseMapper<CcrGuaranteePackage> {
}
