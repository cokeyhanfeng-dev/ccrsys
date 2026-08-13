package com.ccr.resolution.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrGuaranteePackage;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.resolution.domain.CcrNotificationLog;
import com.ccr.resolution.domain.CcrResolution;
import com.ccr.resolution.domain.CcrResolutionExecution;
import com.ccr.resolution.domain.DwLoanNoteSnapshot;
import com.ccr.resolution.dto.ContractBindDTO;
import com.ccr.resolution.mapper.CcrApplicationReadMapper;
import com.ccr.resolution.mapper.CcrGuaranteePackageReadMapper;
import com.ccr.resolution.mapper.CcrNotificationLogWriteMapper;
import com.ccr.resolution.mapper.CcrPricingItemReadMapper;
import com.ccr.resolution.mapper.CcrResolutionExecutionMapper;
import com.ccr.resolution.mapper.CcrResolutionMapper;
import com.ccr.resolution.mapper.DwLoanNoteSnapshotMapper;
import com.ccr.resolution.service.ResolutionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 决议与执行核验实现(§7.7)
 * 决议是内部定价审批结论;回填不覆盖原决议,按业务版本幂等
 */
@Slf4j
@Service
public class ResolutionServiceImpl implements ResolutionService {

    /** 借据快照有效状态 */
    private static final String NOTE_STATUS_ACTIVE = "ACTIVE";
    /** 核验异常通知消息键前缀(分辨率唯一,防重复发送) */
    private static final String NOTIFY_KEY_PREFIX = "RECONCILE_EXCEPTION:";
    /** 预留合同经办岗角色编码(待消息模块按岗位路由到实际经办人) */
    private static final String ROLE_CONTRACT_OPERATOR = "contract_operator";

    @Resource
    private CcrResolutionMapper resolutionMapper;
    @Resource
    private CcrResolutionExecutionMapper executionMapper;
    @Resource
    private CcrPricingItemReadMapper pricingItemReadMapper;
    @Resource
    private CcrGuaranteePackageReadMapper guaranteePackageReadMapper;
    @Resource
    private CcrApplicationReadMapper applicationReadMapper;
    @Resource
    private DwLoanNoteSnapshotMapper loanNoteSnapshotMapper;
    @Resource
    private CcrNotificationLogWriteMapper notificationLogMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrResolution createResolution(Long pricingItemId, BigDecimal finalRate, String carrierType,
                                          String carrierBusinessKey, LocalDate effectiveFrom,
                                          LocalDate effectiveTo, String decisionSource) {
        // 否决决议(COMMITTEE_REJECT)无最终利率,finalRate 可空;批准决议最终利率必填
        boolean committeeReject = "COMMITTEE_REJECT".equals(decisionSource);
        if (pricingItemId == null || (finalRate == null && !committeeReject)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    committeeReject ? "分项必填" : "分项与最终利率必填");
        }
        Long dup = resolutionMapper.selectCount(new LambdaQueryWrapper<CcrResolution>()
                .eq(CcrResolution::getPricingItemId, pricingItemId));
        if (dup != null && dup > 0) {
            throw new ServiceException(ErrorCode.IDEMPOTENCY_REPEAT.getCode(), "该分项已存在决议");
        }
        // 校验分项状态:批准决议为权限内已批或终态(PRD V2 §7.6:APPROVED_LEVEL→FINAL);
        // 否决决议(COMMITTEE_REJECT)放行分项 REJECTED(小组表决未通过出否决决议书)
        CcrPricingItem item = pricingItemReadMapper.selectById(pricingItemId);
        if (item != null) {
            boolean approvedStatus = "APPROVED_LEVEL".equals(item.getStatus())
                    || "FINAL".equals(item.getStatus());
            boolean rejectedStatus = committeeReject && "REJECTED".equals(item.getStatus());
            if (!approvedStatus && !rejectedStatus) {
                throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                        "分项状态未通过,不能生成决议");
            }
        }

        CcrResolution resolution = new CcrResolution();
        // 决议编号:RES+yyyyMMdd+8位随机数,降低碰撞
        resolution.setResolutionNo("RES" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + RandomUtil.randomNumbers(8));
        resolution.setPricingItemId(pricingItemId);
        resolution.setPricingCarrierType(carrierType);
        resolution.setPricingCarrierBusinessKey(carrierBusinessKey);
        resolution.setFinalRate(finalRate);
        resolution.setEffectiveFrom(effectiveFrom);
        resolution.setEffectiveTo(effectiveTo);
        resolution.setDecisionSource(decisionSource);
        resolution.setIssueTime(LocalDateTime.now());
        resolution.setStatus("ISSUED");
        resolutionMapper.insert(resolution);

        // 决议签发仅 ISSUED,待合同回填(§12.4);否决决议无执行核验(无合同可回填,不建 CONTRACT_PENDING)
        if (!committeeReject) {
            CcrResolutionExecution exec = new CcrResolutionExecution();
            exec.setResolutionId(resolution.getId());
            exec.setContractBusinessKey(carrierBusinessKey);
            exec.setExecutionStatus("CONTRACT_PENDING");
            executionMapper.insert(exec);
        }
        return resolution;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrResolutionExecution bindContract(Long resolutionId, ContractBindDTO bindDTO) {
        CcrResolution resolution = resolutionMapper.selectById(resolutionId);
        if (resolution == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "决议不存在");
        }
        if (bindDTO == null || StrUtil.isBlank(bindDTO.getLoanContractNo()) || bindDTO.getExecutionRate() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "正式合同号与执行利率必填");
        }
        CcrResolutionExecution exec = executionMapper.selectOne(
                new LambdaQueryWrapper<CcrResolutionExecution>()
                        .eq(CcrResolutionExecution::getResolutionId, resolutionId));
        if (exec == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "决议执行记录不存在");
        }
        // 幂等:相同合同号+相同利率重复提交,返回原记录不更新
        if ("CONTRACT_BOUND".equals(exec.getExecutionStatus())
                && bindDTO.getLoanContractNo().equals(exec.getLoanContractNo())
                && exec.getExecutionRate() != null
                && bindDTO.getExecutionRate().compareTo(exec.getExecutionRate()) == 0) {
            log.info("决议 {} 合同 {} 重复回填,幂等返回原记录", resolutionId, bindDTO.getLoanContractNo());
            return exec;
        }
        // 状态守卫:仅 CONTRACT_PENDING 可回填,RECONCILE_EXCEPTION 允许显式重填;EXECUTED/CLOSED 拒绝
        String executionStatus = exec.getExecutionStatus();
        if ("EXECUTED".equals(executionStatus) || "CLOSED".equals(executionStatus)) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "决议已执行或已关闭,禁止回填合同");
        }
        if (!"CONTRACT_PENDING".equals(executionStatus) && !"RECONCILE_EXCEPTION".equals(executionStatus)) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "当前执行状态不允许回填合同:" + executionStatus);
        }

        CcrPricingItem item = pricingItemReadMapper.selectById(resolution.getPricingItemId());
        if (item == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "决议关联的定价分项不存在");
        }
        // 存量调息:分项原执行利率非空时,补充协议编号必填
        if (item.getOriginalRate() != null && StrUtil.isBlank(bindDTO.getSupplementAgreementNo())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "存量调息场景必须提供补充协议编号");
        }

        // §7.7 回填校验:客户、产品、金额、期限、担保主类型、最终利率、签署日期须在决议有效期内
        List<String> errors = validateBindConsistency(resolution, item, bindDTO);
        if (!errors.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "合同回填校验不通过:" + String.join(";", errors));
        }
        // 同一 loan_contract_no 只能绑定一个未关闭决议(执行记录未 CLOSED 即占用)
        List<CcrResolutionExecution> occupied = executionMapper.selectList(
                new LambdaQueryWrapper<CcrResolutionExecution>()
                        .eq(CcrResolutionExecution::getLoanContractNo, bindDTO.getLoanContractNo())
                        .ne(CcrResolutionExecution::getId, exec.getId())
                        .ne(CcrResolutionExecution::getExecutionStatus, "CLOSED"));
        if (!occupied.isEmpty()) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "合同号[" + bindDTO.getLoanContractNo() + "]已绑定未关闭决议,禁止重复绑定");
        }

        exec.setLoanContractNo(bindDTO.getLoanContractNo());
        exec.setSupplementAgreementNo(bindDTO.getSupplementAgreementNo());
        exec.setExecutionRate(bindDTO.getExecutionRate());
        exec.setBindTime(LocalDateTime.now());
        exec.setExecutionStatus("CONTRACT_BOUND");
        executionMapper.updateById(exec);
        // 绑定成功同事务自动触发两级核验(§7.7),核验结果直接反映在返回记录上
        return executeCheck(resolutionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrResolutionExecution executeCheck(Long resolutionId) {
        CcrResolution resolution = resolutionMapper.selectById(resolutionId);
        CcrResolutionExecution exec = executionMapper.selectOne(
                new LambdaQueryWrapper<CcrResolutionExecution>()
                        .eq(CcrResolutionExecution::getResolutionId, resolutionId));
        if (resolution == null || exec == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "决议或执行记录不存在");
        }
        // 第一级:合同执行利率==决议利率
        boolean level1 = exec.getExecutionRate() != null
                && exec.getExecutionRate().compareTo(resolution.getFinalRate()) == 0;
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("level1_contract_vs_resolution", level1 ? "PASS" : "FAIL");

        // 第二级:合同下最新数仓批次的有效借据执行利率==合同利率(dw_loan_note_snapshot)
        List<DwLoanNoteSnapshot> notes = selectLatestActiveNotes(exec.getLoanContractNo());
        boolean level2 = true;
        boolean noteDataMissing = notes.isEmpty();
        String sourceBatchId = null;
        if (noteDataMissing) {
            diff.put("level2_note_vs_contract", "WARN");
            diff.put("level2_note", "数仓无合同[" + exec.getLoanContractNo() + "]的有效借据快照,第二级核验未执行");
        } else {
            sourceBatchId = notes.get(0).getDataDt().toString();
            List<Map<String, Object>> mismatchNotes = new ArrayList<>();
            for (DwLoanNoteSnapshot note : notes) {
                if (note.getExecutionRate() == null || exec.getExecutionRate() == null
                        || note.getExecutionRate().compareTo(exec.getExecutionRate()) != 0) {
                    level2 = false;
                    Map<String, Object> mismatch = new LinkedHashMap<>();
                    mismatch.put("loanNoteNo", note.getLoanNoteNo());
                    mismatch.put("noteExecutionRate", note.getExecutionRate());
                    mismatch.put("contractExecutionRate", exec.getExecutionRate());
                    mismatchNotes.add(mismatch);
                }
            }
            diff.put("level2_note_vs_contract", level2 ? "PASS" : "FAIL");
            diff.put("level2_note_count", notes.size());
            if (!mismatchNotes.isEmpty()) {
                diff.put("level2_mismatch_notes", mismatchNotes);
            }
        }

        exec.setReconcileTime(LocalDateTime.now());
        exec.setSourceBatchId(sourceBatchId);
        if (level1 && level2) {
            // 无借据数据按 WARN 通过,不误判为异常
            exec.setReconcileResult(noteDataMissing ? "WARN" : "PASS");
            exec.setExecutionStatus("EXECUTED");
            exec.setDifferenceJson(noteDataMissing ? diff : null);
            // 显式置空历史差异,避免上次异常残留(null 字段 updateById 不更新)
            // 注意:Wrapper 更新不应用实体 TypeHandler,Map 值走 toString→binary 字符集会被 MySQL JSON 列拒绝,
            // 故 JSON 列参数在此手动序列化为 String(utf8)传入;null 传 null 保留"清除历史差异"语义
            executionMapper.update(null, new LambdaUpdateWrapper<CcrResolutionExecution>()
                    .eq(CcrResolutionExecution::getId, exec.getId())
                    .set(CcrResolutionExecution::getReconcileTime, exec.getReconcileTime())
                    .set(CcrResolutionExecution::getSourceBatchId, exec.getSourceBatchId())
                    .set(CcrResolutionExecution::getReconcileResult, exec.getReconcileResult())
                    .set(CcrResolutionExecution::getExecutionStatus, exec.getExecutionStatus())
                    .set(CcrResolutionExecution::getDifferenceJson,
                            exec.getDifferenceJson() == null ? null : JSONUtil.toJsonStr(exec.getDifferenceJson())));
        } else {
            return markReconcileException(exec, resolution, "决议执行核验不通过", diff);
        }
        log.info("决议 {} 核验完成: {} level1={} level2={}", resolutionId, exec.getReconcileResult(), level1, level2);
        return exec;
    }

    // ---------- 决议查询(§13.2,角色数据权限) ----------

    @Override
    public List<Map<String, Object>> listResolutions() {
        Long loginId = StpUtil.getLoginIdAsLong();
        String roleCode = currentRoleCode(loginId);
        String sql = """
                SELECT r.id, r.resolution_no resolutionNo, r.pricing_item_id pricingItemId,
                       r.pricing_carrier_type carrierType, r.pricing_carrier_business_key carrierBusinessKey,
                       r.final_rate finalRate, r.effective_from effectiveFrom, r.effective_to effectiveTo,
                       r.decision_source decisionSource, r.status, r.issue_time issueTime,
                       pi.pricing_item_no pricingItemNo, pi.pricing_customer_no customerNo,
                       a.id applicationId, a.application_no applicationNo, a.business_type businessType
                FROM ccr_resolution r
                JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id
                JOIN ccr_application a ON a.id = pi.application_id
                WHERE r.del_flag = '0'
                """;
        if (isFullViewRole(roleCode)) {
            return jdbcTemplate.queryForList(sql + " ORDER BY r.issue_time DESC");
        }
        if ("customer_manager".equals(roleCode)) {
            // 客户经理:本人申请
            return jdbcTemplate.queryForList(
                    sql + " AND a.applicant_user_id = ? ORDER BY r.issue_time DESC", loginId);
        }
        // 其余审批角色:本人审批/表决/决策过的申请
        return jdbcTemplate.queryForList(
                sql + " AND a.id IN (" + participatedApplicationSql(loginId) + ") ORDER BY r.issue_time DESC");
    }

    @Override
    public Map<String, Object> resolutionDetail(Long resolutionId) {
        Long loginId = StpUtil.getLoginIdAsLong();
        String roleCode = currentRoleCode(loginId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.*, pi.application_id appId, a.applicant_user_id applicantUserId"
                        + " FROM ccr_resolution r JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id"
                        + " JOIN ccr_application a ON a.id = pi.application_id"
                        + " WHERE r.id = ? AND r.del_flag = '0'", resolutionId);
        if (rows.isEmpty()) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "决议不存在");
        }
        Map<String, Object> resolution = rows.get(0);
        // 数据权限:全量角色放行;客户经理仅本人申请;其余审批角色须本人参与过
        if (!isFullViewRole(roleCode)) {
            Object applicant = resolution.get("applicantUserId");
            Long participated = "customer_manager".equals(roleCode) ? null
                    : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ("
                            + participatedApplicationSql(loginId) + ") t WHERE t.application_id = "
                            + ((Number) resolution.get("appId")).longValue(), Long.class);
            boolean allowed = "customer_manager".equals(roleCode)
                    ? applicant != null && loginId.equals(((Number) applicant).longValue())
                    : participated != null && participated > 0;
            if (!allowed) {
                throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "无权查看该决议");
            }
        }
        resolution.remove("appId");
        resolution.remove("applicantUserId");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resolution", resolution);
        // 执行记录(含核验结果)
        result.put("executions", jdbcTemplate.queryForList(
                "SELECT id, contract_business_key contractBusinessKey, loan_contract_no loanContractNo,"
                        + " supplement_agreement_no supplementAgreementNo, execution_rate executionRate,"
                        + " bind_time bindTime, execution_status executionStatus, source_batch_id sourceBatchId,"
                        + " reconcile_result reconcileResult, reconcile_time reconcileTime"
                        + " FROM ccr_resolution_execution WHERE resolution_id = ? AND del_flag = '0'",
                resolutionId));
        return result;
    }

    /** 全量数据权限角色:行长/管理员/审计 */
    private boolean isFullViewRole(String roleCode) {
        return "president".equals(roleCode) || "admin".equals(roleCode) || "auditor".equals(roleCode)
                || "contract_operator".equals(roleCode);
    }

    /** 登录人角色编码(用户表为准,不接受传参) */
    private String currentRoleCode(Long loginId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT role_code roleCode FROM ccr_sys_user WHERE id = ? AND del_flag = '0'", loginId);
        if (rows.isEmpty()) {
            throw new ServiceException(ErrorCode.UNAUTHORIZED.getCode(), "登录用户不存在");
        }
        Object roleCode = rows.get(0).get("roleCode");
        return roleCode == null ? null : roleCode.toString();
    }

    /** 审批人"本人参与过"的申请 id 集合:审批轨迹 ∪ 本人票据(哈希) ∪ 行长决策 */
    private String participatedApplicationSql(Long userId) {
        return "SELECT pi.application_id FROM ccr_approval_action aa"
                + " JOIN ccr_pricing_item pi ON pi.id = aa.pricing_item_id"
                + " WHERE aa.del_flag = '0' AND aa.operator_id = " + userId
                + " UNION SELECT pi.application_id FROM ccr_ballot b"
                + " JOIN ccr_pricing_item pi ON pi.id = b.pricing_item_id"
                + " WHERE b.del_flag = '0' AND b.voter_user_hash = SHA2('" + userId + "', 256)"
                + " UNION SELECT pi.application_id FROM ccr_president_decision pd"
                + " JOIN ccr_pricing_item pi ON pi.id = pd.pricing_item_id"
                + " WHERE pd.del_flag = '0' AND pd.president_user_id = " + userId;
    }

    // ---------- 私有 ----------

    /** §7.7 回填一致性逐字段比对,返回不一致项说明(空表示全部通过) */
    private List<String> validateBindConsistency(CcrResolution resolution, CcrPricingItem item,
                                                 ContractBindDTO bindDTO) {
        List<String> errors = new ArrayList<>();
        if (StrUtil.isBlank(bindDTO.getCustomerNo())
                || !bindDTO.getCustomerNo().equals(item.getPricingCustomerNo())) {
            errors.add("客户不一致(分项客户=" + item.getPricingCustomerNo() + ",合同客户=" + bindDTO.getCustomerNo() + ")");
        }
        if (StrUtil.isBlank(bindDTO.getProductCode())
                || !bindDTO.getProductCode().equals(item.getProductCode())) {
            errors.add("产品不一致(分项产品=" + item.getProductCode() + ",合同产品=" + bindDTO.getProductCode() + ")");
        }
        // 金额/期限:合同金额≥决议金额、期限≥决议期限(§7.7 修正口径)
        if (bindDTO.getContractAmount() == null || item.getPricingAmount() == null
                || bindDTO.getContractAmount().compareTo(item.getPricingAmount()) < 0) {
            errors.add("合同金额小于决议金额(决议金额=" + item.getPricingAmount()
                    + ",合同金额=" + bindDTO.getContractAmount() + ")");
        }
        if (!Objects.equals(bindDTO.getTermUnit(), item.getTermUnit())) {
            errors.add("期限单位不一致(分项期限=" + item.getTermValue() + item.getTermUnit()
                    + ",合同期限=" + bindDTO.getTermValue() + bindDTO.getTermUnit() + ")");
        } else if (bindDTO.getTermValue() == null || item.getTermValue() == null
                || bindDTO.getTermValue() < item.getTermValue()) {
            errors.add("合同期限小于决议期限(决议期限=" + item.getTermValue() + item.getTermUnit()
                    + ",合同期限=" + bindDTO.getTermValue() + bindDTO.getTermUnit() + ")");
        }
        // 担保主类型:分项冻结担保组合的主类型
        String mainGuaranteeType = null;
        if (item.getGuaranteePackageId() != null) {
            CcrGuaranteePackage guaranteePackage =
                    guaranteePackageReadMapper.selectById(item.getGuaranteePackageId());
            mainGuaranteeType = guaranteePackage == null ? null : guaranteePackage.getMainGuaranteeType();
        }
        if (mainGuaranteeType != null && !mainGuaranteeType.equals(bindDTO.getGuaranteeType())) {
            errors.add("担保主类型不一致(分项担保=" + mainGuaranteeType + ",合同担保=" + bindDTO.getGuaranteeType() + ")");
        }
        // 最终利率:合同执行利率必须等于决议利率
        if (bindDTO.getExecutionRate().compareTo(resolution.getFinalRate()) != 0) {
            errors.add("最终利率不一致(决议利率=" + resolution.getFinalRate()
                    + ",合同执行利率=" + bindDTO.getExecutionRate() + ")");
        }
        // 决议有效期:签署日期必须落在 effective_from~effective_to 内
        if (bindDTO.getSignDate() == null) {
            errors.add("合同/补充协议签署日期必填");
        } else {
            if (resolution.getEffectiveFrom() != null
                    && bindDTO.getSignDate().isBefore(resolution.getEffectiveFrom())) {
                errors.add("签署日期早于决议生效日(" + resolution.getEffectiveFrom() + ")");
            }
            if (resolution.getEffectiveTo() != null
                    && bindDTO.getSignDate().isAfter(resolution.getEffectiveTo())) {
                errors.add("签署日期晚于决议失效日(" + resolution.getEffectiveTo() + ")");
            }
        }
        return errors;
    }

    /** 取合同最新 data_dt 批次的 ACTIVE 借据(数仓只读) */
    private List<DwLoanNoteSnapshot> selectLatestActiveNotes(String loanContractNo) {
        if (StrUtil.isBlank(loanContractNo)) {
            return List.of();
        }
        DwLoanNoteSnapshot latest = loanNoteSnapshotMapper.selectOne(
                new LambdaQueryWrapper<DwLoanNoteSnapshot>()
                        .eq(DwLoanNoteSnapshot::getContractNo, loanContractNo)
                        .eq(DwLoanNoteSnapshot::getNoteStatus, NOTE_STATUS_ACTIVE)
                        .orderByDesc(DwLoanNoteSnapshot::getDataDt)
                        .last("LIMIT 1"));
        if (latest == null) {
            return List.of();
        }
        return loanNoteSnapshotMapper.selectList(new LambdaQueryWrapper<DwLoanNoteSnapshot>()
                .eq(DwLoanNoteSnapshot::getContractNo, loanContractNo)
                .eq(DwLoanNoteSnapshot::getNoteStatus, NOTE_STATUS_ACTIVE)
                .eq(DwLoanNoteSnapshot::getDataDt, latest.getDataDt()));
    }

    private CcrResolutionExecution markReconcileException(CcrResolutionExecution exec, CcrResolution resolution,
                                                          String reason, Map<String, Object> diff) {
        exec.setReconcileResult("FAILED");
        exec.setExecutionStatus("RECONCILE_EXCEPTION");
        exec.setReconcileTime(LocalDateTime.now());
        diff.put("reason", reason);
        exec.setDifferenceJson(diff);
        executionMapper.updateById(exec);
        notifyReconcileException(resolution, reason);
        log.warn("决议 {} 核验异常: {}", resolution.getId(), reason);
        return exec;
    }

    /** 核验异常通知落库(§7.7:通知客户经理、合同经办岗);message_key 分辨率唯一防重复 */
    private void notifyReconcileException(CcrResolution resolution, String reason) {
        String content = "决议[" + resolution.getResolutionNo() + "]执行核验异常:" + reason + ",请核查合同与借据利率";
        // 申请人/客户经理
        CcrPricingItem item = pricingItemReadMapper.selectById(resolution.getPricingItemId());
        if (item != null && item.getApplicationId() != null) {
            CcrApplication application = applicationReadMapper.selectById(item.getApplicationId());
            if (application != null && application.getApplicantUserId() != null) {
                insertNotification(resolution, "CUSTOMER_MANAGER",
                        String.valueOf(application.getApplicantUserId()), content);
            }
        }
        // 预留合同经办岗(角色编码路由,待消息模块投递到实际经办人)
        insertNotification(resolution, "ROLE", ROLE_CONTRACT_OPERATOR, content);
    }

    private void insertNotification(CcrResolution resolution, String recipientType, String recipientId,
                                    String content) {
        String messageKey = NOTIFY_KEY_PREFIX + resolution.getResolutionNo() + ":" + recipientType + ":" + recipientId;
        Long exists = notificationLogMapper.selectCount(new LambdaQueryWrapper<CcrNotificationLog>()
                .eq(CcrNotificationLog::getMessageKey, messageKey));
        if (exists != null && exists > 0) {
            return;
        }
        CcrNotificationLog notification = new CcrNotificationLog();
        notification.setStatus("SENDING");
        // 系统内部核验异常通知不关联通知规则版本,置 0(表 NOT NULL 约束)
        notification.setRuleVersionId(0L);
        notification.setRecipientType(recipientType);
        notification.setRecipientId(recipientId);
        notification.setChannel("SYSTEM");
        notification.setMessageKey(messageKey);
        notification.setMessageContent(content);
        notification.setSendStatus("PENDING");
        notification.setRetryCount(0);
        notificationLogMapper.insert(notification);
    }
}
