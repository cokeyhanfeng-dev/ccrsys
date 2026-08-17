package com.ccr.admin.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户(基础系统功能)
 */
@Data
@TableName("ccr_sys_user")
public class CcrSysUser {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    /** 登录名 */
    private String username;

    /** 密码(BCrypt加密) */
    private String password;

    /** 姓名 */
    private String nickName;

    /** 角色:customer_manager/branch_manager/committee_member/president/admin */
    private String roleCode;

    /** 归属机构 */
    private Long orgId;

    private String phone;

    private String email;

    /** ENABLE/DISABLE */
    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String delFlag;

    /** 是否需强制改密:1需改密/0已改 */
    private String pwdChangeFlag;
}
