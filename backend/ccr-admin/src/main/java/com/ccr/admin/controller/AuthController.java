package com.ccr.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.admin.system.domain.CcrSysDept;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysDeptMapper;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import com.ccr.admin.system.support.DataScopeHelper;
import com.ccr.common.core.domain.R;
import com.ccr.common.datascope.DataScope;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录认证(真实用户表 ccr_sys_user 校验;SSO 统一认证预留适配)
 */
@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {

    @Resource
    private CcrSysUserMapper sysUserMapper;

    @Resource
    private CcrSysDeptMapper sysDeptMapper;

    @Resource
    private DataScopeHelper dataScopeHelper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");
        String ip = request.getRemoteAddr();
        CcrSysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<CcrSysUser>()
                .eq(CcrSysUser::getUsername, username)
                .eq(CcrSysUser::getDelFlag, "0"));
        // BCrypt 校验密码(不落明文)
        if (user == null || user.getPassword() == null
                || !BCrypt.checkpw(password, user.getPassword())) {
            writeLoginLog("LOGIN_FAIL", user == null ? 0L : user.getId(), username, ip, "用户名或密码错误");
            throw new ServiceException(401, "用户名或密码错误");
        }
        if (!"ENABLE".equals(user.getStatus())) {
            writeLoginLog("LOGIN_FAIL", user.getId(), username, ip, "用户已停用");
            throw new ServiceException(401, "用户已停用");
        }
        StpUtil.login(user.getId());
        // 写入当前用户机构上下文(公共字段自动填充用)与数据权限范围(§5.4)
        CcrSysDept dept = user.getOrgId() == null ? null : sysDeptMapper.selectById(user.getOrgId());
        String orgCode = dept == null ? null : dept.getOrgCode();
        DataScope dataScope = dataScopeHelper.compute(user);
        StpUtil.getSession().set("orgId", user.getOrgId());
        if (orgCode != null) {
            StpUtil.getSession().set("orgCode", orgCode);
        }
        StpUtil.getSession().set("dataScopeLevel", dataScope.getLevel());
        if (dataScope.getOrgCodePrefix() != null) {
            StpUtil.getSession().set("dataScopeOrgCodePrefix", dataScope.getOrgCodePrefix());
        }
        String token = StpUtil.getTokenValue();

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("userId", user.getId());
        userInfo.put("userName", user.getUsername());
        userInfo.put("nickName", user.getNickName());
        userInfo.put("roles", new String[]{user.getRoleCode()});
        userInfo.put("orgId", user.getOrgId());
        userInfo.put("orgCode", orgCode);
        userInfo.put("orgName", dept == null ? null : dept.getDeptName());
        userInfo.put("dataScope", dataScope.getLevel());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("userInfo", userInfo);
        writeLoginLog("LOGIN", user.getId(), user.getNickName(), ip, "登录成功");
        return R.ok(result);
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        Long uid = null;
        try {
            uid = StpUtil.getLoginIdAsLong();
        } catch (Exception ignored) {
            // 未登录时无留痕
        }
        if (uid != null) {
            CcrSysUser user = sysUserMapper.selectById(uid);
            writeLoginLog("LOGOUT", uid, user == null ? null : user.getNickName(), request.getRemoteAddr(), "退出登录");
        }
        StpUtil.logout();
        return R.ok();
    }

    /** 登录审计留痕(§15.2);表/连接异常仅记日志,不阻断登录 */
    private void writeLoginLog(String logType, Long userId, String nickName, String ip, String detail) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO ccr_audit_log
                            (id, log_type, biz_id, content, operator_id, operator_name, operate_time)
                            VALUES (?,?,?,?,?,?,?)
                            """,
                    IdUtil.getSnowflakeNextId(), logType, userId == null ? "0" : String.valueOf(userId),
                    "用户[" + (nickName == null ? "未知" : nickName) + "] " + detail + "  IP=" + ip,
                    userId == null ? 0L : userId, nickName, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("登录审计留痕写入失败(不影响登录): {}", e.getMessage());
        }
    }
}
