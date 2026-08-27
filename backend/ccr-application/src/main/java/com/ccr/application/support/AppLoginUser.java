package com.ccr.application.support;

import cn.dev33.satoken.stp.StpUtil;
import com.ccr.application.mapper.AppSysUserReadMapper;
import com.ccr.application.read.SysUserRead;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 当前登录人(Sa-Token)数据权限(§5.4)
 * 列表/查询的申请人、机构过滤一律取服务端登录人,不接受前端传参,防越权
 */
@Component
public class AppLoginUser {

    /** 客户经理:仅本人申请 */
    public static final String ROLE_CUSTOMER_MANAGER = "customer_manager";
    /** 支行行长/部门总经理/副行长:按本人机构过滤 */
    public static final String ROLE_BRANCH_MANAGER = "branch_manager";
    public static final String ROLE_DEPT_GM = "dept_gm";
    public static final String ROLE_VICE_PRESIDENT = "vice_president";
    public static final String ROLE_COMMITTEE_MEMBER = "committee_member";
    /** 贷审会秘书岗(由计划财务部总经理兼任,主角色 dept_gm) */
    public static final String ROLE_SECRETARY = "secretary";
    public static final String ROLE_CONTRACT_OPERATOR = "contract_operator";
    /** 行长/审计/管理员:全量 */
    public static final String ROLE_PRESIDENT = "president";
    public static final String ROLE_AUDITOR = "auditor";
    public static final String ROLE_ADMIN = "admin";

    @Resource
    private AppSysUserReadMapper appSysUserReadMapper;

    /** 当前登录人id(未登录由 Sa-Token 抛 NotLoginException) */
    public Long requireLoginId() {
        return StpUtil.getLoginIdAsLong();
    }

    /** 当前登录用户(不存在视为未授权) */
    public SysUserRead requireCurrentUser() {
        SysUserRead user = appSysUserReadMapper.selectById(requireLoginId());
        if (user == null) {
            throw new ServiceException(ErrorCode.UNAUTHORIZED.getCode(), "登录用户不存在");
        }
        return user;
    }
}
