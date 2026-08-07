package com.ccr.admin.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统角色(基础系统功能:权限管理)
 */
@Data
@TableName("ccr_sys_role")
public class CcrSysRole {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    /** 角色编码 */
    private String roleCode;

    /** 角色名称 */
    private String roleName;

    private String remark;

    /** 菜单权限id(逗号分隔) */
    private String menuIds;

    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String delFlag;
}
