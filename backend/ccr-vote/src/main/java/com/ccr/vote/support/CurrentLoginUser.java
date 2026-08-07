package com.ccr.vote.support;

import cn.dev33.satoken.stp.StpUtil;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.vote.read.SysUserRead;
import com.ccr.vote.read.SysUserReadMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 当前登录人(Sa-Token)与节点角色校验
 * 操作人身份一律取 StpUtil.getLoginIdAsLong(),不接受请求传参,防身份伪造
 * 角色编码以 ccr_sys_user/ccr_sys_role 种子为准(08_system.sql)
 */
@Component
public class CurrentLoginUser {

    public static final String ROLE_CUSTOMER_MANAGER = "customer_manager";
    public static final String ROLE_BRANCH_MANAGER = "branch_manager";
    public static final String ROLE_DEPT_GM = "dept_gm";
    public static final String ROLE_VICE_PRESIDENT = "vice_president";
    public static final String ROLE_COMMITTEE = "committee_member";
    public static final String ROLE_PRESIDENT = "president";
    public static final String ROLE_ADMIN = "admin";

    /** 审批节点 → 系统角色编码 */
    public static final Map<String, String> NODE_ROLE = Map.of(
            "BRANCH_MANAGER", ROLE_BRANCH_MANAGER,
            "DEPT_GENERAL_MANAGER", ROLE_DEPT_GM,
            "VICE_PRESIDENT", ROLE_VICE_PRESIDENT,
            "SIX_PEOPLE_GROUP", ROLE_COMMITTEE,
            "PRESIDENT", ROLE_PRESIDENT);

    @Resource
    private SysUserReadMapper sysUserReadMapper;

    /** 当前登录人id(未登录由 Sa-Token 抛 NotLoginException) */
    public Long requireLoginId() {
        return StpUtil.getLoginIdAsLong();
    }

    /** 当前登录用户(不存在视为未授权) */
    public SysUserRead requireCurrentUser() {
        SysUserRead user = sysUserReadMapper.selectById(requireLoginId());
        if (user == null) {
            throw new ServiceException(ErrorCode.UNAUTHORIZED.getCode(), "登录用户不存在");
        }
        return user;
    }

    public String currentRoleCode() {
        return requireCurrentUser().getRoleCode();
    }

    /** 校验登录人具备节点对应角色,不符抛 NODE_PERMISSION */
    public void requireNodeRole(String nodeCode) {
        String required = NODE_ROLE.get(nodeCode);
        if (required == null) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "未知审批节点:" + nodeCode);
        }
        if (!required.equals(currentRoleCode())) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                    "当前登录人不具备节点[" + nodeCode + "]的审批角色");
        }
    }

    /** 校验登录人角色在允许集合内,不符抛 FORBIDDEN */
    public void requireAnyRole(String... roles) {
        String role = currentRoleCode();
        for (String r : roles) {
            if (r.equals(role)) {
                return;
            }
        }
        throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "当前角色无权访问");
    }

    /** 角色 → 审批节点(待办过滤用);无映射(如客户经理/管理员)返回 null */
    public String nodeOfRole(String roleCode) {
        for (Map.Entry<String, String> e : NODE_ROLE.entrySet()) {
            if (e.getValue().equals(roleCode)) {
                return e.getKey();
            }
        }
        return null;
    }
}
