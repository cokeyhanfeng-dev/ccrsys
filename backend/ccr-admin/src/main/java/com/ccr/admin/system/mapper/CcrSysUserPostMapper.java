package com.ccr.admin.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.admin.system.domain.CcrSysUserPost;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-机构-岗位绑定 Mapper
 */
@Mapper
public interface CcrSysUserPostMapper extends BaseMapper<CcrSysUserPost> {
}
