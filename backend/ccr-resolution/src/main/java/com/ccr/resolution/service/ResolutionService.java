package com.ccr.resolution.service;

import com.ccr.resolution.domain.CcrResolution;
import com.ccr.resolution.domain.CcrResolutionExecution;
import com.ccr.resolution.dto.ContractBindDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 决议与执行核验服务(§7.7)
 */
public interface ResolutionService {

    /**
     * 生成决议(审批通过后):只形成 ISSUED,不直接视为合同已执行(§12.4)
     * 供审批/表决模块在审批终态后直接注入调用;前置校验:分项终态(APPROVED_LEVEL/FINAL)、按分项幂等
     */
    CcrResolution createResolution(Long pricingItemId, BigDecimal finalRate, String carrierType,
                                   String carrierBusinessKey, LocalDate effectiveFrom,
                                   LocalDate effectiveTo, String decisionSource);

    /**
     * 回填正式合同/补充协议,按 §7.7 七项(客户、产品、金额≥、期限≥、担保主类型、决议有效期、最终利率)校验一致性;
     * 仅 CONTRACT_PENDING 可回填(RECONCILE_EXCEPTION 允许显式重填),相同合同号+相同利率重复提交幂等返回;
     * 同一 loan_contract_no 只能绑定一个未关闭决议;绑定成功同事务自动触发 executeCheck 两级核验
     */
    CcrResolutionExecution bindContract(Long resolutionId, ContractBindDTO bindDTO);

    /**
     * 执行核验(两级):合同执行利率==决议利率;合同下有效借据执行利率==合同利率(§7.7)
     * 第二级按 dw_loan_note_snapshot 最新 data_dt 批次的 ACTIVE 借据逐笔比对;无借据数据按 WARN 通过
     */
    CcrResolutionExecution executeCheck(Long resolutionId);

    /**
     * 决议列表(§13.2,角色数据权限):客户经理看本人申请的;行长/admin/auditor 全量;
     * 其余审批角色看本人审批/表决/决策过的申请
     */
    List<Map<String, Object>> listResolutions();

    /**
     * 决议详情(含执行记录),数据权限同列表
     */
    Map<String, Object> resolutionDetail(Long resolutionId);
}
