package com.ccr.application.read;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 系统用户只读视图(ccr_sys_user)——主数据归 admin 模块维护
 * 跨模块只读:本模块仅查询登录人角色/机构做数据权限过滤,禁止写操作
 */
@Data
@TableName("ccr_sys_user")
public class SysUserRead {

    @TableId
    private Long id;

    /** 登录名 */
    private String username;

    /** 姓名 */
    private String nickName;

    /** 角色:customer_manager/branch_manager/dept_gm/vice_president/committee_member/president/auditor/admin */
    private String roleCode;

    /** 归属机构 */
    private Long orgId;

    /** ENABLE/DISABLE */
    private String status;

    @TableLogic
    private String delFlag;
}
