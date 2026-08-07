package com.ccr.admin.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户-机构-岗位绑定(详设 §5.1/§10.3.20 sys_user_post)
 * 一个用户可绑定多个机构/岗位组合,默认机构/岗位唯一
 */
@Data
@TableName("ccr_sys_user_post")
public class CcrSysUserPost {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    /** 用户id */
    private Long userId;

    /** 机构id(ccr_sys_dept.id) */
    private Long orgId;

    /** 岗位编码(与角色码对齐:customer_manager/branch_manager/...) */
    private String postCode;

    /** 是否默认机构/岗位:1是0否(每用户仅一条默认) */
    private String isDefault;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String delFlag;
}
