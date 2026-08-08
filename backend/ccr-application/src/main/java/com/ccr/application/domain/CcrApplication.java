package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.application.dto.CommitmentInput;
import com.ccr.application.dto.DepositItemInput;
import com.ccr.application.dto.MemberInput;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 利率申请主单(ccr_application)
 * 状态: DRAFT/SUBMITTING/PROCESSING/PARTIAL_APPROVED/APPROVED/REJECTED/CLOSED
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_application")
public class CcrApplication extends BaseEntity {

    /** 申请号(唯一) */
    private String applicationNo;

    /** LOAN贷款 / DEPOSIT存款 */
    private String businessType;

    /** INDIVIDUAL / CORPORATE_SINGLE / GROUP */
    private String customerScope;

    /** 个人或企业单户客户号(集团场景为空) */
    private String customerNo;

    /** 集团客户编号(集团场景必填) */
    private String groupNo;

    /** 客户经理 */
    private Long applicantUserId;

    /** 提交机构 */
    private Long applicantOrgId;

    /** 所属支行编码(数据权限 DEPT 级前缀过滤基础,§5.4;创建时按申请人机构从 sys_org 解析) */
    private String applyBranchCode;

    /** 关联重提的原申请 */
    private Long sourceApplicationId;

    /** 提交成功绑定不可变快照包 */
    private Long snapshotBundleId;

    /** 冻结利率规则集版本 */
    private Long ruleSetVersionId;

    /** 冻结 LPR 版本 */
    private Long lprVersionId;

    /** 冻结流程定义版本 */
    private String flowDefinitionVersion;

    /** 最终提交时间 */
    private LocalDateTime submitTime;

    /** 主申请终态时间 */
    private LocalDateTime finalTime;

    /** 客户经理备注(手工描述,展示在审批界面) */
    private String applicationRemark;

    /** 提交冻结的路由/LPR生效日期(§8.4,在途沿用) */
    private LocalDateTime routeAsOfDate;

    /** 草稿创建/上次预览时各数仓数据集数据日期基线(JSON:表名→data_dt,§7.1步骤9比对) */
    private String dataBaselineJson;

    /** 集团场景涉及成员(非表字段,仅接收,落 ccr_application_member;逐成员金额/币种/角色) */
    @TableField(exist = false)
    private List<MemberInput> members;

    /** 贷款担保切分录入(非表字段,仅接收,创建 ccr_pricing_item + 担保组合;
     *  键:requestedRate/amount/productCode/termValue/termUnit/currency/originalRate/
     *  memberCustomerNo/contractBusinessKey/plannedContractFlag/creditTrancheRef/
     *  guaranteeType/measures[measureType/guarantorCustomerNo/collateralNo/guaranteeAmount/currency]) */
    @TableField(exist = false)
    private List<Map<String, Object>> guarantees;

    /** 存款分项结构化录入(非表字段,仅接收;DEPOSIT 申请据此生成定价分项) */
    @TableField(exist = false)
    private List<DepositItemInput> depositItems;

    /** 拟达成贡献度承诺(非表字段,仅接收,落 ccr_application_commitment) */
    @TableField(exist = false)
    private List<CommitmentInput> commitments;
}
