package com.ccr.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import com.ccr.common.core.domain.R;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录认证(真实用户表 ccr_sys_user 校验;SSO 统一认证预留适配)
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private CcrSysUserMapper sysUserMapper;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");
        CcrSysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<CcrSysUser>()
                .eq(CcrSysUser::getUsername, username)
                .eq(CcrSysUser::getDelFlag, "0"));
        // BCrypt 校验密码(不落明文)
        if (user == null || user.getPassword() == null
                || !BCrypt.checkpw(password, user.getPassword())) {
            throw new ServiceException(401, "用户名或密码错误");
        }
        if (!"ENABLE".equals(user.getStatus())) {
            throw new ServiceException(401, "用户已停用");
        }
        StpUtil.login(user.getId());
        // 写入当前用户机构上下文(公共字段自动填充用)
        StpUtil.getSession().set("orgId", user.getOrgId());
        String token = StpUtil.getTokenValue();

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("userId", user.getId());
        userInfo.put("userName", user.getUsername());
        userInfo.put("nickName", user.getNickName());
        userInfo.put("roles", new String[]{user.getRoleCode()});
        userInfo.put("orgId", user.getOrgId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("userInfo", userInfo);
        return R.ok(result);
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        StpUtil.logout();
        return R.ok();
    }
}
