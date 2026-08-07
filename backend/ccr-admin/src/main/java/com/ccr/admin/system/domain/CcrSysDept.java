package com.ccr.admin.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机构表(数据权限控制基础)
 */
@Data
@TableName("ccr_sys_dept")
public class CcrSysDept {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    /** 机构编码 */
    private String deptCode;

    /** 机构名称 */
    private String deptName;

    /** 父机构id */
    private Long parentId;

    /** HEAD总行/BRANCH分行/DEPT部门/SUB_BRANCH支行 */
    private String orgType;

    /** 负责人 */
    private String manager;

    private String status;

    private Integer sortNo;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String delFlag;
}
