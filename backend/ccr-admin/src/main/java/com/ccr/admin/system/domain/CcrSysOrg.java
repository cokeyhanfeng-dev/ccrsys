package com.ccr.admin.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机构档案(§10.3.25 sys_org):资质字段权威来源,org_code 与 ccr_sys_dept 唯一对齐。
 * 营业执照/金融许可证/社会信用代码由机构管理页手工维护;数仓不产资质。
 */
@Data
@TableName("sys_org")
public class CcrSysOrg {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    /** 机构编号(对齐 sys_dept.org_code 层级前缀,唯一禁改) */
    private String orgCode;

    /** 机构全称(中文) */
    private String orgName;

    /** 机构简称 */
    private String shortName;

    /** 上级机构编号(总行为空或0) */
    private String parentOrgCode;

    /** 上级机构名称(冗余) */
    private String parentOrgName;

    /** 机构层级代码(1总行/2分行/3支行/4网点,对齐 dw_org_dim.org_level) */
    private String orgLevelCode;

    /** 营业执照号码 */
    private String businessLicenseNo;

    /** 金融许可证 */
    private String financialLicenseNo;

    /** 社会信用代码(18位,非空唯一) */
    private String creditCode;

    /** 机构类型(HEAD/DEPT/BRANCH/NETWORK,对齐 sys_dept.org_type) */
    private String orgType;

    /** 所属支行编码(对齐 dw_org_dim.branch_code) */
    private String branchCode;

    /** 启用0/停用1(停用前置校验同 sys_dept) */
    private String status;

    private String remark;

    private String delFlag;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;
}
