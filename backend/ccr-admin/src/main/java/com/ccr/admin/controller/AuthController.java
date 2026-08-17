package com.ccr.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.admin.system.domain.CcrSysDept;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysDeptMapper;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.core.domain.R;
import com.ccr.common.core.util.PasswordUtil;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private JdbcTemplate jdbcTemplate;

    @Resource
    private NodeAssigneeResolver nodeAssigneeResolver;

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
        // 写入当前用户机构上下文(公共字段自动填充用)
        CcrSysDept dept = user.getOrgId() == null ? null : sysDeptMapper.selectById(user.getOrgId());
        String orgCode = dept == null ? null : dept.getOrgCode();
        String dataScope = dataScopeLevel(user.getRoleCode());
        StpUtil.getSession().set("orgId", user.getOrgId());
        // 是否需强制改密(兼容旧数据无字段:null 视为需改密)
        String pwdChangeFlag = user.getPwdChangeFlag() == null ? "1" : user.getPwdChangeFlag();
        StpUtil.getSession().set("pwdChangeFlag", pwdChangeFlag);
        String token = StpUtil.getTokenValue();

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("userId", user.getId());
        userInfo.put("userName", user.getUsername());
        userInfo.put("nickName", user.getNickName());
        // 角色数组:主角色 role_code;六人小组兼岗(§D-7 名单配置化)——主角色非委员但被配置为小组成员时附加委员角色
        List<String> roles = new ArrayList<>();
        roles.add(user.getRoleCode());
        if (!"committee_member".equals(user.getRoleCode())
                && nodeAssigneeResolver.isUserInAssignees("SIX_PEOPLE_GROUP", user.getId())) {
            roles.add("committee_member");
        }
        // 秘书岗由计划财务部总经理兼任(节点指派 DEPT 3202233931:dept_gm)——主角色非秘书但被解析为秘书处理人时附加秘书角色
        if (!"secretary".equals(user.getRoleCode())
                && nodeAssigneeResolver.isUserInAssignees("SECRETARY", user.getId())) {
            roles.add("secretary");
        }
        userInfo.put("roles", roles.toArray(new String[0]));
        userInfo.put("orgId", user.getOrgId());
        userInfo.put("orgCode", orgCode);
        userInfo.put("orgName", dept == null ? null : dept.getDeptName());
        userInfo.put("dataScope", dataScope);
        userInfo.put("pwdChangeFlag", pwdChangeFlag);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("userInfo", userInfo);
        writeLoginLog("LOGIN", user.getId(), user.getNickName(), ip, "登录成功");
        return R.ok(result);
    }

    /**
     * 修改密码(登录用户主动改密,首次登录强制改密入口)
     * 校验:原密码正确 → 新密码满足强规则 → 新旧不同;成功后 pwd_change_flag 置 0,解除强制改密
     * 旧密码错误返回 400(不用 401,避免前端 401 清 token 整页跳登录)
     */
    @PostMapping("/change-password")
    public R<Void> changePassword(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String oldPassword = body.getOrDefault("oldPassword", "");
        String newPassword = body.getOrDefault("newPassword", "");
        if (StrUtil.isBlank(oldPassword) || StrUtil.isBlank(newPassword)) {
            throw new ServiceException(400, "原密码与新密码必填");
        }
        Long uid = StpUtil.getLoginIdAsLong();
        CcrSysUser user = sysUserMapper.selectById(uid);
        if (user == null || !BCrypt.checkpw(oldPassword, user.getPassword())) {
            writeLoginLog("CHANGE_PASSWORD_FAIL", uid, null, request.getRemoteAddr(), "原密码错误");
            throw new ServiceException(400, "原密码错误");
        }
        if (!PasswordUtil.isStrong(newPassword)) {
            writeLoginLog("CHANGE_PASSWORD_FAIL", uid, user.getNickName(), request.getRemoteAddr(), "新密码不满足强规则");
            throw new ServiceException(400, "新密码不符合强度要求(不少于8位,须含大写字母/小写字母/特殊字符)");
        }
        if (oldPassword.equals(newPassword)) {
            writeLoginLog("CHANGE_PASSWORD_FAIL", uid, user.getNickName(), request.getRemoteAddr(), "新密码与旧密码相同");
            throw new ServiceException(400, "新密码不能与原密码相同");
        }
        user.setPassword(BCrypt.hashpw(newPassword, BCrypt.gensalt()));
        user.setPwdChangeFlag("0");
        user.setUpdateTime(LocalDateTime.now());
        sysUserMapper.updateById(user);
        StpUtil.getSession().set("pwdChangeFlag", "0");
        writeLoginLog("CHANGE_PASSWORD", uid, user.getNickName(), request.getRemoteAddr(), "修改密码成功");
        return R.ok();
    }

    /** 登录响应展示的数据范围级别；业务对象授权由各领域服务执行。 */
    private String dataScopeLevel(String roleCode) {
        return switch (roleCode == null ? "" : roleCode) {
            case "president", "auditor", "admin" -> "ALL";
            case "branch_manager", "dept_gm", "vice_president" -> "DEPT";
            default -> "SELF";
        };
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
