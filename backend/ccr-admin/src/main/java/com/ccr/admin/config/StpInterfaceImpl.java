package com.ccr.admin.config;

import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限数据源(§4.1):角色来源于 ccr_sys_user.role_code
 * 角色编码以 seeds 为准:customer_manager/branch_manager/committee_member/president/admin
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    @Resource
    private CcrSysUserMapper userMapper;

    /** 权限码(当前鉴权按角色,不用权限码,返回空) */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }

    /** 角色列表:用户单角色,直接返回其 role_code */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        CcrSysUser user = userMapper.selectOne(new LambdaQueryWrapper<CcrSysUser>()
                .eq(CcrSysUser::getId, Long.valueOf(loginId.toString()))
                .eq(CcrSysUser::getDelFlag, "0"));
        if (user == null || user.getRoleCode() == null) {
            return List.of();
        }
        return List.of(user.getRoleCode());
    }
}
