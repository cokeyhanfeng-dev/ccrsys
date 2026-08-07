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

    /** 机构编码(历史字母编码,展示/外部对接用) */
    private String deptCode;

    /** 机构编码(层级前缀数字码,唯一,禁改):1000总行/1001xx部门/1002xx支行/支行码+两位为网点 */
    private String orgCode;

    /** 祖先链(机构id逗号分隔),如 0,1000,1002 */
    private String ancestors;

    /** 支行编码:BRANCH=自身orgCode;NETWORK=所属支行orgCode;DEPT/HEAD为空 */
    private String branchCode;

    /** 机构名称 */
    private String deptName;

    /** 父机构id */
    private Long parentId;

    /** HEAD总行/DEPT部门/BRANCH支行/NETWORK网点/GROUP集团管理机构 */
    private String orgType;

    /** 负责人 */
    private String manager;

    private String status;

    private Integer sortNo;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String delFlag;
}
