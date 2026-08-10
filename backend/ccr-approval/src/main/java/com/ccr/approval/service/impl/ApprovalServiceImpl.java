package com.ccr.approval.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.approval.domain.CcrApprovalAction;
import com.ccr.approval.domain.CcrRateAdjustment;
import com.ccr.approval.domain.DwLoanNoteSnapshot;
import com.ccr.approval.mapper.CcrApprovalActionMapper;
import com.ccr.approval.mapper.CcrRateAdjustmentMapper;
import com.ccr.approval.mapper.DwLoanNoteReadMapper;
import com.ccr.approval.service.ApprovalService;
import com.ccr.approval.support.RouteChains;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrNodePermission;
import com.ccr.rule.engine.RuleEngine;
import com.ccr.rule.mapper.CcrNodePermissionMapper;
import com.ccr.vote.read.SysUserRead;
import com.ccr.vote.service.ItemFinalizationService;
import com.ccr.vote.service.VoteService;
import com.ccr.vote.support.CurrentLoginUser;
import com.ccr.workflow.service.WarmFlowService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 普通节点审批实现(§7.2 贷款 / §7.3 存款)
 * 安全口径:操作人取 Sa-Token 登录人;nodeCode 必须等于分项当前节点且登录人具备该节点角色。
 * 权限内判定:贷款 审批利率≥节点下限可终审;存款 审批利率≤节点上限可终审(§8.2)。
 * 超权限保留利率通过 → 自动上送下一节点(不变);上送到六人小组时自动合批表决(§7.4)。
 * 存款/保证金:仅支行行长过手,通过后直接触发合批上会(双轨消除,与 D16b 一致)。
 */
@Slf4j
@Service
public class ApprovalServiceImpl implements ApprovalService {

    @Resource
    private CcrPricingItemMapper pricingItemMapper;
    @Resource
    private CcrApplicationMapper applicationMapper;
    @Resource
    private CcrApprovalActionMapper approvalActionMapper;
    @Resource
    private CcrRateAdjustmentMapper rateAdjustmentMapper;
    @Resource
    private CcrNodePermissionMapper nodePermissionMapper;
    @Resource
    private RuleEngine ruleEngine;
    @Resource
    private VoteService voteService;
    @Resource
    private ItemFinalizationService itemFinalizationService;
    @Resource
    private CurrentLoginUser currentLoginUser;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private DwLoanNoteReadMapper loanNoteSnapshotMapper;
    @Resource
    private WarmFlowService warmFlowService;
    @Resource
    private NodeAssigneeResolver nodeAssigneeResolver;

    @Override
    public List<CcrPricingItem> listTodo() {
        SysUserRead user = currentLoginUser.requireCurrentUser();
        LambdaQueryWrapper<CcrPricingItem> wrapper = new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getStatus, PricingItemStatus.ROUTING.getCode())
                .orderByAsc(CcrPricingItem::getCreateTime);
        if (CurrentLoginUser.ROLE_ADMIN.equals(user.getRoleCode())) {
            return pricingItemMapper.selectList(wrapper);
        }
        String nodeCode = currentLoginUser.nodeOfRole(user.getRoleCode());
        // 无节点角色(客户经理)或小组节点(走表决待办) → 普通审批待办为空
        if (nodeCode == null || RouteChains.SIX_PEOPLE_GROUP.equals(nodeCode)) {
            return List.of();
        }
        // 支行行长(含网点)只见本支行及下辖网点客户经理的申请(§5.4 DEPT 级:apply_branch_code 前缀匹配)
        if (CurrentLoginUser.ROLE_BRANCH_MANAGER.equals(user.getRoleCode())) {
            String branchPrefix = branchCodeOf(user.getOrgId());
            if (branchPrefix != null) {
                wrapper.inSql(CcrPricingItem::getApplicationId,
                        "SELECT id FROM ccr_application WHERE del_flag = '0' AND apply_branch_code LIKE '" + branchPrefix + "%'");
            }
        }
        List<CcrPricingItem> items = pricingItemMapper.selectList(
                wrapper.eq(CcrPricingItem::getCurrentNodeCode, nodeCode));
        return filterByNodeAssignee(items, nodeCode, user.getId());
    }

    /** 支行编码(机构 org_id → ccr_sys_dept.branch_code;网点用户上溯所属支行) */
    private String branchCodeOf(Long orgId) {
        if (orgId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT branch_code FROM ccr_sys_dept WHERE id = ? AND del_flag = '0' LIMIT 1", orgId);
        return rows.isEmpty() || rows.get(0).get("branch_code") == null ? null : String.valueOf(rows.get(0).get("branch_code"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long pricingItemId, String nodeCode, BigDecimal adjustRate, String comment,
                        Integer versionNo, String idempotencyKey) {
        SysUserRead operator = checkOperatorAndNode(nodeCode);
        guardIdempotency(idempotencyKey);
        CcrPricingItem item = getRoutingItem(pricingItemId, nodeCode);
        CcrApplication application = getApplication(item.getApplicationId());
        // 节点审批人配置限制(§5.5.1):配置了有效指派时仅解析出的处理人可操作
        guardNodeAssignee(nodeCode, application, operator);
        String businessType = application.getBusinessType();
        boolean deposit = "DEPOSIT".equals(businessType);
        // 存款双轨消除:普通审批链对 DEPOSIT 分项只允许支行行长节点动作
        if (deposit && !RouteChains.BRANCH_MANAGER.equals(nodeCode)) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "存款分项仅支行行长过手,此后上会小组表决");
        }
        // 支行行长(含网点)只能审批本支行及下辖网点客户经理的申请(§5.4,与待办过滤同口径)
        if (CurrentLoginUser.ROLE_BRANCH_MANAGER.equals(operator.getRoleCode())) {
            String branchPrefix = branchCodeOf(operator.getOrgId());
            if (branchPrefix != null) {
                List<Map<String, Object>> appRows = jdbcTemplate.queryForList(
                        "SELECT apply_branch_code FROM ccr_application WHERE id = ? LIMIT 1", application.getId());
                String appBranch = appRows.isEmpty() || appRows.get(0).get("apply_branch_code") == null
                        ? null : String.valueOf(appRows.get(0).get("apply_branch_code"));
                if (appBranch == null || !appBranch.startsWith(branchPrefix)) {
                    throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "非本支行客户经理的申请,无权审批");
                }
            }
        }

        CcrNodePermission perm = nodePermissionMapper.selectOne(new LambdaQueryWrapper<CcrNodePermission>()
                .eq(CcrNodePermission::getNodeCode, nodeCode)
                .eq(CcrNodePermission::getBusinessType, businessType)
                .last("limit 1"));

        // B07 调价边界:主动调价不得突破本节点权限边界(超权限利率只能保留上送,不能由本节点调价产生),
        // 且不得突破产品硬边界(RuleEngine 校验,越界抛 HARD_BOUNDARY)
        BigDecimal beforeRate = item.getCurrentApprovalRate();
        boolean adjusted = adjustRate != null && (beforeRate == null || adjustRate.compareTo(beforeRate) != 0);
        BigDecimal effectiveRate = adjusted ? adjustRate : beforeRate;
        if (adjusted) {
            if (!inNodePermission(businessType, adjustRate, perm)) {
                throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                        "调价突破本节点权限边界:节点[" + nodeCode + "] 调价利率 " + adjustRate);
            }
            ruleEngine.checkHardBoundary(businessType, item.getProductCode(), adjustRate);
        }

        // 目标状态计算
        boolean terminal = false;
        boolean toGroup = false;
        String targetNode = nodeCode;
        String targetStatus = PricingItemStatus.ROUTING.getCode();
        BigDecimal finalRate = null;
        if (deposit) {
            // 存款/保证金:利率未超期限上限(冻结 boundary_rate,含等于)由支行行长终审;超上限才上会小组
            BigDecimal upper = item.getBoundaryRate();
            if (upper != null && effectiveRate != null && effectiveRate.compareTo(upper) <= 0) {
                terminal = true;
                targetStatus = PricingItemStatus.APPROVED_LEVEL.getCode();
                finalRate = effectiveRate;
            } else {
                toGroup = true;
            }
        } else if (inNodePermission(businessType, effectiveRate, perm)) {
            // 权限内 → 终审
            terminal = true;
            targetStatus = PricingItemStatus.APPROVED_LEVEL.getCode();
            finalRate = effectiveRate;
        } else {
            // 超权限保留利率 → 自动上送(§7.2)
            String next = RouteChains.nextNode(nodeCode);
            if (next == null) {
                terminal = true;
                targetStatus = PricingItemStatus.APPROVED_LEVEL.getCode();
                finalRate = effectiveRate;
            } else if (RouteChains.SIX_PEOPLE_GROUP.equals(next)) {
                toGroup = true;
            } else {
                targetNode = next;
            }
        }
        if (toGroup) {
            targetNode = RouteChains.SIX_PEOPLE_GROUP;
        }

        // 带状态+版本条件更新(WHERE status=ROUTING AND version_no=?),0 行按竞态区分错误码
        updateItemWithStateAndVersion(item, targetNode, targetStatus,
                adjusted ? effectiveRate : null, finalRate, versionNo);

        if (adjusted) {
            saveAdjustment(item, nodeCode, operator.getId(), beforeRate, adjustRate, perm);
        }
        // §14.7 流转留痕:动作前 ROUTING,动作后 终审→APPROVED_LEVEL / 上送→ROUTING / 上送小组→VOTING
        insertAction(buildAction(item.getId(), "APPROVE", nodeCode, operator.getId(),
                comment, beforeRate, effectiveRate, idempotencyKey,
                PricingItemStatus.ROUTING.getCode(),
                toGroup ? PricingItemStatus.VOTING.getCode() : targetStatus));
        // Warm-Flow 业务轨迹(失败仅记日志,不阻断主流程)
        warmFlowService.recordBusinessTrail(item.getPricingItemNo(), nodeCode, "APPROVE",
                operatorName(operator), comment);

        if (toGroup) {
            // 上送小组:同申请小组节点未入批分项自动合为一批,入批后分项置 VOTING
            voteService.createGroupRound(item.getApplicationId());
        } else if (terminal) {
            // 权限内终审通过:生成决议+承诺计划+主申请聚合(异常不阻断主流程)
            itemFinalizationService.afterItemTerminal(item.getId(), "LEVEL_APPROVED");
        }
        log.info("分项 {} 节点 {} 通过, 操作人 {} 调价:{} 权限内终审:{} 上送小组:{}",
                pricingItemId, nodeCode, operator.getId(), adjusted, terminal, toGroup);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long pricingItemId, String nodeCode, String comment, Integer versionNo, String idempotencyKey) {
        SysUserRead operator = checkOperatorAndNode(nodeCode);
        // §7.3 普通节点否决原因必填
        if (StrUtil.isBlank(comment)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "否决必须填写原因(§7.3)");
        }
        guardIdempotency(idempotencyKey);
        CcrPricingItem item = getRoutingItem(pricingItemId, nodeCode);
        CcrApplication application = getApplication(item.getApplicationId());
        // 节点审批人配置限制(§5.5.1):配置了有效指派时仅解析出的处理人可操作
        guardNodeAssignee(nodeCode, application, operator);
        if ("DEPOSIT".equals(application.getBusinessType()) && !RouteChains.BRANCH_MANAGER.equals(nodeCode)) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "存款分项仅支行行长过手");
        }
        // 支行行长(含网点)只能审批本支行及下辖网点客户经理的申请(§5.4,与待办过滤同口径)
        if (CurrentLoginUser.ROLE_BRANCH_MANAGER.equals(operator.getRoleCode())) {
            String branchPrefix = branchCodeOf(operator.getOrgId());
            if (branchPrefix != null) {
                List<Map<String, Object>> appRows = jdbcTemplate.queryForList(
                        "SELECT apply_branch_code FROM ccr_application WHERE id = ? LIMIT 1", application.getId());
                String appBranch = appRows.isEmpty() || appRows.get(0).get("apply_branch_code") == null
                        ? null : String.valueOf(appRows.get(0).get("apply_branch_code"));
                if (appBranch == null || !appBranch.startsWith(branchPrefix)) {
                    throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "非本支行客户经理的申请,无权审批");
                }
            }
        }

        updateItemWithStateAndVersion(item, item.getCurrentNodeCode(), PricingItemStatus.REJECTED.getCode(),
                null, null, versionNo, comment);

        insertAction(buildAction(item.getId(), "REJECT", nodeCode, operator.getId(),
                comment, item.getCurrentApprovalRate(), item.getCurrentApprovalRate(), idempotencyKey,
                PricingItemStatus.ROUTING.getCode(), PricingItemStatus.REJECTED.getCode()));
        // Warm-Flow 业务轨迹(失败仅记日志,不阻断主流程)
        warmFlowService.recordBusinessTrail(item.getPricingItemNo(), nodeCode, "REJECT",
                operatorName(operator), comment);
        // 否决终态:聚合主申请状态
        itemFinalizationService.afterItemTerminal(item.getId(), null);
        log.info("分项 {} 节点 {} 否决, 操作人 {}", pricingItemId, nodeCode, operator.getId());
    }

    // ---------- 已办(§11.4) ----------

    @Override
    public List<Map<String, Object>> listDone() {
        Long operatorId = currentLoginUser.requireLoginId();
        return jdbcTemplate.queryForList("""
                SELECT aa.pricing_item_id pricingItemId, aa.action_type actionType, aa.node_code nodeCode,
                       aa.action_comment actionComment, aa.before_rate beforeRate, aa.after_rate afterRate,
                       aa.from_status fromStatus, aa.to_status toStatus, aa.operation_time operationTime,
                       pi.pricing_item_no pricingItemNo, pi.pricing_customer_no customerNo,
                       pi.current_approval_rate currentApprovalRate, pi.status itemStatus,
                       a.id applicationId, a.application_no applicationNo, a.business_type businessType
                FROM ccr_approval_action aa
                JOIN ccr_pricing_item pi ON pi.id = aa.pricing_item_id
                JOIN ccr_application a ON a.id = pi.application_id
                WHERE aa.operator_id = ? AND aa.del_flag = '0'
                ORDER BY aa.operation_time DESC
                """, operatorId);
    }

    // ---------- 历史审批(§13.2/§14.4) ----------

    @Override
    public Map<String, Object> pageHistory(int pageNum, int pageSize) {
        SysUserRead user = currentLoginUser.requireCurrentUser();
        Page<CcrApplication> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 200));
        LambdaQueryWrapper<CcrApplication> wrapper = new LambdaQueryWrapper<>();
        String role = user.getRoleCode();
        if (CurrentLoginUser.ROLE_CUSTOMER_MANAGER.equals(role)) {
            // 客户经理:本人申请
            wrapper.eq(CcrApplication::getApplicantUserId, user.getId());
        } else if (!CurrentLoginUser.ROLE_PRESIDENT.equals(role) && !CurrentLoginUser.ROLE_ADMIN.equals(role)) {
            // 审批人(含委员):本人审批/表决/决策过的申请;行长与审计(admin)看全部
            wrapper.inSql(CcrApplication::getId, participatedApplicationSql(user.getId()));
        }
        wrapper.orderByDesc(CcrApplication::getCreateTime);
        Page<CcrApplication> result = applicationMapper.selectPage(page, wrapper);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotal());
        data.put("records", result.getRecords());
        return data;
    }

    @Override
    public Map<String, Object> historyDetail(Long applicationId) {
        SysUserRead user = currentLoginUser.requireCurrentUser();
        List<Map<String, Object>> apps = jdbcTemplate.queryForList(
                "SELECT * FROM ccr_application WHERE id = ? AND del_flag = '0'", applicationId);
        if (apps.isEmpty()) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "申请不存在");
        }
        Map<String, Object> application = apps.get(0);
        checkHistoryPermission(user, application);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("application", application);
        result.put("members", jdbcTemplate.queryForList(
                "SELECT * FROM ccr_application_member WHERE application_id = ? AND del_flag = '0'", applicationId));
        result.put("pricingItems", jdbcTemplate.queryForList(
                "SELECT * FROM ccr_pricing_item WHERE application_id = ? AND del_flag = '0' ORDER BY create_time", applicationId));

        // 合同/存款账户关系 + 合同下借据(数仓最新批次)
        List<Map<String, Object>> contracts = jdbcTemplate.queryForList(
                "SELECT id, pricing_item_id pricingItemId, contract_business_key contractBusinessKey,"
                        + " loan_contract_no loanContractNo, planned_contract_flag plannedContractFlag"
                        + " FROM ccr_pricing_item_contract_rel WHERE application_id = ? AND del_flag = '0'"
                        + " ORDER BY pricing_item_id, id", applicationId);
        result.put("contracts", contracts);
        result.put("depositAccounts", jdbcTemplate.queryForList(
                "SELECT id, pricing_item_id pricingItemId, deposit_account_no_cipher depositAccountNoCipher,"
                        + " planned_account_flag plannedAccountFlag"
                        + " FROM ccr_pricing_item_deposit_rel WHERE application_id = ? AND del_flag = '0'"
                        + " ORDER BY pricing_item_id, id", applicationId));
        result.put("notes", selectArchiveNotes(contracts, application.get("snapshot_bundle_id")));

        // 快照包信息 + 质量校验结果(PASS/WARN/BLOCK 汇总)
        Object bundleId = application.get("snapshot_bundle_id");
        if (bundleId != null) {
            result.put("snapshotBundles", jdbcTemplate.queryForList(
                    "SELECT id, bundle_no bundleNo, status, freeze_time freezeTime, bundle_hash bundleHash, record_count recordCount FROM ccr_snapshot_bundle WHERE id = ?", bundleId));
            result.put("qualityResults", jdbcTemplate.queryForList(
                    "SELECT rule_code ruleCode, rule_level ruleLevel, subject_type subjectType, subject_id subjectId, message, checked_time checkedTime FROM ccr_snapshot_quality_result WHERE bundle_id = ? AND del_flag = '0' ORDER BY rule_level DESC, rule_code", bundleId));
        } else {
            result.put("snapshotBundles", List.of());
            result.put("qualityResults", List.of());
        }

        // 审批轨迹 + 调价记录
        result.put("approvalActions", jdbcTemplate.queryForList(
                "SELECT aa.* FROM ccr_approval_action aa JOIN ccr_pricing_item pi ON pi.id = aa.pricing_item_id WHERE pi.application_id = ? AND aa.del_flag = '0' ORDER BY aa.operation_time", applicationId));
        result.put("rateAdjustments", jdbcTemplate.queryForList(
                "SELECT ra.* FROM ccr_rate_adjustment ra JOIN ccr_pricing_item pi ON pi.id = ra.pricing_item_id WHERE pi.application_id = ? AND ra.del_flag = '0' ORDER BY ra.operation_time", applicationId));

        // 表决汇总(只到计票结果粒度,不返回票据明细,保持委员匿名)
        result.put("voteRounds", jdbcTemplate.queryForList(
                "SELECT id, round_no roundNo, round_name roundName, status, voter_count voterCount, required_count requiredCount, round_start_time roundStartTime, round_end_time roundEndTime FROM ccr_vote_round WHERE application_id = ? AND del_flag = '0' ORDER BY round_no", applicationId));
        result.put("voteResults", jdbcTemplate.queryForList(
                "SELECT vr.round_id roundId, vr.pricing_item_id pricingItemId, vr.approve_count approveCount, vr.reject_count rejectCount, vr.result, vr.count_time countTime FROM ccr_vote_result vr JOIN ccr_pricing_item pi ON pi.id = vr.pricing_item_id WHERE pi.application_id = ? AND vr.del_flag = '0'", applicationId));
        result.put("presidentDecisions", jdbcTemplate.queryForList(
                "SELECT pd.pricing_item_id pricingItemId, pd.decision, pd.opinion, pd.decision_time decisionTime FROM ccr_president_decision pd JOIN ccr_pricing_item pi ON pi.id = pd.pricing_item_id WHERE pi.application_id = ? AND pd.del_flag = '0'", applicationId));

        // 决议 + 执行核验
        result.put("resolutions", jdbcTemplate.queryForList(
                "SELECT r.id, r.resolution_no resolutionNo, r.pricing_item_id pricingItemId, r.final_rate finalRate, r.effective_from effectiveFrom, r.effective_to effectiveTo, r.decision_source decisionSource, r.status, r.issue_time issueTime FROM ccr_resolution r JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id WHERE pi.application_id = ? AND r.del_flag = '0'", applicationId));
        result.put("resolutionExecutions", jdbcTemplate.queryForList(
                "SELECT re.resolution_id resolutionId, re.loan_contract_no loanContractNo, re.supplement_agreement_no supplementAgreementNo, re.execution_rate executionRate, re.execution_status executionStatus, re.reconcile_result reconcileResult, re.reconcile_time reconcileTime FROM ccr_resolution_execution re JOIN ccr_resolution r ON r.id = re.resolution_id JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id WHERE pi.application_id = ? AND re.del_flag = '0'", applicationId));

        // 承诺计划 + 指标
        result.put("commitmentPlans", jdbcTemplate.queryForList(
                "SELECT cp.id, cp.plan_no planNo, cp.resolution_id resolutionId, cp.scope_type scopeType, cp.status, cp.start_date startDate, cp.end_date endDate FROM ccr_commitment_plan cp JOIN ccr_resolution r ON r.id = cp.resolution_id JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id WHERE pi.application_id = ? AND cp.del_flag = '0'", applicationId));
        result.put("commitmentMetrics", jdbcTemplate.queryForList(
                "SELECT cm.plan_id planId, cm.metric_code metricCode, cm.target_type targetType, cm.baseline_value baselineValue, cm.target_value targetValue, cm.unit, cm.metric_scope metricScope FROM ccr_commitment_metric cm JOIN ccr_commitment_plan cp ON cp.id = cm.plan_id JOIN ccr_resolution r ON r.id = cp.resolution_id JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id WHERE pi.application_id = ? AND cm.del_flag = '0'", applicationId));
        return result;
    }

    // ---------- 私有 ----------

    /**
     * 档案借据区块(D11/§9.5):优先读申请冻结快照包内 subject_type=NOTE 的记录
     * (按分项合同 loan_contract_no 过滤 core_json.contract_no);无快照包时降级回数仓最新批次
     */
    private List<DwLoanNoteSnapshot> selectArchiveNotes(List<Map<String, Object>> contracts, Object bundleId) {
        List<String> contractNos = contracts.stream()
                .map(c -> c.get("loanContractNo"))
                .filter(Objects::nonNull).map(Object::toString)
                .filter(StrUtil::isNotBlank).distinct().toList();
        if (contractNos.isEmpty()) {
            return List.of();
        }
        if (bundleId != null) {
            List<DwLoanNoteSnapshot> fromSnapshot = selectSnapshotNotes(bundleId, contractNos);
            if (!fromSnapshot.isEmpty()) {
                return fromSnapshot;
            }
        }
        // 降级:数仓最新 data_dt 批次
        List<DwLoanNoteSnapshot> notes = new ArrayList<>();
        for (String contractNo : contractNos) {
            notes.addAll(selectLatestActiveNotes(contractNo));
        }
        return notes;
    }

    /** 快照包内 NOTE 记录(core_json 为提交时冻结的借据行,只读解析) */
    private List<DwLoanNoteSnapshot> selectSnapshotNotes(Object bundleId, List<String> contractNos) {
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT core_json coreJson FROM ccr_snapshot_record"
                        + " WHERE bundle_id = ? AND subject_type = 'NOTE' AND del_flag = '0'",
                bundleId);
        List<DwLoanNoteSnapshot> notes = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Object coreJson = record.get("coreJson");
            if (coreJson == null) {
                continue;
            }
            JSONObject core = JSONUtil.parseObj(coreJson.toString());
            String contractNo = core.getStr("contract_no");
            if (StrUtil.isBlank(contractNo) || !contractNos.contains(contractNo)) {
                continue;
            }
            DwLoanNoteSnapshot note = new DwLoanNoteSnapshot();
            note.setDataDt(core.get("data_dt", LocalDate.class));
            note.setLoanNoteNo(core.getStr("loan_note_no"));
            note.setContractNo(contractNo);
            note.setTrancheNo(core.getStr("tranche_no"));
            note.setBorrowerCustomerNo(core.getStr("borrower_customer_no"));
            note.setLoanAmount(core.get("loan_amount", BigDecimal.class));
            note.setLoanBalance(core.get("loan_balance", BigDecimal.class));
            note.setCurrency(core.getStr("currency"));
            note.setExecutionRate(core.get("execution_rate", BigDecimal.class));
            note.setRateType(core.getStr("rate_type"));
            note.setLprTerm(core.getStr("lpr_term"));
            note.setStartDate(core.get("start_date", LocalDate.class));
            note.setMaturityDate(core.get("maturity_date", LocalDate.class));
            note.setNoteStatus(core.getStr("note_status"));
            notes.add(note);
        }
        return notes;
    }

    /** 取合同最新 data_dt 批次的 ACTIVE 借据(数仓只读) */
    private List<DwLoanNoteSnapshot> selectLatestActiveNotes(String loanContractNo) {
        DwLoanNoteSnapshot latest = loanNoteSnapshotMapper.selectOne(
                new LambdaQueryWrapper<DwLoanNoteSnapshot>()
                        .eq(DwLoanNoteSnapshot::getContractNo, loanContractNo)
                        .eq(DwLoanNoteSnapshot::getNoteStatus, "ACTIVE")
                        .orderByDesc(DwLoanNoteSnapshot::getDataDt)
                        .last("LIMIT 1"));
        if (latest == null) {
            return List.of();
        }
        return loanNoteSnapshotMapper.selectList(new LambdaQueryWrapper<DwLoanNoteSnapshot>()
                .eq(DwLoanNoteSnapshot::getContractNo, loanContractNo)
                .eq(DwLoanNoteSnapshot::getNoteStatus, "ACTIVE")
                .eq(DwLoanNoteSnapshot::getDataDt, latest.getDataDt()));
    }

    /** 轨迹操作人:优先姓名,空则用户id字符串 */
    private String operatorName(SysUserRead operator) {
        return StrUtil.isNotBlank(operator.getNickName())
                ? operator.getNickName() : String.valueOf(operator.getId());
    }

    /**
     * 节点审批人配置过滤(§5.5.1):节点配置了有效指派时,仅解析出的处理人可见;
     * 解析为空(未配置)保持现有角色匹配,向后兼容
     */
    private List<CcrPricingItem> filterByNodeAssignee(List<CcrPricingItem> items, String nodeCode, Long userId) {
        if (items.isEmpty()) {
            return items;
        }
        List<Long> appIds = items.stream().map(CcrPricingItem::getApplicationId)
                .filter(Objects::nonNull).distinct().toList();
        // 申请id → 申请人机构(applicantOrgId 可能为空,不用 Collectors.toMap)
        Map<Long, Long> appOrg = new LinkedHashMap<>();
        for (CcrApplication app : applicationMapper.selectBatchIds(appIds)) {
            appOrg.put(app.getId(), app.getApplicantOrgId());
        }
        List<CcrPricingItem> filtered = new ArrayList<>();
        for (CcrPricingItem item : items) {
            List<Long> assignees = nodeAssigneeResolver.resolveUserIds(nodeCode,
                    appOrg.get(item.getApplicationId()));
            if (!assignees.isEmpty() && !assignees.contains(userId)) {
                continue;
            }
            filtered.add(item);
        }
        return filtered;
    }

    /** 节点审批人配置校验(§5.5.1):配置了有效指派时,仅解析出的处理人可通过/否决 */
    private void guardNodeAssignee(String nodeCode, CcrApplication application, SysUserRead operator) {
        List<Long> assignees = nodeAssigneeResolver.resolveUserIds(nodeCode, application.getApplicantOrgId());
        if (!assignees.isEmpty() && !assignees.contains(operator.getId())) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                    "节点[" + nodeCode + "]已配置指定审批人,当前登录人不在指派范围内");
        }
    }

    /** 身份与节点校验:小组/行长节点不走普通审批通道;登录人须具备节点角色 */
    private SysUserRead checkOperatorAndNode(String nodeCode) {
        if (StrUtil.isBlank(nodeCode)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "节点编码必填");
        }
        // 六人小组从普通 approve/reject 路径移除(小组分项只能经表决流转)
        if (RouteChains.SIX_PEOPLE_GROUP.equals(nodeCode) || "PRESIDENT".equals(nodeCode)) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                    "节点[" + nodeCode + "]不属于普通审批通道");
        }
        SysUserRead operator = currentLoginUser.requireCurrentUser();
        currentLoginUser.requireNodeRole(nodeCode);
        return operator;
    }

    /** 幂等键防护:同键已处理直接拒绝(uk_action_idem 兜底) */
    private void guardIdempotency(String idempotencyKey) {
        if (StrUtil.isBlank(idempotencyKey)) {
            return;
        }
        Long count = approvalActionMapper.selectCount(new LambdaQueryWrapper<CcrApprovalAction>()
                .eq(CcrApprovalAction::getIdempotencyKey, idempotencyKey));
        if (count != null && count > 0) {
            throw new ServiceException(ErrorCode.IDEMPOTENCY_REPEAT.getCode(), "重复提交:幂等键已处理");
        }
    }

    /** 分项存在且在审批节点,且 nodeCode==当前节点 */
    private CcrPricingItem getRoutingItem(Long pricingItemId, String nodeCode) {
        if (pricingItemId == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "分项必填");
        }
        CcrPricingItem item = pricingItemMapper.selectById(pricingItemId);
        if (item == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "定价分项不存在");
        }
        if (!PricingItemStatus.ROUTING.getCode().equals(item.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "分项不在审批节点");
        }
        if (!nodeCode.equals(item.getCurrentNodeCode())) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                    "分项当前不在节点[" + nodeCode + "],实际节点[" + item.getCurrentNodeCode() + "]");
        }
        return item;
    }

    private CcrApplication getApplication(Long applicationId) {
        if (applicationId == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "分项缺少所属申请");
        }
        CcrApplication application = applicationMapper.selectById(applicationId);
        if (application == null) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "所属申请不存在");
        }
        return application;
    }

    /**
     * 带状态+版本条件的分项更新(WHERE id=? AND status='ROUTING' AND version_no=?);
     * 0 行:状态已变迁 → TASK_PROCESSED;版本不符 → DATA_VERSION_CONFLICT
     */
    private void updateItemWithStateAndVersion(CcrPricingItem item, String targetNode, String targetStatus,
                                               BigDecimal newApprovalRate, BigDecimal finalRate,
                                               Integer versionNo) {
        updateItemWithStateAndVersion(item, targetNode, targetStatus, newApprovalRate, finalRate, versionNo, null);
    }

    private void updateItemWithStateAndVersion(CcrPricingItem item, String targetNode, String targetStatus,
                                               BigDecimal newApprovalRate, BigDecimal finalRate,
                                               Integer versionNo, String finalReason) {
        if (versionNo == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "版本号 versionNo 必传");
        }
        LambdaUpdateWrapper<CcrPricingItem> wrapper = new LambdaUpdateWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getId, item.getId())
                .eq(CcrPricingItem::getStatus, PricingItemStatus.ROUTING.getCode())
                .eq(CcrPricingItem::getVersionNo, versionNo)
                .set(CcrPricingItem::getCurrentNodeCode, targetNode)
                .set(CcrPricingItem::getStatus, targetStatus)
                .set(newApprovalRate != null, CcrPricingItem::getCurrentApprovalRate, newApprovalRate)
                .set(finalRate != null, CcrPricingItem::getFinalRate, finalRate)
                .set(finalReason != null, CcrPricingItem::getFinalReason, finalReason)
                .set(CcrPricingItem::getVersionNo, versionNo + 1)
                .set(CcrPricingItem::getUpdateTime, LocalDateTime.now())
                .set(CcrPricingItem::getUpdateBy, currentLoginUser.requireLoginId());
        int rows = pricingItemMapper.update(null, wrapper);
        if (rows > 0) {
            return;
        }
        CcrPricingItem latest = pricingItemMapper.selectById(item.getId());
        if (latest == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "定价分项不存在");
        }
        if (!PricingItemStatus.ROUTING.getCode().equals(latest.getStatus())) {
            throw new ServiceException(ErrorCode.TASK_PROCESSED.getCode(), "分项已被处理,请勿重复操作");
        }
        throw new ServiceException(ErrorCode.DATA_VERSION_CONFLICT.getCode(), "分项数据版本冲突,请刷新后重试");
    }

    /** 权限内判定(§8.2):贷款审批利率≥节点下界;存款审批利率≤节点上界 */
    private boolean inNodePermission(String businessType, BigDecimal rate, CcrNodePermission perm) {
        if (perm == null || rate == null) {
            return true; // 未配置权限边界 → 视为可终审
        }
        if ("LOAN".equals(businessType)) {
            return perm.getBoundaryMinRate() == null || rate.compareTo(perm.getBoundaryMinRate()) >= 0;
        }
        return perm.getBoundaryMaxRate() == null || rate.compareTo(perm.getBoundaryMaxRate()) <= 0;
    }

    private void saveAdjustment(CcrPricingItem item, String nodeCode, Long operatorId,
                                BigDecimal before, BigDecimal after, CcrNodePermission perm) {
        CcrRateAdjustment adj = new CcrRateAdjustment();
        adj.setPricingItemId(item.getId());
        adj.setNodeCode(nodeCode);
        adj.setBeforeRate(before);
        adj.setAfterRate(after);
        adj.setBoundaryMinRate(perm == null ? null : perm.getBoundaryMinRate());
        adj.setBoundaryMaxRate(perm == null ? null : perm.getBoundaryMaxRate());
        adj.setAdjustReason("节点调价");
        adj.setOperatorId(operatorId);
        adj.setOperationChannel("PC");
        adj.setOperationTime(LocalDateTime.now());
        rateAdjustmentMapper.insert(adj);
    }

    private void insertAction(CcrApprovalAction action) {
        try {
            approvalActionMapper.insert(action);
        } catch (DuplicateKeyException e) {
            throw new ServiceException(ErrorCode.IDEMPOTENCY_REPEAT.getCode(), "重复提交:幂等键已处理");
        }
    }

    private CcrApprovalAction buildAction(Long pricingItemId, String actionType, String nodeCode,
                                          Long operatorId, String comment, BigDecimal beforeRate,
                                          BigDecimal afterRate, String idempotencyKey,
                                          String fromStatus, String toStatus) {
        CcrApprovalAction action = new CcrApprovalAction();
        action.setPricingItemId(pricingItemId);
        action.setTaskId(IdUtil.fastSimpleUUID());
        action.setActionType(actionType);
        action.setNodeCode(nodeCode);
        action.setOperatorId(operatorId);
        action.setActionComment(StrUtil.nullToEmpty(comment));
        action.setBeforeRate(beforeRate);
        action.setAfterRate(afterRate);
        action.setFromStatus(fromStatus);
        action.setToStatus(toStatus);
        action.setOperationChannel("PC");
        action.setOperationTime(LocalDateTime.now());
        action.setIdempotencyKey(idempotencyKey);
        return action;
    }

    /** 审批人"本人审批过"的申请 id 集合:审批轨迹 ∪ 本人票据(哈希) ∪ 行长决策 */
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

    /** 档案数据权限:客户经理看本人申请、审批人看本人审批过、行长/审计看全部 */
    private void checkHistoryPermission(SysUserRead user, Map<String, Object> application) {
        String role = user.getRoleCode();
        if (CurrentLoginUser.ROLE_PRESIDENT.equals(role) || CurrentLoginUser.ROLE_ADMIN.equals(role)) {
            return;
        }
        Object applicant = application.get("applicant_user_id");
        if (CurrentLoginUser.ROLE_CUSTOMER_MANAGER.equals(role)) {
            if (applicant != null && user.getId().equals(((Number) applicant).longValue())) {
                return;
            }
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "仅可查看本人申请");
        }
        // 本申请是否有本人审批/表决/决策轨迹
        Object appId = application.get("id");
        Long inThis = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (" + participatedApplicationSql(user.getId()) + ") t WHERE t.application_id = "
                        + ((Number) appId).longValue(),
                Long.class);
        if (inThis == null || inThis == 0) {
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "仅可查看本人审批过的申请");
        }
    }
}
