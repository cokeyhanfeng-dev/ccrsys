package com.ccr.admin.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.admin.system.domain.CcrSysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统用户 Mapper
 */
@Mapper
public interface CcrSysUserMapper extends BaseMapper<CcrSysUser> {
}
