package com.ccr.vote.read;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统用户只读 Mapper(ccr_sys_user,主数据在 admin 模块)——仅查询,禁止写
 */
@Mapper
public interface SysUserReadMapper extends BaseMapper<SysUserRead> {
}
