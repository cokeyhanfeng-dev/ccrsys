package com.ccr.approval.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrApplicationMember;
import com.ccr.application.domain.CcrGuaranteePackage;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrApplicationMemberMapper;
import com.ccr.application.mapper.CcrGuaranteePackageMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.application.service.DataWarehouseService;
import com.ccr.application.support.CustomerNoUtil;
import com.ccr.approval.domain.CcrApprovalAction;
import com.ccr.approval.domain.CcrRateAdjustment;
import com.ccr.approval.domain.DwLoanNoteSnapshot;
import com.ccr.approval.dto.ApprovalResult;
import com.ccr.approval.dto.AutoBackfillResult;
import com.ccr.approval.mapper.CcrApprovalActionMapper;
import com.ccr.approval.mapper.CcrRateAdjustmentMapper;
import com.ccr.approval.mapper.DwLoanNoteReadMapper;
import com.ccr.approval.service.ApprovalService;
import com.ccr.approval.support.RouteChains;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.core.util.BranchTypeSupport;
import com.ccr.common.core.util.ContributionMerger;
import com.ccr.common.core.util.OrgAchievementAssembler;
import com.ccr.common.core.util.RelatedCustomerResolver;
import com.ccr.common.core.util.WarehouseCustomerSync;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrNodePermission;
import com.ccr.rule.dto.MatrixRouteInput;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.engine.RuleEngine;
import com.ccr.rule.mapper.CcrNodePermissionMapper;
import com.ccr.rule.service.RateMatrixRouter;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 普通节点审批实现(§7.2 贷款 / §7.3 存款)
 * 安全口径:操作人取 Sa-Token 登录人;nodeCode 必须等于分项当前节点且登录人具备该节点角色。
 * 权限内判定:贷款 审批利率≥节点下限;存款 审批利率≤期限上限(冻结 boundary_rate,含等于)。
 * 整单流转口径 v2(用户拍板 2026-08-27,替代分项独立终审/上送):审批人仍逐项审批(approve 粒度不变),
 * 但分项不各自独立终审/上送,以申请为单位按「逐项同意 → 齐套触发整单动作」推进——
 *   每次 approve 先查本节点 APPROVE 记录判定齐套:同申请全部「本节点 ROUTING」分项均须已同意;
 *   未齐套 → 该分项记「本节点已同意」(ROUTING→ROUTING)停留当前节点,待其余分项逐个同意;
 *   齐套后分派:全部分项权限内通过且当前节点为矩阵冻结终审岗位(route_code) → 整单齐套终审
 *   (全部一起 APPROVED_LEVEL,走既有终态串联);
 *   任一分项保留超权限利率通过,或当前节点为链路中间节点(强制上会场景的支行/部门总/分管,
 *   即使利率在权限内也只有过手权) → 整单上送:全部本节点 ROUTING 分项一起推进下一节点,
 *   下一节点为六人小组时走既有 createGroupRound 合批(§7.4);
 *   否决任一分项 → 整单否决:全部 ROUTING 分项置 REJECTED 并聚合主申请。
 * 「本节点已同意」判定口径:ccr_approval_action 中按 node_code + APPROVE 过滤覆盖的分项集合
 * (与详情页 siblingItems.agreed 同源);上级节点 APPROVE 不视为本节点已通过——每个节点的
 * 每个分项都要逐个同意(中间节点逐项审批),这是 v1「上级已通过后续节点只展示不重复审批」的替代。
 * 存款/保证金:仅支行行长过手;全部未超期限上限才整单终审,任一超上限整单上会(双轨消除,与 D16b 一致)。
 */
@Slf4j
@Service
public class ApprovalServiceImpl implements ApprovalService {

    /** 贷审会秘书岗节点(需求四:整单必经的中间审核节点,批完整单上送小组) */
    private static final String SECRETARY_NODE = "SECRETARY";

    @Resource
    private CcrPricingItemMapper pricingItemMapper;
    @Resource
    private CcrApplicationMapper applicationMapper;
    @Resource
    private CcrApplicationMemberMapper applicationMemberMapper;
    @Resource
    private CcrApprovalActionMapper approvalActionMapper;
    @Resource
    private CcrRateAdjustmentMapper rateAdjustmentMapper;
    @Resource
    private CcrNodePermissionMapper nodePermissionMapper;
    @Resource
    private RuleEngine ruleEngine;
    @Resource
    private RateMatrixRouter rateMatrixRouter;
    @Resource
    private CcrGuaranteePackageMapper guaranteePackageMapper;
    @Resource
    private DataWarehouseService dataWarehouseService;
    @Resource
    private ApplicationAccessService applicationAccessService;
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
        List<CcrPricingItem> result;
        if (CurrentLoginUser.ROLE_ADMIN.equals(user.getRoleCode())) {
            result = pricingItemMapper.selectList(wrapper);
        } else {
            String nodeCode = currentLoginUser.nodeOfRole(user.getRoleCode());
            List<CcrPricingItem> merged = new ArrayList<>();
            // 主角色节点待办;无节点角色(客户经理)或小组节点(走表决待办) → 跳过
            if (nodeCode != null && !RouteChains.SIX_PEOPLE_GROUP.equals(nodeCode)) {
                // 支行行长(含网点)只见本支行及下辖网点客户经理的申请(§5.4 DEPT 级:apply_branch_code 前缀匹配)
                if (CurrentLoginUser.ROLE_BRANCH_MANAGER.equals(user.getRoleCode())) {
                    String branchPrefix = branchCodeOf(user.getOrgId());
                    if (branchPrefix != null) {
                        wrapper.inSql(CcrPricingItem::getApplicationId,
                                "SELECT id FROM ccr_application WHERE del_flag = '0' AND apply_branch_code LIKE '" + branchPrefix + "%'");
                    }
                }
                merged.addAll(filterByNodeAssignee(
                        pricingItemMapper.selectList(
                                wrapper.eq(CcrPricingItem::getCurrentNodeCode, nodeCode)),
                        nodeCode, user.getId()));
            }
            // 秘书岗兼岗(§需求四:贷审会秘书由计划财务部总经理兼任,主角色 dept_gm 映射不到 SECRETARY 节点):
            // 在 SECRETARY 节点指派内的用户额外查该节点 ROUTING 待办
            if (nodeAssigneeResolver.isUserInAssignees("SECRETARY", user.getId())) {
                merged.addAll(filterByNodeAssignee(
                        pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                                .eq(CcrPricingItem::getStatus, PricingItemStatus.ROUTING.getCode())
                                .eq(CcrPricingItem::getCurrentNodeCode, "SECRETARY")
                                .orderByAsc(CcrPricingItem::getCreateTime)),
                        "SECRETARY", user.getId()));
            }
            // 管理综合支行长 PARENT 待办(2026-09-04 综合/零售两级支行):零售支行申请先零售支行长再
            // 综合支行长——本机构直接下级零售支行在 PARENT_BRANCH_MANAGER 节点的 ROUTING 待办由
            // 管理综合支行长处理。按申请人机构=本机构直接下级零售支行收口,非管理行(空下级)不查不泄;
            // PARENT 审批人=管理综合支行 branch_manager(guardNodeAssignee 指派收口),无需再按指派过滤
            List<Long> retailChildIds = BranchTypeSupport.directRetailChildIds(jdbcTemplate, user.getOrgId());
            if (!retailChildIds.isEmpty()) {
                merged.addAll(pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                        .eq(CcrPricingItem::getStatus, PricingItemStatus.ROUTING.getCode())
                        .eq(CcrPricingItem::getCurrentNodeCode, RouteChains.PARENT_BRANCH_MANAGER)
                        .inSql(CcrPricingItem::getApplicationId,
                                "SELECT id FROM ccr_application WHERE del_flag = '0' AND applicant_org_id IN ("
                                        + retailChildIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")")
                        .orderByAsc(CcrPricingItem::getCreateTime)));
            }
            result = merged;
        }
        // 待办卡片客户显示名(§2026-09-01):工作台「待审批」卡片主标题显示客户名称而非客户号,按申请批量反查快照
        fillTodoCustomerName(result);
        return result;
    }

    /** 待办分项客户/集团显示名称:按 applicationId 批量取申请,复用历史列表快照解析口径(客户快照 customerName,集团回退 groupName;§2026-09-01) */
    private void fillTodoCustomerName(List<CcrPricingItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<Long> appIds = items.stream().map(CcrPricingItem::getApplicationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (appIds.isEmpty()) {
            return;
        }
        List<CcrApplication> apps = applicationMapper.selectList(new LambdaQueryWrapper<CcrApplication>()
                .in(CcrApplication::getId, appIds));
        fillDisplayCustomerName(apps);
        // 集团申请快照缺 groupName(仅存 groupNo)时回退手工集团表名称(§2026-09-01)
        Map<Long, String> groupFallback = new HashMap<>();
        for (CcrApplication a : apps) {
            if (StrUtil.isBlank(a.getCustomerName()) && StrUtil.isNotBlank(a.getGroupInfoJson())) {
                String groupNo = extractJsonName(a.getGroupInfoJson(), "groupNo");
                if (StrUtil.isNotBlank(groupNo)) {
                    // queryForList 而非 queryForObject:查无记录不抛 EmptyResultDataAccessException
                    List<String> names = jdbcTemplate.queryForList(
                            "SELECT group_name FROM ccr_group WHERE group_no = ? AND del_flag = '0' LIMIT 1",
                            String.class, groupNo);
                    if (!names.isEmpty() && StrUtil.isNotBlank(names.get(0))) {
                        groupFallback.put(a.getId(), names.get(0));
                    }
                }
            }
        }
        // 手动循环而非 Collectors.toMap:toMap 对 null value(未解析到名称)抛 NPE
        Map<Long, String> nameByApp = new HashMap<>();
        for (CcrApplication a : apps) {
            nameByApp.put(a.getId(),
                    StrUtil.isNotBlank(a.getCustomerName()) ? a.getCustomerName() : groupFallback.get(a.getId()));
        }
        for (CcrPricingItem item : items) {
            if (item.getApplicationId() != null) {
                item.setCustomerName(nameByApp.get(item.getApplicationId()));
            }
        }
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
    public ApprovalResult approve(Long applicationId, String nodeCode, BigDecimal adjustRate, String comment,
                        Integer versionNo, String idempotencyKey, Map<Long, BigDecimal> rateAdjustments) {
        SysUserRead operator = checkOperatorAndNode(nodeCode);
        guardIdempotency(idempotencyKey);
        CcrApplication application = getApplication(applicationId);
        // 整单交付改造(2026-08-29):审批推进以申请单为准,校验当前节点一致;
        // 历史申请(整单字段未冻结)从在途分项推导当前节点与部门归属(新旧统一,不改存量数据)
        CcrPricingItem legacyAnchor = legacyDerivationSource(application);
        String effectiveNode = legacyAnchor != null ? legacyAnchor.getCurrentNodeCode()
                : application.getCurrentNodeCode();
        if (!nodeCode.equals(effectiveNode)) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                    "申请不在节点[" + nodeCode + "],实际节点[" + effectiveNode + "]");
        }
        // 节点审批人配置限制(§5.5.1):配置了有效指派时仅解析出的处理人可操作(§D16a 部门分流按整单 dept_code)
        guardNodeAssignee(nodeCode, application, operator,
                StrUtil.isNotBlank(application.getDeptCode()) ? application.getDeptCode()
                        : (legacyAnchor != null ? legacyAnchor.getDeptCode() : null));
        String businessType = application.getBusinessType();
        boolean deposit = "DEPOSIT".equals(businessType);
        // 存款双轨消除:普通审批链对 DEPOSIT 申请只允许支行节点动作(零售申请含管理综合支行长 PARENT,2026-09-04)
        if (deposit && !RouteChains.BRANCH_MANAGER.equals(nodeCode)
                && !RouteChains.PARENT_BRANCH_MANAGER.equals(nodeCode)) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "存款申请仅支行行长过手,此后上会小组表决");
        }
        // 支行行长(含网点)只能审批本支行及下辖网点客户经理的申请(§5.4,与待办过滤同口径);
        // PARENT 节点(管理综合支行长审零售子行)不做 apply_branch_code 前缀匹配——越权由 guardNodeAssignee 指派收口
        if (CurrentLoginUser.ROLE_BRANCH_MANAGER.equals(operator.getRoleCode())
                && !RouteChains.PARENT_BRANCH_MANAGER.equals(nodeCode)) {
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

        // 整单化:本次整单动作目标分项 = 同申请 ROUTING 且当前节点==本节点(整单同步推进,无分项提前终审)
        List<CcrPricingItem> routingItems = pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, application.getId())
                .eq(CcrPricingItem::getStatus, PricingItemStatus.ROUTING.getCode())
                .eq(CcrPricingItem::getCurrentNodeCode, nodeCode)
                .orderByAsc(CcrPricingItem::getCreateTime));
        if (routingItems.isEmpty()) {
            throw new ServiceException(ErrorCode.TASK_PROCESSED.getCode(), "申请无待处理分项,请勿重复操作");
        }
        List<Long> routingIds = routingItems.stream().map(CcrPricingItem::getId).toList();
        // 防重复守卫:本节点已有任一在途分项处理记录(APPROVE/REJECT)→整单已处理,拒绝重复
        Long acted = approvalActionMapper.selectCount(new LambdaQueryWrapper<CcrApprovalAction>()
                .eq(CcrApprovalAction::getNodeCode, nodeCode)
                .in(CcrApprovalAction::getActionType, "APPROVE", "REJECT")
                .in(CcrApprovalAction::getPricingItemId, routingIds));
        if (acted != null && acted > 0) {
            throw new ServiceException(ErrorCode.TASK_PROCESSED.getCode(), "申请本节点已处理,请勿重复操作");
        }

        // B07 调价(2026-09-02 逐分项利率为主路径):rateAdjustments(分项id→利率,仅收录变化分项)逐项应用,
        // adjustRate 整单统一兼容旧前端(二者传一,adjustRate 优先);未收录分项沿用原 currentApprovalRate。
        // 利率可任意调整——低于本节点下限不再拦截,调价后按新利率重算审批链路
        // (recalcRoute 沿新链推进,需更高权限时整单带新利率上送更高层级节点重新审批);
        // 产品硬边界仅返回展示值不拦截(§用户要求取消硬边界,RuleEngineImpl.checkHardBoundary 不再抛错)
        Map<Long, BigDecimal> effectiveRates = new LinkedHashMap<>();
        for (CcrPricingItem i : routingItems) {
            BigDecimal rate = adjustRate != null ? adjustRate
                    : (rateAdjustments == null ? null : rateAdjustments.get(i.getId()));
            if (rate == null) {
                rate = i.getCurrentApprovalRate();
            }
            effectiveRates.put(i.getId(), rate);
            ruleEngine.checkHardBoundary(businessType, i.getProductCode(), rate);
        }
        boolean adjusted = adjustRate != null || rateAdjustments != null;

        // 整单链锚定:贷款=当前在途分项中有效利率最低者;存款=原流程(首个分项)。
        // 调价后或历史申请整单链为空 → 按锚定分项要素重算并刷新申请单冻结字段(§8.6 重锚定)
        CcrPricingItem anchor = routingItems.get(0);
        if (!deposit) {
            for (CcrPricingItem i : routingItems) {
                if (effectiveRates.get(i.getId()) != null
                        && effectiveRates.get(i.getId()).compareTo(effectiveRates.get(anchor.getId())) < 0) {
                    anchor = i;
                }
            }
        }
        List<String> chain;
        String routeCode;
        if (deposit) {
            chain = parseRouteChain(application.getRouteChain());
            if (chain.isEmpty()) {
                chain = RouteChains.fullChain(businessType);
            }
            routeCode = application.getRouteCode();
        } else if (adjusted || StrUtil.isBlank(application.getRouteChain())) {
            RouteResult reroute = recalcRoute(application, anchor,
                    adjusted ? effectiveRates.get(anchor.getId()) : anchor.getRequestedRate());
            applyRerouteApplication(application, reroute);
            chain = reroute.getRouteChain();
            routeCode = reroute.getFinalNodeCode();
        } else {
            chain = parseRouteChain(application.getRouteChain());
            routeCode = application.getRouteCode();
        }

        // ===== 整单流转口径(整单交付改造 2026-08-29):一次动作即整单推进/终审,无逐分项齐套 =====
        // 下一节点:存款链支行过手后直上小组;贷款沿整单链推进(矩阵驱动,可跳过无权限节点如GM)。
        // 终点判定:当前节点 == 整单链终审岗位(route_code)才具备整单终审资格;链路中间节点
        // (强制上会场景的支行/部门总/分管)即使利率在权限内也只有过手权,须沿链上送,不就地终审
        boolean isFinalNode;
        String next;
        if (deposit) {
            // 零售存款(2026-09-04):链含 PARENT 时零售支行长过手后先到管理综合支行长,再过手上会小组
            if (RouteChains.PARENT_BRANCH_MANAGER.equals(nodeCode)) {
                next = RouteChains.SIX_PEOPLE_GROUP;
            } else if (RouteChains.BRANCH_MANAGER.equals(nodeCode) && chain.contains(RouteChains.PARENT_BRANCH_MANAGER)) {
                next = RouteChains.PARENT_BRANCH_MANAGER;
            } else {
                next = RouteChains.SIX_PEOPLE_GROUP;
            }
            isFinalNode = false;
        } else {
            next = RouteChains.nextNode(nodeCode, chain);
            isFinalNode = StrUtil.isBlank(routeCode) || routeCode.equals(nodeCode);
        }

        if (isFinalNode) {
            // 整单终审:全部分项一起置 APPROVED_LEVEL,走既有终态串联(决议/承诺/主申请聚合)
            updateWholeOrderItems(routingItems, nodeCode, PricingItemStatus.APPROVED_LEVEL.getCode(),
                    effectiveRates, null, null);
            for (CcrPricingItem i : routingItems) {
                BigDecimal eff = effectiveRates.get(i.getId());
                insertAction(buildAction(i.getId(), "APPROVE", nodeCode, operator.getId(),
                        comment, i.getCurrentApprovalRate(), eff, itemIdempotencyKey(i, routingItems, idempotencyKey),
                        PricingItemStatus.ROUTING.getCode(), PricingItemStatus.APPROVED_LEVEL.getCode()));
                if (eff != null && (i.getCurrentApprovalRate() == null || eff.compareTo(i.getCurrentApprovalRate()) != 0)) {
                    saveAdjustment(i, nodeCode, operator.getId(), i.getCurrentApprovalRate(), eff, perm);
                }
            }
            // Warm-Flow 业务轨迹(失败仅记日志,不阻断主流程)
            warmFlowService.recordBusinessTrail(application.getApplicationNo(), nodeCode, "APPROVE",
                    operatorName(operator), comment);
            // 逐项触发终态串联(决议+承诺计划+主申请聚合,整单一起终审,异常不阻断主流程)
            for (CcrPricingItem i : routingItems) {
                itemFinalizationService.afterItemTerminal(i.getId(), "LEVEL_APPROVED");
            }
            log.info("申请 {} 节点 {} 整单终审通过(共 {} 项), 操作人 {} 调价:{}",
                    applicationId, nodeCode, routingItems.size(), operator.getId(), adjusted);
            return ApprovalResult.terminal();
        }

        // 整单上送:全部分项一起推进下一节点(小组节点经 createGroupRound 合批,入批后置 VOTING)
        boolean toGroup = RouteChains.SIX_PEOPLE_GROUP.equals(next);
        updateWholeOrderItems(routingItems, next, PricingItemStatus.ROUTING.getCode(),
                effectiveRates, null, null);
        for (CcrPricingItem i : routingItems) {
            BigDecimal eff = effectiveRates.get(i.getId());
            insertAction(buildAction(i.getId(), "ESCALATE", nodeCode, operator.getId(),
                    comment, i.getCurrentApprovalRate(), eff, itemIdempotencyKey(i, routingItems, idempotencyKey),
                    PricingItemStatus.ROUTING.getCode(),
                    toGroup ? PricingItemStatus.VOTING.getCode() : PricingItemStatus.ROUTING.getCode()));
            if (eff != null && (i.getCurrentApprovalRate() == null || eff.compareTo(i.getCurrentApprovalRate()) != 0)) {
                saveAdjustment(i, nodeCode, operator.getId(), i.getCurrentApprovalRate(), eff, perm);
            }
        }
        // 同步整单当前节点(申请单为准,分项 current_node_code 已随 updateWholeOrderItems 推进)
        updateApplicationNode(application, next, null);
        // Warm-Flow 业务轨迹(失败仅记日志,不阻断主流程)
        warmFlowService.recordBusinessTrail(application.getApplicationNo(), nodeCode, "APPROVE",
                operatorName(operator), comment);
        if (toGroup) {
            voteService.createGroupRound(applicationId);
        }
        log.info("申请 {} 节点 {} 整单上送 {} 上送小组:{}, 操作人 {} 调价:{}",
                applicationId, nodeCode, next, toGroup, operator.getId(), adjusted);
        // 审批提交成功提示:整单上送推进至下一节点(六人小组等)
        return ApprovalResult.go(next);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApprovalResult reject(Long applicationId, String nodeCode, String comment, Integer versionNo, String idempotencyKey) {
        SysUserRead operator = checkOperatorAndNode(nodeCode);
        // §7.3 普通节点否决原因必填
        if (StrUtil.isBlank(comment)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "否决必须填写原因(§7.3)");
        }
        guardIdempotency(idempotencyKey);
        CcrApplication application = getApplication(applicationId);
        // 整单交付改造(2026-08-29):审批推进以申请单为准,校验当前节点一致;
        // 历史申请(整单字段未冻结)从在途分项推导当前节点与部门归属(新旧统一,不改存量数据)
        CcrPricingItem legacyAnchor = legacyDerivationSource(application);
        String effectiveNode = legacyAnchor != null ? legacyAnchor.getCurrentNodeCode()
                : application.getCurrentNodeCode();
        if (!nodeCode.equals(effectiveNode)) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                    "申请不在节点[" + nodeCode + "],实际节点[" + effectiveNode + "]");
        }
        // 节点审批人配置限制(§5.5.1):配置了有效指派时仅解析出的处理人可操作(§D16a 部门分流按整单 dept_code)
        guardNodeAssignee(nodeCode, application, operator,
                StrUtil.isNotBlank(application.getDeptCode()) ? application.getDeptCode()
                        : (legacyAnchor != null ? legacyAnchor.getDeptCode() : null));
        String businessType = application.getBusinessType();
        boolean deposit = "DEPOSIT".equals(businessType);
        // 存款双轨消除:普通审批链对 DEPOSIT 申请只允许支行节点动作(零售申请含管理综合支行长 PARENT,2026-09-04)
        if (deposit && !RouteChains.BRANCH_MANAGER.equals(nodeCode)
                && !RouteChains.PARENT_BRANCH_MANAGER.equals(nodeCode)) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "存款申请仅支行行长过手");
        }
        // 支行行长(含网点)只能审批本支行及下辖网点客户经理的申请(§5.4,与待办过滤同口径);
        // PARENT 节点(管理综合支行长审零售子行)不做 apply_branch_code 前缀匹配——越权由 guardNodeAssignee 指派收口
        if (CurrentLoginUser.ROLE_BRANCH_MANAGER.equals(operator.getRoleCode())
                && !RouteChains.PARENT_BRANCH_MANAGER.equals(nodeCode)) {
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

        // ===== 整单否决(整单交付改造 2026-08-29):任一节点一次否决即整单否决,无部分否决 =====
        // 本次整单动作目标分项 = 同申请 ROUTING 且当前节点==本节点
        List<CcrPricingItem> routingItems = pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, application.getId())
                .eq(CcrPricingItem::getStatus, PricingItemStatus.ROUTING.getCode())
                .eq(CcrPricingItem::getCurrentNodeCode, nodeCode)
                .orderByAsc(CcrPricingItem::getCreateTime));
        if (routingItems.isEmpty()) {
            throw new ServiceException(ErrorCode.TASK_PROCESSED.getCode(), "申请无待处理分项,请勿重复操作");
        }
        List<Long> routingIds = routingItems.stream().map(CcrPricingItem::getId).toList();
        // 防重复守卫:本节点已有任一在途分项处理记录 → 整单已处理,拒绝重复
        Long acted = approvalActionMapper.selectCount(new LambdaQueryWrapper<CcrApprovalAction>()
                .eq(CcrApprovalAction::getNodeCode, nodeCode)
                .in(CcrApprovalAction::getActionType, "APPROVE", "REJECT")
                .in(CcrApprovalAction::getPricingItemId, routingIds));
        if (acted != null && acted > 0) {
            throw new ServiceException(ErrorCode.TASK_PROCESSED.getCode(), "申请本节点已处理,请勿重复操作");
        }

        // 整单否决:全部分项一起置 REJECTED 终态(finalReason 注明否决原因),主申请经
        // afterItemTerminal 聚合为 REJECTED「已否决」,流程直接结束(不是退回客户经理重办)
        updateWholeOrderItems(routingItems, nodeCode, PricingItemStatus.REJECTED.getCode(),
                null, null, comment);
        for (CcrPricingItem i : routingItems) {
            insertAction(buildAction(i.getId(), "REJECT", nodeCode, operator.getId(),
                    comment, i.getCurrentApprovalRate(), i.getCurrentApprovalRate(), itemIdempotencyKey(i, routingItems, idempotencyKey),
                    PricingItemStatus.ROUTING.getCode(), PricingItemStatus.REJECTED.getCode()));
        }
        // Warm-Flow 业务轨迹(失败仅记日志,不阻断主流程)
        warmFlowService.recordBusinessTrail(application.getApplicationNo(), nodeCode, "REJECT",
                operatorName(operator), comment);
        // 否决终态串联:聚合主申请 REJECTED(REJECTED 非 COMMITTEE_REJECT 不签决议)
        for (CcrPricingItem i : routingItems) {
            itemFinalizationService.afterItemTerminal(i.getId(), null);
        }
        log.info("申请 {} 节点 {} 整单否决(流程直接结束,主申请置否决态), 操作人 {}",
                applicationId, nodeCode, operator.getId());
        return ApprovalResult.terminal();
    }

    // ---------- 审批中客户号回填(2026-08-20 #017) ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void backfillCustomerNo(Long pricingItemId, String customerNo, String certNo) {
        // 权限:能查看该分项的对象(申请人/审批链审批人/行长/审计/admin)方可回填
        applicationAccessService.requirePricingItemView(pricingItemId);
        CcrPricingItem item = pricingItemMapper.selectById(pricingItemId);
        if (item == null || "1".equals(item.getDelFlag())) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "定价分项不存在");
        }
        CcrApplication application = applicationMapper.selectById(item.getApplicationId());
        if (application == null || "1".equals(application.getDelFlag())) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "申请不存在");
        }
        boolean groupScope = "GROUP".equals(application.getCustomerScope());
        String currentNo = item.getPricingCustomerNo();
        if (!CustomerNoUtil.isPlaceholder(currentNo)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "当前客户号已是真实号(" + StrUtil.nullToEmpty(currentNo) + "),无需回填");
        }
        // 解析真实客户号:优先直接给号;仅给证件号时按数仓 cert_no 反查
        String resolved;
        if (StrUtil.isNotBlank(customerNo)) {
            resolved = customerNo.trim();
        } else {
            if (StrUtil.isBlank(certNo)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "回填需提供真实客户号或证件号(customerNo/certNo)");
            }
            Map<String, Object> dw = "INDIVIDUAL".equals(application.getCustomerScope())
                    ? dataWarehouseService.findIndvByCertNo(certNo.trim())
                    : dataWarehouseService.findCorpByCertNo(certNo.trim());
            if (dw == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "按证件号未在数仓命中客户,请核对后直接填写真实客户号");
            }
            resolved = String.valueOf(dw.get("cust_no"));
        }
        if (CustomerNoUtil.isPlaceholder(resolved)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "回填的客户号不能是占位号");
        }
        if (resolved.equals(currentNo)) {
            return; // 幂等:已是目标号,无需变更
        }

        // 1) 回填主申请 customer_no + customer_info_json.customerNo:仅单户
        //    (集团主申请以 group_no 为主标识无客户号,成员号按分项/成员表回填)
        if (!groupScope) {
            applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                    .eq(CcrApplication::getId, application.getId())
                    .set(CcrApplication::getCustomerNo, resolved));
            if (StrUtil.isNotBlank(application.getCustomerInfoJson())) {
                try {
                    JSONObject json = JSONUtil.parseObj(application.getCustomerInfoJson());
                    json.set("customerNo", resolved);
                    applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                            .eq(CcrApplication::getId, application.getId())
                            .set(CcrApplication::getCustomerInfoJson, json.toString()));
                } catch (Exception e) {
                    log.warn("回填 customer_info_json.customerNo 失败,忽略:{}", e.getMessage());
                }
            }
        }
        // 2) 同申请占用位号的分项全部替换(整单口径:单户按客户号一致,集团按成员号一致)
        pricingItemMapper.update(null, new LambdaUpdateWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, application.getId())
                .eq(CcrPricingItem::getPricingCustomerNo, currentNo)
                .set(CcrPricingItem::getPricingCustomerNo, resolved));
        // 2.5) 集团:分项 member_customer_no + ccr_application_member + ccr_group_member + group_info_json 同步(2026-08-20 #017)
        if (groupScope) {
            pricingItemMapper.update(null, new LambdaUpdateWrapper<CcrPricingItem>()
                    .eq(CcrPricingItem::getApplicationId, application.getId())
                    .eq(CcrPricingItem::getMemberCustomerNo, currentNo)
                    .set(CcrPricingItem::getMemberCustomerNo, resolved));
            applicationMemberMapper.update(null, new LambdaUpdateWrapper<CcrApplicationMember>()
                    .eq(CcrApplicationMember::getApplicationId, application.getId())
                    .eq(CcrApplicationMember::getMemberCustomerNo, currentNo)
                    .set(CcrApplicationMember::getMemberCustomerNo, resolved));
            // 手工集团成员表(补录占位号若已落 ccr_group_member,数仓优先不落则无此行)
            if (StrUtil.isNotBlank(application.getGroupNo())) {
                jdbcTemplate.update("""
                        UPDATE ccr_group_member
                        SET member_customer_no = ?
                        WHERE group_no = ? AND member_customer_no = ?""",
                        resolved, application.getGroupNo(), currentNo);
            }
            // group_info_json.supplementMembers[].memberCustomerNo 占位→真实(审批详情集团成员展示)
            if (StrUtil.isNotBlank(application.getGroupInfoJson())) {
                try {
                    JSONObject json = JSONUtil.parseObj(application.getGroupInfoJson());
                    JSONArray supplementMembers = json.getJSONArray("supplementMembers");
                    if (supplementMembers != null) {
                        for (int i = 0; i < supplementMembers.size(); i++) {
                            JSONObject m = supplementMembers.getJSONObject(i);
                            if (currentNo.equals(m.getStr("memberCustomerNo"))) {
                                m.set("memberCustomerNo", resolved);
                            }
                        }
                    }
                    applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                            .eq(CcrApplication::getId, application.getId())
                            .set(CcrApplication::getGroupInfoJson, json.toString()));
                } catch (Exception e) {
                    log.warn("回填 group_info_json 补录成员客户号失败,忽略:{}", e.getMessage());
                }
            }
            // 申请承诺指标成员号同步(终态承诺固化按此 member_customer_no 建 uk_alloc,必须用真实号)
            jdbcTemplate.update("""
                    UPDATE ccr_application_commitment
                    SET member_customer_no = ?
                    WHERE application_id = ? AND member_customer_no = ?""",
                    resolved, application.getId(), currentNo);
        }
        // 3) 同步已冻结快照记录(subject_id + core_json.cust_no)与质量结果 subject_id:
        //    审批详情快照路径按 pricing_customer_no.equals(subjectId) 过滤,必须一并纠正(2026-08-20 #017)
        if (application.getSnapshotBundleId() != null) {
            jdbcTemplate.update("""
                    UPDATE ccr_snapshot_record
                    SET subject_id = ?, core_json = JSON_SET(core_json, '$.cust_no', ?)
                    WHERE bundle_id = ? AND subject_id = ?""", resolved, resolved, application.getSnapshotBundleId(), currentNo);
            jdbcTemplate.update("""
                    UPDATE ccr_snapshot_quality_result
                    SET subject_id = ?
                    WHERE bundle_id = ? AND subject_id = ?""", resolved, application.getSnapshotBundleId(), currentNo);
        }
        // 4) 关联人绑定主体同步(单户,§2026-09-02 无客户号流程):本申请产生的 ccr_relation 绑定
        //    占位/空主体 → 真实号。按 bind_application_no 定位(占位号 NEW+后6位 可能跨申请撞号,
        //    绝不裸按 customer_no 替换);uk_relation_cert(cert_type,cert_no,del_flag) 不含主体,
        //    UPDATE 无唯一键风险;「同证件已属他人主体」在 bind 期 findByCert 已拦截,此处不存在。
        if (!groupScope) {
            jdbcTemplate.update("""
                    UPDATE ccr_relation
                    SET customer_no = ?
                    WHERE bind_application_no = ? AND del_flag = '0' AND group_no IS NULL AND customer_no = ?""",
                    resolved, application.getApplicationNo(), currentNo);
            // 兜底:该申请仍为空/其它占位号的绑定行一律对齐(证件号变更/撞号遗留收敛)
            jdbcTemplate.update("""
                    UPDATE ccr_relation
                    SET customer_no = ?
                    WHERE bind_application_no = ? AND del_flag = '0' AND group_no IS NULL
                      AND (customer_no IS NULL OR customer_no LIKE 'NEW%')""",
                    resolved, application.getApplicationNo());
        }
        // 5) 申请关联人自身客户号补全(related_customer_no 空 → 按本人证件号反查数仓,复用
        //    RelatedCustomerResolver 同款 SQL;未命中保持空,读取期 resolveBatch 继续兜底展示)
        try {
            List<Map<String, Object>> pending = jdbcTemplate.queryForList("""
                    SELECT id, cert_type AS certType, cert_no AS certNo
                    FROM ccr_application_related_person
                    WHERE application_id = ? AND del_flag = '0'
                      AND (related_customer_no IS NULL OR related_customer_no = '')
                      AND cert_no IS NOT NULL AND cert_no <> ''""", application.getId());
            for (Map<String, Object> rp : pending) {
                Object ct = rp.get("certType");
                Object cn = rp.get("certNo");
                if (cn == null) {
                    continue;
                }
                String rc = RelatedCustomerResolver.resolve(jdbcTemplate,
                        ct == null ? null : ct.toString(), cn.toString());
                if (rc != null) {
                    jdbcTemplate.update(
                            "UPDATE ccr_application_related_person SET related_customer_no = ? WHERE id = ?",
                            rc, rp.get("id"));
                }
            }
        } catch (Exception e) {
            log.warn("关联人自身客户号补全失败,忽略:申请 {} 原因:{}", application.getId(), e.getMessage());
        }
        // 6) 客户主档权威刷新(§2026-09-02 #460):单户占位命中数仓后,凡数仓主档行可查出的客户其他信息
        //    (名称/企业性质/规模/行业/评级/地址/开户机构等)以数仓为权威整体覆盖 customer_info_json 与
        //    快照客户行 core_json(退出 MANUAL 纯人工快照,使审批详情展示数仓宽字段);集团成员场景不适用
        if (!groupScope) {
            refreshWarehouseCustomerInfo(application.getId(),
                    "INDIVIDUAL".equals(application.getCustomerScope()), resolved,
                    application.getSnapshotBundleId());
        }
        log.info("审批中回填客户号:分项 {} 申请 {} 场景{} 占位号 {} → 真实号 {}, 操作人 {}",
                pricingItemId, application.getId(), groupScope ? "集团成员" : "单户", currentNo, resolved,
                currentLoginUser.requireLoginId());
    }

    /**
     * §2026-09-02 节点进入自动回填(决策二):单户占位申请进入审批详情时,按 customer_info_json 证件号
     * 反查数仓主档,命中即走 {@link #backfillCustomerNo} 整单占位→真实并级联(主单/分项/快照/关联人绑定/
     * 关联人自身客户号);未命中不写库、不阻塞流程。幂等:主单已真实号直接返回;并发重复触发最终值一致安全。
     *
     * @param applicationId 申请主键
     * @return applicable=是否适用单户自动回填;backfilled=本次是否实际回填;customerNo=回填后的真实客户号(未命中为 null)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AutoBackfillResult autoBackfillCustomerNo(Long applicationId) {
        applicationAccessService.requireView(applicationId);
        CcrApplication application = applicationMapper.selectById(applicationId);
        if (application == null || "1".equals(application.getDelFlag())) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "申请不存在");
        }
        if ("GROUP".equals(application.getCustomerScope())) {
            return new AutoBackfillResult(false, false, null); // 集团成员回填走提交/人工,自动通道仅单户
        }
        String mainNo = application.getCustomerNo();
        boolean placeholder = StrUtil.isBlank(mainNo) || CustomerNoUtil.isPlaceholder(mainNo);
        if (!placeholder) {
            return new AutoBackfillResult(true, false, null); // 已真实号,无需回填
        }
        String certNo = CustomerNoUtil.certNoFromInfoJson(application.getCustomerInfoJson(), application.getCustomerScope());
        if (StrUtil.isBlank(certNo)) {
            return new AutoBackfillResult(true, false, null); // 无证件号无法反查
        }
        Map<String, Object> dw = "INDIVIDUAL".equals(application.getCustomerScope())
                ? dataWarehouseService.findIndvByCertNo(certNo)
                : dataWarehouseService.findCorpByCertNo(certNo);
        if (dw == null || dw.get("cust_no") == null) {
            return new AutoBackfillResult(true, false, null); // 数仓未收录:不写库、不阻塞
        }
        String resolved = String.valueOf(dw.get("cust_no"));
        if (CustomerNoUtil.isPlaceholder(resolved)) {
            return new AutoBackfillResult(true, false, null);
        }
        // 取一个占位分项作锚定(人工回填口径:占位分项方可替换整单;无占位分项视为异常态不触发)
        CcrPricingItem anchor = pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                        .eq(CcrPricingItem::getApplicationId, application.getId())
                        .likeRight(CcrPricingItem::getPricingCustomerNo, CustomerNoUtil.PREFIX)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (anchor == null) {
            return new AutoBackfillResult(true, false, null);
        }
        // 走人工回填同一级联(其内 requirePricingItemView 顺带校验对象级查看权限)
        backfillCustomerNo(anchor.getId(), resolved, null);
        log.info("节点进入自动回填客户号:申请 {} {} → 真实号 {}", application.getId(),
                application.getApplicationNo(), resolved);
        return new AutoBackfillResult(true, true, resolved);
    }

    /**
     * §2026-09-02 #460 客户主档权威刷新(用户拍板:以数仓为权威整体覆盖,非仅补空缺)。
     *
     * <p>单户占位申请命中数仓真实客户号后调用:以数仓主档行(按 cust_no 查最新批次)为权威,
     * 刷新两处客户信息载体——① {@code ccr_application.customer_info_json} 人工快照层(审批详情
     * 人工覆盖/申请页回显源):数仓列可映射的键(名称/企业性质/行业/评级/开户机构/账户等)整体覆盖;
     * ② 快照客户行 {@code ccr_snapshot_record.core_json}:整行数据源替换为数仓行(宽字段如企业规模/
     * 员工数/总资产/地址等人工快照没有的列),并移除 {@code data_source=MANUAL} 标记——否则审批详情
     * manualOnly 分支仍按纯人工快照渲染,数仓宽字段显示不出来。数仓查不出该客户(dw=null)时跳过,
     * 保持人工值不写库(「凡是能查出来的都回填」)。集团场景由 backfill 第 6 步跳过,不走此方法。</p>
     */
    private void refreshWarehouseCustomerInfo(Long applicationId, boolean indv, String resolved, Long snapshotBundleId) {
        Map<String, Object> dw = indv
                ? dataWarehouseService.findIndvCustomer(resolved)
                : dataWarehouseService.findCorpCustomer(resolved);
        if (dw == null) {
            return; // 数仓查不出该客户:仅回填客户号,其余保持人工
        }
        // ① customer_info_json 权威覆盖(仅数仓非空键;customerNo 已由调用方写好,此处补齐其余可查字段)
        try {
            CcrApplication cur = applicationMapper.selectById(applicationId);
            if (cur != null && StrUtil.isNotBlank(cur.getCustomerInfoJson())) {
                JSONObject json = JSONUtil.parseObj(cur.getCustomerInfoJson());
                WarehouseCustomerSync.applyCustomerInfo(json, dw, indv);
                applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                        .eq(CcrApplication::getId, applicationId)
                        .set(CcrApplication::getCustomerInfoJson, json.toString()));
            }
        } catch (Exception e) {
            log.warn("回填 customer_info_json 客户其他信息失败,忽略:申请 {} 原因:{}", applicationId, e.getMessage());
        }
        // ② 快照客户行 core_json 整行数据源刷新(退出 MANUAL,审批详情快照路径展示数仓宽字段)
        if (snapshotBundleId != null) {
            try {
                List<Map<String, Object>> snapshotRows = jdbcTemplate.queryForList("""
                        SELECT id, core_json AS coreJson FROM ccr_snapshot_record
                        WHERE bundle_id = ? AND subject_type = ? AND subject_id = ?
                          AND del_flag = '0'""",
                        snapshotBundleId, indv ? "INDIVIDUAL" : "CORPORATE", resolved);
                for (Map<String, Object> snap : snapshotRows) {
                    Object coreObj = snap.get("coreJson");
                    JSONObject core;
                    try {
                        core = coreObj instanceof JSONObject j ? j : JSONUtil.parseObj(String.valueOf(coreObj));
                    } catch (Exception e) {
                        core = new JSONObject();
                    }
                    WarehouseCustomerSync.applyWarehouseRow(core, dw);
                    core.remove("data_source"); // 占位人工快照标记 → 数仓主档口径
                    jdbcTemplate.update("UPDATE ccr_snapshot_record SET core_json = ? WHERE id = ?",
                            core.toString(), snap.get("id"));
                }
            } catch (Exception e) {
                log.warn("刷新快照客户行数据源失败,忽略:申请 {} 原因:{}", applicationId, e.getMessage());
            }
        }
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

    /** 工作台今日已办(§2026-09-05):今日审批 action ∪ 本人表决 ballot ∪ 本人行长决策,按申请去重(与累计口径一致) */
    @Override
    public int countTodayDone() {
        Long userId = currentLoginUser.requireLoginId();
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                  SELECT pi.application_id FROM ccr_approval_action aa
                   JOIN ccr_pricing_item pi ON pi.id = aa.pricing_item_id
                   WHERE aa.del_flag = '0' AND aa.operator_id = ? AND aa.operation_time >= CURDATE()
                  UNION SELECT pi.application_id FROM ccr_ballot b
                   JOIN ccr_pricing_item pi ON pi.id = b.pricing_item_id
                   WHERE b.del_flag = '0' AND b.voter_user_hash = SHA2(?, 256) AND b.submit_time >= CURDATE()
                  UNION SELECT pi.application_id FROM ccr_president_decision pd
                   JOIN ccr_pricing_item pi ON pi.id = pd.pricing_item_id
                   WHERE pd.del_flag = '0' AND pd.president_user_id = ? AND pd.decision_time >= CURDATE()
                ) t""", Integer.class, userId, userId, userId);
        return n == null ? 0 : n;
    }

    // ---------- 历史审批(§13.2/§14.4) ----------

    @Override
    public Map<String, Object> pageHistory(int pageNum, int pageSize, String applicationNo, String status, String keyword) {
        SysUserRead user = currentLoginUser.requireCurrentUser();
        Page<CcrApplication> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 200));
        LambdaQueryWrapper<CcrApplication> wrapper = new LambdaQueryWrapper<>();
        String role = user.getRoleCode();
        if (CurrentLoginUser.ROLE_CUSTOMER_MANAGER.equals(role)) {
            // 客户经理:本人申请
            wrapper.eq(CcrApplication::getApplicantUserId, user.getId());
        } else if (!CurrentLoginUser.ROLE_ADMIN.equals(role)) {
            // 审批人(含行长/委员/部门总经理/支行行长/副行长):仅本人审批/表决/决策过的申请;
            // 审计(admin)为全局监管视角,保留查看全部
            wrapper.inSql(CcrApplication::getId, participatedApplicationSql(user.getId()));
        }
        // 筛选:申请号模糊(§2026-08-26 历史申请查询)
        if (StrUtil.isNotBlank(applicationNo)) {
            wrapper.like(CcrApplication::getApplicationNo, applicationNo.trim());
        }
        // 状态筛选:逗号分隔多状态(工作台「审批中/否决」等聚合跳转;§2026-08-26)
        if (StrUtil.isNotBlank(status)) {
            List<String> statuses = Arrays.stream(status.split(","))
                    .map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
            if (!statuses.isEmpty()) {
                wrapper.in(CcrApplication::getStatus, statuses);
            }
        }
        // 客户/集团名称模糊:匹配 JSON 快照键值(快照为系统序列化,键顺序/格式稳定;§2026-08-26)
        if (StrUtil.isNotBlank(keyword)) {
            String k = keyword.trim();
            wrapper.and(w -> w
                    .like(CcrApplication::getCustomerInfoJson, "\"customerName\":\"" + k)
                    .or().like(CcrApplication::getGroupInfoJson, "\"groupName\":\"" + k));
        }
        wrapper.orderByDesc(CcrApplication::getCreateTime);
        Page<CcrApplication> result = applicationMapper.selectPage(page, wrapper);
        // 历史列表展示客户/集团名称(客户快照 customerName,集团回退 group_info_json.groupName;§2026-08-26)
        fillDisplayCustomerName(result.getRecords());
        // 决议书可用性:仅已签发决议的申请提供决议书下载(前端"决议书"按钮显隐)
        markHasResolution(result.getRecords());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotal());
        data.put("records", result.getRecords());
        return data;
    }

    /** 历史列表客户/集团显示名称:优先客户快照 customerName,集团申请回退 group_info_json.groupName(§2026-08-26) */
    private void fillDisplayCustomerName(List<CcrApplication> records) {
        for (CcrApplication r : records) {
            String name = extractJsonName(r.getCustomerInfoJson(), "customerName");
            if (StrUtil.isBlank(name)) {
                name = extractJsonName(r.getGroupInfoJson(), "groupName");
            }
            r.setCustomerName(name);
        }
    }

    /** 从 JSON 快照提取指定 key 的字符串值(兼容紧凑/带空格两种序列化: "key":"value" 或 "key": "value";§2026-09-01 兼容) */
    private String extractJsonName(String json, String key) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // key 为内部固定值(customerName/groupName),无注入风险
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 批量标记申请是否有已签发决议(一次 IN 查询避免 N+1) */
    private void markHasResolution(List<CcrApplication> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        StringBuilder in = new StringBuilder();
        for (CcrApplication r : records) {
            if (in.length() > 0) {
                in.append(',');
            }
            in.append(r.getId());
        }
        // 整单化后决议按申请维度落库(application_id 直存,2026-08-29 起);旧数据逐分项(pricing_item_id,经 pi 关联);
        // 双键兼容:COALESCE(r.application_id, pi.application_id) 取申请 id,LEFT JOIN 适配整单决议无分项关联(2026-09-02)
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(r.application_id, pi.application_id) applicationId FROM ccr_resolution r"
                        + " LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id"
                        + " WHERE (r.application_id IN (" + in + ") OR pi.application_id IN (" + in + "))"
                        + " AND r.del_flag = '0' GROUP BY applicationId");
        Set<Long> hasResolution = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object v = row.get("applicationId");
            if (v != null) {
                hasResolution.add(((Number) v).longValue());
            }
        }
        for (CcrApplication r : records) {
            r.setHasResolution(hasResolution.contains(r.getId()));
        }
    }

    /** 授信协议历史审批申请(§2026-09-01 存量授信展示:按 credit_info_json.agreementNo 查同协议历史申请,返回审批状态/金额) */
    @Override
    public List<Map<String, Object>> agreementHistory(String agreementNo) {
        if (StrUtil.isBlank(agreementNo)) {
            return java.util.Collections.emptyList();
        }
        return jdbcTemplate.queryForList("""
                SELECT a.application_no applicationNo, a.business_type businessType,
                       a.customer_no customerNo, a.status, a.submit_time submitTime,
                       a.final_time finalTime,
                       (SELECT COALESCE(SUM(p.pricing_amount), 0) FROM ccr_pricing_item p
                        WHERE p.application_id = a.id AND p.del_flag = '0') applicationAmount
                FROM ccr_application a
                WHERE a.del_flag = '0' AND a.status <> 'DRAFT'
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.credit_info_json, '$.agreementNo')) = ?
                ORDER BY a.submit_time DESC, a.id DESC""", agreementNo);
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
        List<Map<String, Object>> members = jdbcTemplate.queryForList(
                "SELECT * FROM ccr_application_member WHERE application_id = ? AND del_flag = '0'", applicationId);
        // 集团成员信息补充:名称 + 完整对公要素(快照数仓成员主数据 → 申请补录 → 实时降级)
        enrichArchiveMemberNames(members, application);
        result.put("members", members);
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
                "SELECT id, pricing_item_id pricingItemId, deposit_account_no depositAccountNo,"
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

        // 审批轨迹 + 调价记录(动作补处理人姓名,与审批详情 flowTrace 同口径)
        result.put("approvalActions", jdbcTemplate.queryForList(
                "SELECT aa.*, u.nick_name operatorName FROM ccr_approval_action aa"
                        + " JOIN ccr_pricing_item pi ON pi.id = aa.pricing_item_id"
                        + " LEFT JOIN ccr_sys_user u ON u.id = aa.operator_id"
                        + " WHERE pi.application_id = ? AND aa.del_flag = '0' ORDER BY aa.operation_time", applicationId));
        result.put("rateAdjustments", jdbcTemplate.queryForList(
                "SELECT ra.* FROM ccr_rate_adjustment ra JOIN ccr_pricing_item pi ON pi.id = ra.pricing_item_id WHERE pi.application_id = ? AND ra.del_flag = '0' ORDER BY ra.operation_time", applicationId));

        // 表决汇总(只到计票结果粒度,不返回票据明细,保持委员匿名)
        // 保密性(§12.7/T4-02/T4-10):表决统计(轮次/计票/行长决策)仅行长·审计·超管可见;
        // 审批过程与档案对委员/审批人隐藏票数与表决进度,后台自动计票,只有行长能查汇总
        String roleCode = user.getRoleCode();
        boolean voteVisible = CurrentLoginUser.ROLE_PRESIDENT.equals(roleCode)
                || CurrentLoginUser.ROLE_AUDITOR.equals(roleCode)
                || CurrentLoginUser.ROLE_ADMIN.equals(roleCode);
        if (voteVisible) {
            result.put("voteRounds", jdbcTemplate.queryForList(
                    "SELECT id, round_no roundNo, round_name roundName, status, voter_count voterCount, required_count requiredCount, round_start_time roundStartTime, round_end_time roundEndTime FROM ccr_vote_round WHERE application_id = ? AND del_flag = '0' ORDER BY round_no", applicationId));
            result.put("voteResults", jdbcTemplate.queryForList(
                    "SELECT vr.round_id roundId, vr.pricing_item_id pricingItemId, vr.approve_count approveCount, vr.reject_count rejectCount, vr.result, vr.count_time countTime FROM ccr_vote_result vr JOIN ccr_pricing_item pi ON pi.id = vr.pricing_item_id WHERE pi.application_id = ? AND vr.del_flag = '0'", applicationId));
            result.put("presidentDecisions", jdbcTemplate.queryForList(
                    "SELECT pd.pricing_item_id pricingItemId, pd.decision, pd.opinion, pd.decision_time decisionTime FROM ccr_president_decision pd JOIN ccr_pricing_item pi ON pi.id = pd.pricing_item_id WHERE pi.application_id = ? AND pd.del_flag = '0'", applicationId));
        } else {
            result.put("voteRounds", List.of());
            result.put("voteResults", List.of());
            result.put("presidentDecisions", List.of());
        }

        // 决议 + 执行核验(整单化后决议按申请维度落库、无分项关联,LEFT JOIN + application_id/pricing_item_id 双键兼容,2026-09-02)
        result.put("resolutions", jdbcTemplate.queryForList(
                "SELECT r.id, r.resolution_no resolutionNo, r.pricing_item_id pricingItemId, r.final_rate finalRate, r.effective_from effectiveFrom, r.effective_to effectiveTo, r.decision_source decisionSource, r.status, r.issue_time issueTime FROM ccr_resolution r LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id WHERE (r.application_id = ? OR pi.application_id = ?) AND r.del_flag = '0'", applicationId, applicationId));
        result.put("resolutionExecutions", jdbcTemplate.queryForList(
                "SELECT re.resolution_id resolutionId, re.loan_contract_no loanContractNo, re.supplement_agreement_no supplementAgreementNo, re.execution_rate executionRate, re.execution_status executionStatus, re.reconcile_result reconcileResult, re.reconcile_time reconcileTime FROM ccr_resolution_execution re JOIN ccr_resolution r ON r.id = re.resolution_id LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id WHERE (r.application_id = ? OR pi.application_id = ?) AND re.del_flag = '0'", applicationId, applicationId));

        // 承诺计划 + 指标(整单化后承诺按申请关联决议,LEFT JOIN + 双键兼容)
        result.put("commitmentPlans", jdbcTemplate.queryForList(
                "SELECT cp.id, cp.plan_no planNo, cp.resolution_id resolutionId, cp.scope_type scopeType, cp.status, cp.start_date startDate, cp.end_date endDate FROM ccr_commitment_plan cp JOIN ccr_resolution r ON r.id = cp.resolution_id LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id WHERE (r.application_id = ? OR pi.application_id = ?) AND cp.del_flag = '0'", applicationId, applicationId));
        result.put("commitmentMetrics", jdbcTemplate.queryForList(
                "SELECT cm.plan_id planId, cm.metric_code metricCode, cm.target_type targetType, cm.baseline_value baselineValue, cm.target_value targetValue, cm.unit, cm.metric_scope metricScope FROM ccr_commitment_metric cm JOIN ccr_commitment_plan cp ON cp.id = cm.plan_id JOIN ccr_resolution r ON r.id = cp.resolution_id LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id WHERE (r.application_id = ? OR pi.application_id = ?) AND cm.del_flag = '0'", applicationId, applicationId));
        // 承诺逐期履约评估(按指标期次:实际值/达成率/风险/结果;档案页展示"每一期指标完成情况")
        result.put("commitmentEvaluations", jdbcTemplate.queryForList(
                "SELECT te.plan_id planId, te.metric_id metricId, cm.metric_code metricCode,"
                        + " te.data_dt dataDt, te.actual_value actualValue, te.achievement_ratio achievementRatio,"
                        + " te.risk_level riskLevel, te.result_status resultStatus"
                        + " FROM ccr_tracking_evaluation te"
                        + " JOIN ccr_commitment_metric cm ON cm.id = te.metric_id"
                        + " JOIN ccr_commitment_plan cp ON cp.id = cm.plan_id"
                        + " JOIN ccr_resolution r ON r.id = cp.resolution_id"
                        + " LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id"
                        + " WHERE (r.application_id = ? OR pi.application_id = ?) AND te.del_flag = '0'"
                        + " ORDER BY cm.id, te.data_dt", applicationId, applicationId));

        // 关联人(客户经理申请时实际录入,§12.4④;按关联客户号补全基本信息/授信信息)
        List<Map<String, Object>> relatedPersons = jdbcTemplate.queryForList(
                "SELECT person_name personName, cert_no certNo, cert_type certType, relation_type relationType, related_customer_no relatedCustomerNo FROM ccr_application_related_person WHERE application_id = ? AND del_flag = '0' ORDER BY id", applicationId);
        enrichRelated(relatedPersons);
        result.put("relatedPersons", relatedPersons);

        // 拟达成贡献度(申请承诺指标,按申请关联 §十三 13.2-6;成员级含成员客户号)
        result.put("commitments", jdbcTemplate.queryForList(
                "SELECT metric_code metricCode, target_type targetType, baseline_value baselineValue, target_value targetValue, unit, metric_scope metricScope, member_customer_no memberCustomerNo, commitment_desc commitmentDesc, end_date endDate FROM ccr_application_commitment WHERE application_id = ? AND del_flag = '0' ORDER BY id", applicationId));

        // 申请内容留痕(§14.4 档案完整保留,与审批详情同口径):客户/融资/贡献度(提交快照优先+人工修正)、
        // 授信信息、担保分项、他行融资、申请附件、集团信息、机构达成
        assembleApplicationContent(result, application, applicationId);
        return result;
    }

    // ==================== 档案申请内容留痕组装(§14.4) ====================

    /**
     * 档案申请内容留痕:与审批详情 detail 同口径组装客户/融资/贡献度/授信/担保/他行融资/附件/集团/机构达成。
     * 客户级信息优先读提交冻结快照(保留当时数据),无快照降级数仓实时,再叠加 customer_info_json 人工修正;
     * 授信协议合并 credit_info_json 补录与数仓协议(同号去重)。
     */
    private void assembleApplicationContent(Map<String, Object> result, Map<String, Object> application, Long applicationId) {
        Object custNo = application.get("customer_no");
        String custNoStr = custNo == null ? "" : custNo.toString();
        Object groupNo = application.get("group_no");
        // 集团场景:customer 展示集团本身(集团名 + 集团补录对公要素),不按成员客户号查(集团申请 customer_no 为空)
        boolean groupScene = groupNo != null && StrUtil.isNotBlank(groupNo.toString());
        Object bundleId = application.get("snapshot_bundle_id");
        List<Map<String, Object>> snapshotRecords = bundleId == null ? List.of()
                : jdbcTemplate.queryForList(
                        "SELECT subject_type subjectType, subject_id subjectId, source_data_dt sourceDataDt, core_json coreJson"
                                + " FROM ccr_snapshot_record WHERE bundle_id = ? AND del_flag = '0'", bundleId);
        if (!snapshotRecords.isEmpty()) {
            result.put("source", "SNAPSHOT");
            result.put("snapshotInfo", snapshotInfo(bundleId, snapshotRecords));
            result.put("customer", groupScene
                    ? groupCustomerOf(groupNo.toString(), snapshotRecords, application)
                    : snapshotCustomer(snapshotRecords, custNoStr));
            result.put("financing", snapshotFinancing(snapshotRecords, custNoStr));
            result.put("contribution", snapshotContribution(snapshotRecords, custNoStr));
        } else {
            result.put("source", "REALTIME");
            result.put("customer", groupScene
                    ? groupCustomerOf(groupNo.toString(), List.of(), application)
                    : realtimeCustomer(custNoStr));
            result.put("financing", realtimeFinancing(custNoStr));
            result.put("contribution", realtimeContribution(custNoStr));
        }
        // 关联人贡献度归并:关联人(申请录入)同 metric_code 值加总进主客户贡献度(§关联人贡献度归并)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contributionRows = (List<Map<String, Object>>) result.get("contribution");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relPersons = (List<Map<String, Object>>) result.get("relatedPersons");
        if (contributionRows != null && relPersons != null) {
            // 空客户号关联人按证件号兜底反查数仓主数据补全(展示与归并共用该列表)
            RelatedCustomerResolver.resolveBatch(jdbcTemplate, relPersons);
            Set<String> relatedCustomerNos = new LinkedHashSet<>();
            for (Map<String, Object> rp : relPersons) {
                Object no = rp.get("relatedCustomerNo");
                if (no != null && !no.toString().isBlank()) {
                    relatedCustomerNos.add(no.toString());
                }
            }
            ContributionMerger.mergeRelatedContributions(jdbcTemplate, contributionRows, relatedCustomerNos);
        }
        // 客户信息人工修正(ccr_application.customer_info_json):数仓带出后人工调整/新增客户手工录入,档案保留人工值
        applyCustomerOverride(result, application);
        // 授信信息(credit_info_json 补录 + 数仓协议合并去重)
        result.put("creditAgreements", mergeCreditAgreements(application, custNoStr));
        // 担保分项明细(按分项挂载,前端定价分项表内嵌展示)
        result.put("guaranteesByItem", guaranteesByItem(applicationId));
        // 他行融资(申请人工补录/Excel 导入 + 数仓征信,最新批次;报告日期=数仓征信报告日期,§2026-08-26)
        result.put("otherLoanSummary", jdbcTemplate.queryForList(
                "SELECT f.lender_count lenderCount, f.npl_balance nplBalance, f.credit_amount_total creditAmountTotal, f.used_amount_total usedAmountTotal, f.loan_account_count loanAccountCount, f.overdue_account_count overdueAccountCount, f.overdue_balance overdueBalance, f.special_mention_balance specialMentionBalance, f.external_guarantee_balance externalGuaranteeBalance, (SELECT r.report_date FROM dw_credit_report_snapshot r WHERE r.cust_no = f.cust_no ORDER BY r.data_dt DESC, r.report_date DESC LIMIT 1) reportDate FROM dw_credit_financing_summary f WHERE f.cust_no = ? ORDER BY f.data_dt DESC LIMIT 1", custNoStr));
        result.put("otherLoans", jdbcTemplate.queryForList(
                "SELECT lender_name lenderName, credit_amount creditAmount, used_amount usedAmount, balance_amount balanceAmount, annual_rate annualRate, data_dt dataDt, 'DW' inputMode FROM dw_credit_financing_detail WHERE customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_financing_detail WHERE customer_no = ?)", custNoStr, custNoStr));
        result.put("appOtherLoans", jdbcTemplate.queryForList(
                "SELECT lender_name lenderName, credit_amount creditAmount, used_amount usedAmount, balance_amount balanceAmount, annual_rate annualRate, input_mode inputMode FROM ccr_application_other_loan WHERE application_id = ? AND del_flag = '0' ORDER BY id", applicationId));
        // 申请附件(材料附件步骤上传;元数据,下载走 /ccr/applications/{appId}/attachments/{id}/download)
        result.put("attachments", jdbcTemplate.queryForList(
                "SELECT id, file_name fileName, file_size fileSize, source_type sourceType, source_resolution_no sourceResolutionNo, create_time createTime FROM ccr_application_attachment WHERE application_id = ? AND del_flag = '0' ORDER BY id", applicationId));
        // 集团信息(集团授信总额/到期日 + 集团贡献度,仅集团场景)
        if (groupNo != null && StrUtil.isNotBlank(groupNo.toString())) {
            String gno = groupNo.toString();
            result.put("groupCredit", jdbcTemplate.queryForList(
                    "SELECT approved_total_amount approvedTotalAmount, allocated_amount allocatedAmount, used_amount usedAmount, available_amount availableAmount, credit_start creditStart, credit_end creditEnd, credit_status creditStatus FROM dw_group_credit_snapshot WHERE group_no = ? ORDER BY data_dt DESC LIMIT 1", gno));
            result.put("groupContribution", jdbcTemplate.queryForList(
                    "SELECT metric_value metricValue, value_type valueType FROM dw_contribution_metric"
                            + " WHERE cust_no = ? AND metric_code = 'TOTAL' AND metric_scope = 'GROUP'"
                            + " AND data_dt = (SELECT MAX(data_dt) FROM dw_contribution_metric"
                            + " WHERE cust_no = ? AND metric_code = 'TOTAL' AND metric_scope = 'GROUP')",
                    gno, gno));
        } else {
            result.put("groupCredit", List.of());
            result.put("groupContribution", List.of());
        }
        // 机构达成(申请机构最新批次;§2026-08-26 存款申请无机构达成概念,置空不组装)
        result.put("orgPerformance", "DEPOSIT".equals(application.get("business_type"))
                ? List.of() : orgPerformance(applicationId));
    }

    /** 快照信息(bundle_no/freeze_time/数据日期,数据日期=记录最大 source_data_dt) */
    private Map<String, Object> snapshotInfo(Object bundleId, List<Map<String, Object>> records) {
        Map<String, Object> info = new LinkedHashMap<>();
        List<Map<String, Object>> bundles = jdbcTemplate.queryForList(
                "SELECT bundle_no bundleNo, freeze_time freezeTime FROM ccr_snapshot_bundle WHERE id = ?", bundleId);
        if (!bundles.isEmpty()) {
            info.put("bundleNo", bundles.get(0).get("bundleNo"));
            info.put("freezeTime", bundles.get(0).get("freezeTime"));
        }
        records.stream().map(r -> r.get("sourceDataDt")).filter(Objects::nonNull)
                .map(Object::toString).max(String::compareTo)
                .ifPresent(dt -> info.put("dataDt", dt));
        return info;
    }

    /** 快照内客户基本信息(CORPORATE/INDIVIDUAL 记录 core_json,字段与实时降级口径一致) */
    private List<Map<String, Object>> snapshotCustomer(List<Map<String, Object>> records, String custNo) {
        Map<String, Object> corp = null;
        Map<String, Object> indv = null;
        for (Map<String, Object> record : records) {
            if (!custNo.equals(record.get("subjectId"))) {
                continue;
            }
            if ("CORPORATE".equals(record.get("subjectType"))) {
                corp = coreOf(record);
            } else if ("INDIVIDUAL".equals(record.get("subjectType"))) {
                indv = coreOf(record);
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        if (corp != null) {
            row.put("customerNo", jsonSafe(corp.get("cust_no")));
            row.put("customerName", jsonSafe(corp.get("cust_name")));
            row.put("certNo", jsonSafe(corp.get("cert_no")));
            row.put("entpCharic", jsonSafe(corp.get("entp_charic")));
            row.put("entpScale", jsonSafe(corp.get("entp_scale")));
            row.put("industry", jsonSafe(corp.get("blgd_idsty")));
            row.put("creditLevel", jsonSafe(corp.get("crdt_grd")));
            row.put("fiveLevelClass", jsonSafe(corp.get("ffthlv_class")));
            row.put("empeNum", jsonSafe(corp.get("entp_empe_num")));
            row.put("totalAssets", jsonSafe(corp.get("rest_asts")));
            row.put("registeredCapital", jsonSafe(corp.get("reg_cap")));
            row.put("estbDate", snapshotDate(jsonSafe(corp.get("estp_estb_dt"))));
            row.put("restAddr", jsonSafe(corp.get("rest_addr")));
            row.put("openOrgName", jsonSafe(corp.get("openact_org_nm")));
            row.put("openDate", jsonSafe(corp.get("openact_dt")));
            row.put("customerClass", jsonSafe(corp.get("cust_class")));
            row.put("custType", "CORP");
            row.put("dataSource", jsonSafe(corp.get("data_source")));
            return List.of(row);
        }
        if (indv != null) {
            row.put("customerNo", jsonSafe(indv.get("cust_no")));
            row.put("customerName", jsonSafe(indv.get("cust_nm")));
            row.put("certType", jsonSafe(indv.get("cert_tp")));
            row.put("certNo", jsonSafe(indv.get("cert_no")));
            row.put("gender", jsonSafe(indv.get("gnd")));
            row.put("occupation", jsonSafe(indv.get("ocupn")));
            row.put("annualIncome", jsonSafe(indv.get("whlyr_incm")));
            row.put("maritalStatus", jsonSafe(indv.get("mrrg_sittn")));
            row.put("address", jsonSafe(indv.get("rsd_addr")));
            row.put("phone", jsonSafe(indv.get("tel_no")));
            row.put("openOrgName", jsonSafe(indv.get("opnact_org_nm")));
            row.put("openDate", jsonSafe(indv.get("opnact_dt")));
            row.put("fiveLevelClass", jsonSafe(indv.get("ffthlv_class")));
            row.put("customerClass", jsonSafe(indv.get("cust_class")));
            row.put("custType", "INDIV");
            row.put("dataSource", jsonSafe(indv.get("data_source")));
            return List.of(row);
        }
        return List.of();
    }

    /** 快照日期归一:数仓 DATE 列被快照冻结为 epoch 毫秒(数字或13位数字串),统一转 yyyy-MM-dd。
     *  字符串日期(如 openact_dt)原样返回;非日期值原样返回。 */
    private static Object snapshotDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n && n.longValue() > 0) {
            return DateUtil.format(DateUtil.date(n.longValue()), "yyyy-MM-dd");
        }
        String s = v.toString().trim();
        if (s.matches("\\d{13}")) {
            return DateUtil.format(DateUtil.date(Long.parseLong(s)), "yyyy-MM-dd");
        }
        return v;
    }

    /** 快照内本行融资(CONTRACT/FINANCING 记录,按借款客户过滤,core_json 字段归一) */
    private List<Map<String, Object>> snapshotFinancing(List<Map<String, Object>> records, String custNo) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Object subjectType = record.get("subjectType");
            if (!"FINANCING".equals(subjectType) && !"CONTRACT".equals(subjectType)) {
                continue;
            }
            Map<String, Object> core = coreOf(record);
            Object coreCust = jsonSafe(core.get("borrower_customer_no"));
            if (coreCust == null) {
                coreCust = jsonSafe(core.get("cust_no"));
            }
            if (!custNo.equals(coreCust)) {
                continue;
            }
            Object loanBalance = jsonSafe(core.get("loan_balance"));
            if (loanBalance == null) {
                loanBalance = jsonSafe(core.get("contract_balance"));
            }
            Object contractRate = jsonSafe(core.get("contract_rate"));
            if (contractRate == null) {
                contractRate = jsonSafe(core.get("execution_rate"));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contractNo", jsonSafe(core.get("contract_no")));
            row.put("agreementNo", jsonSafe(core.get("agreement_no")));
            row.put("trancheNo", jsonSafe(core.get("tranche_no")));
            row.put("contractAmount", jsonSafe(core.get("contract_amount")));
            row.put("loanBalance", loanBalance);
            row.put("contractRate", contractRate);
            row.put("rateType", jsonSafe(core.get("rate_type")));
            row.put("lprTerm", jsonSafe(core.get("lpr_term")));
            row.put("startDate", jsonSafe(core.get("start_date")));
            row.put("maturityDate", jsonSafe(core.get("maturity_date")));
            row.put("contractStatus", jsonSafe(core.get("contract_status")));
            row.put("currency", jsonSafe(core.get("currency")));
            row.put("guaranteeType", jsonSafe(core.get("guarantee_type")));
            rows.add(row);
        }
        return rows;
    }

    /** 快照内贡献度(CONTRIBUTION 记录 core_json.metrics 数组) */
    private List<Map<String, Object>> snapshotContribution(List<Map<String, Object>> records, String custNo) {
        for (Map<String, Object> record : records) {
            if (!"CONTRIBUTION".equals(record.get("subjectType")) || !custNo.equals(record.get("subjectId"))) {
                continue;
            }
            Object metrics = coreOf(record).get("metrics");
            if (!(metrics instanceof List<?> list)) {
                return List.of();
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object m : list) {
                if (!(m instanceof Map<?, ?> metric)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("metricCode", jsonSafe(metric.get("metric_code")));
                row.put("metricName", jsonSafe(metric.get("metric_name")));
                row.put("metricValue", jsonSafe(metric.get("metric_value")));
                row.put("valueType", jsonSafe(metric.get("value_type")));
                rows.add(row);
            }
            return rows;
        }
        return List.of();
    }

    /** 解析快照 core_json(JSON 列查询结果为字符串) */
    @SuppressWarnings("unchecked")
    private Map<String, Object> coreOf(Map<String, Object> record) {
        Object coreJson = record.get("coreJson");
        if (coreJson == null) {
            return Map.of();
        }
        if (coreJson instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return JSONUtil.parseObj(coreJson.toString());
    }

    /**
     * 集团场景客户信息行:集团申请 customer_no 为空,客户信息展示集团本身。
     * 数据源优先级:提交快照 GROUP 记录(集团主数据 core_json,含 group_name/group_type/group_status)
     * → 数仓实时集团主数据 → 申请上下文 group_info_json(新增集团补录;补录对公要素仅此来源) → 集团号兜底。
     * 键名对齐前端对公模板(customerName/certNo/fiveLevelClass/creditLevel/industry/registeredCapital/openOrgName/openDate/basicAccount)。
     */
    private List<Map<String, Object>> groupCustomerOf(String groupNo, List<Map<String, Object>> snapshotRecords,
                                                      Map<String, Object> application) {
        String groupName = null, groupType = null, groupStatus = null, groupUcrCode = null;
        for (Map<String, Object> record : snapshotRecords) {
            if (!groupNo.equals(record.get("subjectId")) || !"GROUP".equals(record.get("subjectType"))) {
                continue;
            }
            Map<String, Object> core = coreOf(record);
            groupName = jsonSafe(core.get("group_name")) == null ? null : String.valueOf(core.get("group_name"));
            groupType = jsonSafe(core.get("group_type")) == null ? null : String.valueOf(core.get("group_type"));
            groupStatus = jsonSafe(core.get("group_status")) == null ? null : String.valueOf(core.get("group_status"));
            groupUcrCode = jsonSafe(core.get("ucr_code")) == null ? null : String.valueOf(core.get("ucr_code"));
            break;
        }
        if (groupName == null) {
            Map<String, Object> dw = dataWarehouseService.findGroup(groupNo);
            if (dw != null) {
                groupName = dw.get("group_name") == null ? null : String.valueOf(dw.get("group_name"));
                groupType = dw.get("group_type") == null ? null : String.valueOf(dw.get("group_type"));
                groupStatus = dw.get("group_status") == null ? null : String.valueOf(dw.get("group_status"));
                groupUcrCode = dw.get("ucr_code") == null ? null : String.valueOf(dw.get("ucr_code"));
            }
        }
        cn.hutool.json.JSONObject gi = null;
        Object gij = application == null ? null : application.get("group_info_json");
        if (gij != null && StrUtil.isNotBlank(gij.toString())) {
            try {
                gi = JSONUtil.parseObj(gij.toString());
            } catch (Exception ignore) {
                // 补录 JSON 非法时忽略,集团名仍可来自快照/数仓
            }
        }
        if (groupName == null && gi != null) {
            groupName = gi.getStr("groupName");
        }
        if (groupName == null) {
            groupName = "集团-" + groupNo;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customerNo", groupNo);
        row.put("customerName", groupName);
        row.put("certType", "USCC");
        // 集团统一社会信用代码:补录(新增集团/人工)值优先,存量集团回退快照/数仓 ucr_code(2026-09-03 修复「申请页有/审批无」)
        String certNo = groupUcrCode;
        if (gi != null) {
            if (StrUtil.isNotBlank(gi.getStr("ucrCode"))) {
                certNo = gi.getStr("ucrCode");
            }
            row.put("fiveLevelClass", gi.getStr("fiveLevelClass"));
            row.put("creditLevel", gi.getStr("creditLevel"));
            row.put("industry", gi.getStr("industry"));
            row.put("registeredCapital", jsonSafe(gi.get("registeredCapital")));
            row.put("openOrgName", gi.getStr("openOrg"));
            row.put("openDate", gi.getStr("openDate"));
            row.put("basicAccount", gi.getStr("basicAccount"));
            row.put("currency", gi.getStr("currency"));
            row.put("applyAmount", jsonSafe(gi.get("applyAmount")));
        }
        row.put("groupNo", groupNo);
        row.put("groupName", groupName);
        row.put("groupType", groupType == null ? "INDUSTRY_GROUP" : groupType);
        row.put("groupStatus", groupStatus);
        row.put("custType", "CORP");
        if (certNo != null) {
            row.put("certNo", certNo);
        }
        return List.of(row);
    }

    /** 档案集团成员信息补充(成员行 snake_case + 对公模板驼峰键):成员名称 + 完整对公要素。
     * 名称/对公要素优先级一致:快照数仓成员主数据(CORPORATE core_json) → 申请补录 group_info_json.supplementMembers(手工成员) → 实时数仓降级。
     * 输出键对齐前端对公模板(certNo/fiveLevelClass/creditLevel/industry/registeredCapital/openOrgName/openDate/basicAccount)。 */
    private void enrichArchiveMemberNames(List<Map<String, Object>> members, Map<String, Object> application) {
        if (members.isEmpty()) {
            return;
        }
        Map<String, String> nameByNo = new HashMap<>();
        Map<String, Map<String, Object>> corpCoreByNo = new HashMap<>();
        Object bundleId = application == null ? null : application.get("snapshot_bundle_id");
        if (bundleId != null) {
            List<Map<String, Object>> records = jdbcTemplate.queryForList(
                    "SELECT subject_type subjectType, subject_id subjectId, core_json coreJson"
                            + " FROM ccr_snapshot_record WHERE bundle_id = ? AND del_flag = '0'", bundleId);
            for (Map<String, Object> record : records) {
                Object sid = record.get("subjectId");
                if (sid == null) {
                    continue;
                }
                String no = sid.toString();
                Map<String, Object> core = coreOf(record);
                if ("CORPORATE".equals(record.get("subjectType"))) {
                    if (jsonSafe(core.get("cust_name")) != null) {
                        nameByNo.putIfAbsent(no, String.valueOf(core.get("cust_name")));
                    }
                    corpCoreByNo.putIfAbsent(no, core);
                } else if ("MEMBER".equals(record.get("subjectType")) && jsonSafe(core.get("member_name")) != null) {
                    nameByNo.putIfAbsent(no, String.valueOf(core.get("member_name")));
                }
            }
        }
        // 手工成员对公要素仅存在于申请上下文 group_info_json.supplementMembers(未落业务表)
        Map<String, cn.hutool.json.JSONObject> manualByNo = new HashMap<>();
        Object gij = application == null ? null : application.get("group_info_json");
        if (gij != null && StrUtil.isNotBlank(gij.toString())) {
            try {
                cn.hutool.json.JSONObject gi = JSONUtil.parseObj(gij.toString());
                Object supp = gi.get("supplementMembers");
                if (supp instanceof cn.hutool.json.JSONArray arr) {
                    for (Object item : arr) {
                        if (!(item instanceof cn.hutool.json.JSONObject m)) {
                            continue;
                        }
                        String no = m.getStr("memberCustomerNo");
                        if (no == null) {
                            continue;
                        }
                        manualByNo.put(no, m);
                        if (m.getStr("memberName") != null) {
                            nameByNo.putIfAbsent(no, m.getStr("memberName"));
                        }
                    }
                }
            } catch (Exception ignore) {
                // 补录 JSON 非法时忽略,成员名称仍可来自快照/实时数仓
            }
        }
        for (Map<String, Object> member : members) {
            Object mno = member.get("member_customer_no");
            if (mno == null) {
                continue;
            }
            String no = mno.toString();
            String name = nameByNo.get(no);
            if (name == null) {
                name = realtimeMemberName(no);
            }
            member.put("member_name", name);
            Map<String, Object> corp = corpCoreByNo.get(no);
            if (corp != null) {
                applyCorpMember(member, corp);
            } else {
                cn.hutool.json.JSONObject manual = manualByNo.get(no);
                if (manual != null) {
                    applyManualMember(member, manual);
                } else {
                    applyRealtimeMember(member, no);
                }
            }
        }
    }

    /** 数仓成员对公要素(快照 CORPORATE core_json)映射到前端对公模板键 */
    private void applyCorpMember(Map<String, Object> member, Map<String, Object> core) {
        member.put("certNo", jsonSafe(core.get("cert_no")));
        member.put("certType", jsonSafe(core.get("cert_tp")));
        member.put("fiveLevelClass", jsonSafe(core.get("ffthlv_class")));
        member.put("creditLevel", jsonSafe(core.get("crdt_grd")));
        member.put("industry", jsonSafe(core.get("blgd_idsty")));
        member.put("registeredCapital", jsonSafe(core.get("rest_asts")));
        member.put("openOrgName", jsonSafe(core.get("openact_org_nm")));
        member.put("openDate", snapshotDate(jsonSafe(core.get("openact_dt"))));
        member.put("basicAccount", jsonSafe(core.get("basic_account_no")));
        member.put("customerClass", jsonSafe(core.get("cust_class")));
        member.put("empeNum", jsonSafe(core.get("entp_empe_num")));
        member.put("estbDate", snapshotDate(jsonSafe(core.get("estp_estb_dt"))));
        member.put("restAddr", jsonSafe(core.get("rest_addr")));
    }

    /** 手工成员对公要素(申请补录 group_info_json.supplementMembers)映射到前端对公模板键;证件类型统一 USCC */
    private void applyManualMember(Map<String, Object> member, cn.hutool.json.JSONObject m) {
        member.put("certNo", m.getStr("ucrCode"));
        member.put("certType", "USCC");
        member.put("fiveLevelClass", m.getStr("fiveLevelClass"));
        member.put("creditLevel", m.getStr("creditLevel"));
        member.put("industry", m.getStr("industry"));
        member.put("registeredCapital", jsonSafe(m.get("registeredCapital")));
        member.put("openOrgName", m.getStr("openOrg"));
        member.put("openDate", m.getStr("openDate"));
        member.put("basicAccount", m.getStr("basicAccount"));
    }

    /** 成员对公要素实时降级:数仓对公客户主数据(快照/补录均缺失时兜底) */
    private void applyRealtimeMember(Map<String, Object> member, String memberNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT cert_no certNo, cert_tp certType, entp_charic entpCharic, entp_scale entpScale,"
                        + " blgd_idsty industry, crdt_grd creditLevel, ffthlv_class fiveLevelClass,"
                        + " rest_asts registeredCapital, openact_org_nm openOrgName, openact_dt openDate,"
                        + " basic_account_no basicAccount, cust_class customerClass, entp_empe_num empeNum,"
                        + " estp_estb_dt estbDate, rest_addr restAddr"
                        + " FROM caps_corp_cust_basic_info WHERE cust_no = ? LIMIT 1", memberNo);
        if (!rows.isEmpty()) {
            member.putAll(rows.get(0));
        }
    }

    /** 成员名称实时降级:数仓对公主数据 → 对私主数据 → 手工成员表(补录成员名) */
    private String realtimeMemberName(String memberNo) {
        List<Map<String, Object>> corp = jdbcTemplate.queryForList(
                "SELECT cust_name FROM caps_corp_cust_basic_info WHERE cust_no = ? LIMIT 1", memberNo);
        if (!corp.isEmpty() && corp.get(0).get("cust_name") != null) {
            return String.valueOf(corp.get(0).get("cust_name"));
        }
        List<Map<String, Object>> indv = jdbcTemplate.queryForList(
                "SELECT cust_nm FROM caps_indv_cust_basic_info WHERE cust_no = ? LIMIT 1", memberNo);
        if (!indv.isEmpty() && indv.get(0).get("cust_nm") != null) {
            return String.valueOf(indv.get(0).get("cust_nm"));
        }
        List<Map<String, Object>> manual = jdbcTemplate.queryForList(
                "SELECT member_name FROM ccr_group_member WHERE member_customer_no = ? AND del_flag = '0' LIMIT 1", memberNo);
        if (!manual.isEmpty() && manual.get(0).get("member_name") != null) {
            return String.valueOf(manual.get(0).get("member_name"));
        }
        return null;
    }

    /** Hutool JSON 解析 null 值为 JSONNull 包装对象,统一转 Java null */
    private static Object jsonSafe(Object v) {
        return (v instanceof cn.hutool.json.JSONNull) ? null : v;
    }

    /** 降级:数仓实时客户基本信息(对公/对私) */
    private List<Map<String, Object>> realtimeCustomer(String custNo) {
        if (StrUtil.isBlank(custNo)) {
            return List.of();
        }
        List<Map<String, Object>> corp = jdbcTemplate.queryForList(
                "SELECT cust_no customerNo, cust_name customerName, cert_no certNo, entp_charic entpCharic, entp_scale entpScale,"
                        + " blgd_idsty industry, crdt_grd creditLevel, ffthlv_class fiveLevelClass, entp_empe_num empeNum,"
                        + " rest_asts totalAssets, estp_estb_dt estbDate, rest_addr restAddr, openact_org_nm openOrgName,"
                        + " openact_dt openDate, cust_class customerClass, 'CORP' custType"
                        + " FROM caps_corp_cust_basic_info WHERE cust_no = ? LIMIT 1", custNo);
        if (!corp.isEmpty()) {
            return corp;
        }
        return jdbcTemplate.queryForList(
                "SELECT cust_no customerNo, cust_nm customerName, cert_tp certType, cert_no certNo,"
                        + " gnd gender, ocupn occupation, whlyr_incm annualIncome, mrrg_sittn maritalStatus, rsd_addr address,"
                        + " tel_no phone, opnact_org_nm openOrgName, opnact_dt openDate, ffthlv_class fiveLevelClass,"
                        + " cust_class customerClass, 'INDIV' custType"
                        + " FROM caps_indv_cust_basic_info WHERE cust_no = ? LIMIT 1", custNo);
    }

    /** 降级:数仓实时本行融资(贷款合同最新快照) */
    private List<Map<String, Object>> realtimeFinancing(String custNo) {
        if (StrUtil.isBlank(custNo)) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
                "SELECT contract_no contractNo, agreement_no agreementNo, tranche_no trancheNo, borrower_customer_no borrowerCustomerNo,"
                        + " contract_amount contractAmount, contract_balance loanBalance, guarantee_type guaranteeType, currency,"
                        + " execution_rate contractRate, rate_type rateType, lpr_term lprTerm, start_date startDate,"
                        + " maturity_date maturityDate, contract_status contractStatus, contract_version contractVersion"
                        + " FROM dw_loan_contract_snapshot WHERE borrower_customer_no = ?", custNo);
    }

    /** 降级:数仓实时贡献度 */
    private List<Map<String, Object>> realtimeContribution(String custNo) {
        if (StrUtil.isBlank(custNo)) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
                "SELECT metric_code metricCode, metric_name metricName, metric_value metricValue, value_type valueType FROM dw_contribution_metric WHERE cust_no = ?", custNo);
    }

    /** 客户信息人工修正(customer_info_json):人工值覆盖基线,新增客户(无基线)即唯一来源 */
    private void applyCustomerOverride(Map<String, Object> result, Map<String, Object> application) {
        Object ciObj = application.get("customer_info_json");
        if (ciObj == null || ciObj.toString().isBlank()) {
            return;
        }
        JSONObject manual;
        try {
            manual = JSONUtil.parseObj(ciObj.toString());
        } catch (Exception e) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> custList = (List<Map<String, Object>>) result.get("customer");
        if (custList == null) {
            custList = new ArrayList<>();
        }
        boolean manualOnly = custList.isEmpty()
                || "MANUAL".equals(custList.get(0).get("dataSource"));
        Map<String, Object> row = manualOnly ? new LinkedHashMap<>() : custList.get(0);
        overwriteCustomer(row, manual, "customerNo", "customerNo", false);
        overwriteCustomer(row, manual, "customerName", "customerName", false);
        if ("INDV".equals(manual.getStr("custType"))) {
            overwriteCustomer(row, manual, "idNo", "certNo", true);
        } else {
            overwriteCustomer(row, manual, "ucrCode", "certNo", true);
        }
        overwriteCustomer(row, manual, "idType", "certType", true);
        overwriteCustomer(row, manual, "fiveLevelClass", "fiveLevelClass", true);
        overwriteCustomer(row, manual, "creditLevel", "creditLevel", true);
        overwriteCustomer(row, manual, "industry", "industry", true);
        overwriteCustomer(row, manual, "entpCharic", "entpCharic", true);
        overwriteCustomer(row, manual, "registeredCapital", "registeredCapital", true);
        overwriteCustomer(row, manual, "occupation", "occupation", true);
        overwriteCustomer(row, manual, "annualIncome", "annualIncome", true);
        overwriteCustomer(row, manual, "maritalStatus", "maritalStatus", true);
        overwriteCustomer(row, manual, "phone", "phone", true);
        overwriteCustomer(row, manual, "openOrg", "openOrgName", true);
        overwriteCustomer(row, manual, "openDate", "openDate", true);
        overwriteCustomer(row, manual, "basicAccount", "basicAccount", true);
        if (manualOnly) {
            row.put("custType", "CORP".equals(manual.getStr("custType")) ? "CORP" : "INDIV");
            row.put("source", "MANUAL");
            if (custList.isEmpty()) {
                custList.add(row);
            }
            result.put("customer", custList);
            result.put("source", "MANUAL");
        } else {
            row.put("source", "MANUAL_OVERRIDE");
            result.put("source", "MANUAL_OVERRIDE");
        }
    }

    /** 单字段人工修正覆盖(allowBlank=false 仅非空覆盖;true 含空覆盖,以人工为准) */
    private void overwriteCustomer(Map<String, Object> row, JSONObject manual, String srcKey, String targetKey, boolean allowBlank) {
        Object v = jsonSafe(manual.get(srcKey));
        if (v == null) {
            return;
        }
        if (allowBlank || StrUtil.isNotBlank(v.toString())) {
            row.put(targetKey, v);
        }
    }

    /** 授信协议合并:credit_info_json 补录(来源 APPLICATION)在前 + 数仓协议(同号去重);补录协议号可空时不去重 */
    private List<Map<String, Object>> mergeCreditAgreements(Map<String, Object> application, String custNo) {
        List<Map<String, Object>> agreements = jdbcTemplate.queryForList(
                "SELECT agreement_no agreementNo, agreement_type agreementType, credit_amount creditAmount, used_amount usedAmount, available_amount availableAmount, currency, start_date startDate, end_date endDate, agreement_status agreementStatus FROM dw_credit_agreement_snapshot WHERE customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_agreement_snapshot WHERE customer_no = ?) ORDER BY agreement_no", custNo, custNo);
        Object creditInfoJson = application.get("credit_info_json");
        if (creditInfoJson == null || creditInfoJson.toString().isBlank()) {
            return agreements;
        }
        JSONObject ci;
        try {
            ci = JSONUtil.parseObj(creditInfoJson.toString());
        } catch (Exception e) {
            return agreements;
        }
        Map<String, Object> manual = new LinkedHashMap<>();
        manual.put("agreementNo", ci.getStr("agreementNo"));
        manual.put("agreementType", ci.getStr("agreementType"));
        manual.put("creditAmount", jsonSafe(ci.get("creditAmount")));
        manual.put("usedAmount", jsonSafe(ci.get("usedAmount")));
        manual.put("availableAmount", jsonSafe(ci.get("availableAmount")));
        manual.put("currency", ci.getStr("currency"));
        manual.put("startDate", ci.getStr("startDate"));
        manual.put("endDate", ci.getStr("endDate"));
        manual.put("agreementStatus", ci.getStr("agreementStatus"));
        manual.put("source", "APPLICATION");
        List<Map<String, Object>> merged = new ArrayList<>();
        // 空壳补录(除 currency 外协议要素全空)不展示,避免档案授信区出现整行"--"(仅业务类型/币种等路由字段落库)
        if (!isShellAgreement(manual)) {
            merged.add(manual);
        }
        String manualNo = manual.get("agreementNo") == null ? null : manual.get("agreementNo").toString();
        for (Map<String, Object> row : agreements) {
            Object no = row.get("agreementNo");
            String rowNo = no == null ? null : no.toString();
            if (manualNo == null || manualNo.isEmpty()) {
                merged.add(row);
            } else if (!manualNo.equals(rowNo)) {
                merged.add(row);
            }
        }
        return merged;
    }

    /** 空壳补录判定:补录行除 currency 外协议要素(编号/类型/额度/起止/状态)全空,视为无实质内容(仅路由字段落库),不展示 */
    private boolean isShellAgreement(Map<String, Object> manual) {
        for (String k : new String[]{"agreementNo", "agreementType", "creditAmount", "usedAmount",
                "availableAmount", "startDate", "endDate", "agreementStatus"}) {
            Object v = manual.get(k);
            if (v != null && !v.toString().isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** 担保分项明细:同申请全部分项的担保包与措施,按 pricing_item_id 聚合 */
    private Map<Object, List<Map<String, Object>>> guaranteesByItem(Long applicationId) {
        // gm.del_flag='0' 必须放 JOIN ON 而非 WHERE:担保包必有主担保方式(main_guarantee_type),
        // 措施表可能为空(有包无措施),WHERE 过滤会整行丢失导致决议书/档案担保方式为空(2026-09-02 修复,与审批详情担保查询口径一致)
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT gp.pricing_item_id pricingItemId, gp.main_guarantee_type guaranteeType, gp.package_version packageVersion,"
                        + " gm.measure_no measureNo, gm.measure_type measureType, gm.guarantee_amount guaranteeAmount, gm.ext_json extJson"
                        + " FROM ccr_guarantee_package gp"
                        + " LEFT JOIN ccr_guarantee_measure gm ON gm.package_id = gp.id AND gm.del_flag = '0'"
                        + " JOIN ccr_pricing_item pi ON pi.id = gp.pricing_item_id"
                        + " WHERE pi.application_id = ? AND gp.del_flag = '0' ORDER BY gp.id, gm.measure_no", applicationId);
        Map<Object, List<Map<String, Object>>> byItem = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byItem.computeIfAbsent(row.get("pricingItemId"), k -> new ArrayList<>()).add(row);
        }
        return byItem;
    }

    /** 机构达成(§12.16,2026-09-04 两版承诺计划合并改造):申请机构 id(v2 track.org_id 同域)→ OrgAchievementAssembler
     *  按 ccr_commitment_track 到期终态聚合达成率(废弃旧表金额口径) */
    private List<Map<String, Object>> orgPerformance(Long appId) {
        if (appId == null) {
            return List.of();
        }
        List<Map<String, Object>> orgIds = jdbcTemplate.queryForList(
                "SELECT a.applicant_org_id orgId FROM ccr_application a WHERE a.id = ? AND a.del_flag = '0'", appId);
        if (orgIds.isEmpty() || orgIds.get(0).get("orgId") == null) {
            return List.of();
        }
        Long orgId = ((Number) orgIds.get(0).get("orgId")).longValue();
        return OrgAchievementAssembler.assemble(jdbcTemplate, orgId);
    }

    /** 关联人信息补全(§12.4④):按 relatedCustomerNo 批量反查基本信息(caps_corp/indv)+授信信息(授信协议数/本行贷款余额) */
    private void enrichRelated(List<Map<String, Object>> persons) {
        if (persons == null || persons.isEmpty()) {
            return;
        }
        Set<String> customerNos = new LinkedHashSet<>();
        for (Map<String, Object> p : persons) {
            Object no = p.get("relatedCustomerNo");
            if (no != null && StrUtil.isNotBlank(no.toString())) {
                customerNos.add(no.toString());
            }
        }
        if (customerNos.isEmpty()) {
            return;
        }
        String in = String.join(",", Collections.nCopies(customerNos.size(), "?"));
        Object[] args = customerNos.toArray();

        // 基本信息:对私优先填充、对公覆盖(与快照客户 CORP 优先口径一致)
        Map<String, Map<String, Object>> basics = new HashMap<>();
        for (Map<String, Object> iv : jdbcTemplate.queryForList(
                "SELECT cust_no custNo, ocupn occupation, whlyr_incm annualIncome FROM caps_indv_cust_basic_info"
                        + " WHERE cust_no IN (" + in + ") AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_indv_cust_basic_info d2 WHERE d2.cust_no = caps_indv_cust_basic_info.cust_no)", args)) {
            basics.put(String.valueOf(iv.get("custNo")), iv);
        }
        for (Map<String, Object> c : jdbcTemplate.queryForList(
                "SELECT cust_no custNo, entp_charic entpCharic, entp_scale entpScale, blgd_idsty industry, crdt_grd creditLevel, ffthlv_class fiveLevelClass"
                        + " FROM caps_corp_cust_basic_info WHERE cust_no IN (" + in + ") AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_corp_cust_basic_info d2 WHERE d2.cust_no = caps_corp_cust_basic_info.cust_no)", args)) {
            basics.put(String.valueOf(c.get("custNo")), c);
        }

        // 授信信息:授信协议数 + 本行贷款余额合计(万元)
        Map<String, Object> agreementCounts = new HashMap<>();
        for (Map<String, Object> a : jdbcTemplate.queryForList(
                "SELECT customer_no customerNo, COUNT(*) cnt FROM dw_credit_agreement_snapshot"
                        + " WHERE customer_no IN (" + in + ") AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_agreement_snapshot)"
                        + " GROUP BY customer_no", args)) {
            agreementCounts.put(String.valueOf(a.get("customerNo")), a.get("cnt"));
        }
        Map<String, Object> loanBalances = new HashMap<>();
        for (Map<String, Object> l : jdbcTemplate.queryForList(
                "SELECT borrower_customer_no customerNo, SUM(contract_balance) balance FROM dw_loan_contract_snapshot"
                        + " WHERE borrower_customer_no IN (" + in + ") AND data_dt = (SELECT MAX(data_dt) FROM dw_loan_contract_snapshot)"
                        + " GROUP BY borrower_customer_no", args)) {
            loanBalances.put(String.valueOf(l.get("customerNo")), l.get("balance"));
        }

        for (Map<String, Object> p : persons) {
            Object no = p.get("relatedCustomerNo");
            if (no == null) {
                continue;
            }
            Map<String, Object> basic = basics.get(no.toString());
            if (basic != null) {
                boolean corp = basic.containsKey("entpCharic");
                p.put("custType", corp ? "CORP" : "INDIV");
                p.put("entpCharic", basic.get("entpCharic"));
                p.put("entpScale", basic.get("entpScale"));
                p.put("industry", basic.get("industry"));
                p.put("creditLevel", basic.get("creditLevel"));
                p.put("fiveLevelClass", basic.get("fiveLevelClass"));
                p.put("occupation", basic.get("occupation"));
                p.put("annualIncome", basic.get("annualIncome"));
            }
            p.put("creditAgreementCount", agreementCounts.get(no.toString()));
            p.put("loanBalanceTotal", loanBalances.get(no.toString()));
        }
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
            // §D16a 部门分流:部门总经理/分管行长按分项 dept_code 解析处理人,其他节点传 null 走原逻辑
            List<Long> assignees = nodeAssigneeResolver.resolveUserIds(nodeCode,
                    appOrg.get(item.getApplicationId()), item.getDeptCode());
            if (!assignees.isEmpty() && !assignees.contains(userId)) {
                continue;
            }
            filtered.add(item);
        }
        return filtered;
    }

    /** 解析分项冻结的完整审批链路(JSON数组);空/异常返回空列表(推进时回退贷款固定链) */
    private List<String> parseRouteChain(String routeChainJson) {
        if (StrUtil.isBlank(routeChainJson)) {
            return List.of();
        }
        try {
            return JSONUtil.parseArray(routeChainJson).toList(String.class);
        } catch (Exception e) {
            log.warn("分项 route_chain 解析失败,回退贷款固定链: {}", routeChainJson);
            return List.of();
        }
    }

    /** 秘书岗过手放行(2026-08-28 用户拍板):SECRETARY 节点上未命中秘书岗条件的分项
     *  (route_chain 不含 SECRETARY)仅过手,不要求秘书岗审批动作,不阻塞齐套 */
    private boolean secretaryPassThrough(CcrPricingItem i, String nodeCode) {
        if (!SECRETARY_NODE.equals(nodeCode)) {
            return false;
        }
        return !parseRouteChain(i.getRouteChain()).contains(SECRETARY_NODE);
    }

    /** 节点审批人配置校验(§5.5.1):配置了有效指派时,仅解析出的处理人可通过/否决 */
    private void guardNodeAssignee(String nodeCode, CcrApplication application, SysUserRead operator,
                                   String deptCode) {
        List<Long> assignees = nodeAssigneeResolver.resolveUserIds(nodeCode,
                application.getApplicantOrgId(), deptCode);
        if (assignees.isEmpty()) {
            // 部门类节点(部门总经理/分管行长)按分项 dept_code 部门归属解析:解析为空说明申请缺少部门归属
            // (如历史申请矩阵漏配冻结 dept_code=NULL),必须拒绝而非角色兜底放行——否则全部门总经理/
            // 分管行长都能越权审批(2026-08-26 串扰根因修复);其余节点保持原角色兜底语义
            if (RouteChains.DEPT_GENERAL_MANAGER.equals(nodeCode)
                    || RouteChains.VICE_PRESIDENT.equals(nodeCode)) {
                throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                        "申请缺少部门归属配置,请联系管理员补全矩阵部门归属后重新提交");
            }
            // 未配置指定审批人:按节点角色校验兜底(§5.5.1)
            currentLoginUser.requireNodeRole(nodeCode);
        } else if (!assignees.contains(operator.getId())) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                    "节点[" + nodeCode + "]已配置指定审批人,当前登录人不在指派范围内");
        }
        // assignees 含当前登录人:指派命中即放行——兼岗节点(如秘书岗=计划财务部总经理兼任,指派 dept_gm)
        // 不再强制节点角色;§D16a 固定机构节点即此形态
    }

    /** 身份与节点校验:小组/行长节点不走普通审批通道;节点角色在 guardNodeAssignee 中按「指派优先」校验 */
    private SysUserRead checkOperatorAndNode(String nodeCode) {
        if (StrUtil.isBlank(nodeCode)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "节点编码必填");
        }
        // 六人小组从普通 approve/reject 路径移除(小组分项只能经表决流转)
        if (RouteChains.SIX_PEOPLE_GROUP.equals(nodeCode) || "PRESIDENT".equals(nodeCode)) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                    "节点[" + nodeCode + "]不属于普通审批通道");
        }
        return currentLoginUser.requireCurrentUser();
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

    /**
     * 整单流转随行分项更新(WHERE id=? AND status='ROUTING',version_no 自增):
     * 随行分项无前端版本号,按整单一致推进;0 行视为并发状态变迁,抛错回滚整单
     */
    private void updateSiblingWholeOrder(CcrPricingItem sibling, String targetNode, String targetStatus,
                                         BigDecimal finalRate, String finalReason) {
        updateSiblingWholeOrder(sibling, targetNode, targetStatus, null, finalRate, finalReason);
    }

    /** 整单流转随行分项更新(带调价:currentApprovalRate 非空时同步保存调整后利率;5 参重载不传调价) */
    private void updateSiblingWholeOrder(CcrPricingItem sibling, String targetNode, String targetStatus,
                                         BigDecimal currentApprovalRate, BigDecimal finalRate, String finalReason) {
        LambdaUpdateWrapper<CcrPricingItem> wrapper = new LambdaUpdateWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getId, sibling.getId())
                .eq(CcrPricingItem::getStatus, PricingItemStatus.ROUTING.getCode())
                .set(CcrPricingItem::getCurrentNodeCode, targetNode)
                .set(CcrPricingItem::getStatus, targetStatus)
                .set(currentApprovalRate != null, CcrPricingItem::getCurrentApprovalRate, currentApprovalRate)
                .set(finalRate != null, CcrPricingItem::getFinalRate, finalRate)
                .set(finalReason != null, CcrPricingItem::getFinalReason, finalReason)
                .setSql("version_no = version_no + 1")
                .set(CcrPricingItem::getUpdateTime, LocalDateTime.now())
                .set(CcrPricingItem::getUpdateBy, currentLoginUser.requireLoginId());
        int rows = pricingItemMapper.update(null, wrapper);
        if (rows == 0) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "分项 " + sibling.getId() + " 状态已变迁,整单流转中止");
        }
    }

    // ==================== 调价后矩阵路由重算(§8.6 用户拍板:改利率流程链路随之调整) ====================

    /**
     * 调价后按调整后利率重算矩阵路由:业务大类/存量新增/客户类型/金额/期限/担保维度与提交时一致,
     * 仅利率用调整后值匹配矩阵行;沿用提交冻结的 LPR 版本与生效日期(§8.4),保证口径与提交预览一致。
     * 跨上会线(如 3.9→3.7 命中小组行)时终审岗位/链路/部门归属随之变化,由调用方 applyReroute 刷新冻结字段。
     */
    private RouteResult recalcRoute(CcrApplication app, CcrPricingItem item, BigDecimal adjustedRate) {
        MatrixRouteInput input = new MatrixRouteInput();
        input.setBusinessBigType(routeBusinessBigType(app));
        input.setNewOrExisting(routeNewOrExisting(app, item));
        input.setCustomerType(routeCustomerType(app, item));
        input.setProductCode(item.getProductCode());
        // 需求:审批链路按总授信额度定档(存量=数仓授信协议金额合计,新增=手工录入;集团=集团综合授信批复总额度优先)
        input.setAmount(routeTotalCredit(app, item));
        input.setAmountBasis(MatrixRouteInput.AMOUNT_BASIS_GROUP_TOTAL_CREDIT);
        input.setGroupCreditTotal(routeGroupCreditTotal(app));
        input.setTermValue(item.getTermValue());
        input.setTermUnit(item.getTermUnit());
        input.setGuaranteeType(routeGuaranteeType(item));
        input.setRequestedRate(adjustedRate);
        input.setOriginalRate(item.getOriginalRate());
        input.setLprVersionId(app.getLprVersionId());
        input.setAsOfDate(app.getRouteAsOfDate());
        // 2026-09-04 综合/零售两级支行:零售申请调价重算同样插管理综合支行长节点、支行层终审上收
        input.setRetailBranch(BranchTypeSupport.isRetailBranch(jdbcTemplate, app.getApplicantOrgId()));
        try {
            return rateMatrixRouter.calcRoute(input);
        } catch (ServiceException e) {
            throw new ServiceException(e.getCode(),
                    "调整后利率 " + adjustedRate + "% 无矩阵路由匹配(" + e.getMessage() + "),请重新确认利率");
        }
    }

    /** 总授信额度(审批链路金额定档口径):优先取申请授信快照 credit_info_json.totalCredit(存量=数仓授信协议金额合计,新增=手工录入);缺省回退分项金额 */
    private BigDecimal routeTotalCredit(CcrApplication app, CcrPricingItem item) {
        String ci = app.getCreditInfoJson();
        if (ci != null && !ci.isBlank()) {
            try {
                BigDecimal tc = JSONUtil.parseObj(ci).getBigDecimal("totalCredit");
                if (tc != null) {
                    return tc;
                }
            } catch (Exception ignored) {
                // 快照解析失败按分项金额回退
            }
        }
        return item.getPricingAmount();
    }

    /** 刷新调价后分项的冻结路由字段(route_code/route_chain/dept_code/boundary/matched_matrix_no),同步 DB 与内存。
     * 用 wrapper 形式更新,避免 updateById 触发 BaseEntity.versionNo 乐观锁自增,
     * 否则后续带版本条件的 updateItemWithStateAndVersion(eq version_no)会 0 行抛版本冲突。 */
    private void applyReroute(CcrPricingItem item, RouteResult nr) {
        item.setRouteCode(nr.getFinalNodeCode());
        item.setRouteChain(JSONUtil.toJsonStr(nr.getRouteChain()));
        item.setDeptCode(nr.getDeptCode());
        item.setBoundaryRate(nr.getBoundaryRate());
        item.setMatchedMatrixNo(nr.getMatchedMatrixNo());
        pricingItemMapper.update(null, new LambdaUpdateWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getId, item.getId())
                .set(CcrPricingItem::getRouteCode, item.getRouteCode())
                .set(CcrPricingItem::getRouteChain, item.getRouteChain())
                .set(CcrPricingItem::getDeptCode, item.getDeptCode())
                .set(CcrPricingItem::getBoundaryRate, item.getBoundaryRate())
                .set(CcrPricingItem::getMatchedMatrixNo, item.getMatchedMatrixNo()));
    }

    /** 调价重算后的推进目标:当前节点在新链 → 下一节点(终点 null);不在新链(链路已变,start 已过) → 从首节点下一站进入 */
    private String nextAfterReroute(String nodeCode, List<String> chain) {
        if (StrUtil.isBlank(nodeCode) || chain == null || chain.isEmpty()) {
            return null;
        }
        int idx = chain.indexOf(nodeCode);
        if (idx >= 0) {
            return idx == chain.size() - 1 ? null : chain.get(idx + 1);
        }
        return chain.size() > 1 ? chain.get(1) : null;
    }

    /** 业务大类:DEPOSIT / LOAN_PERSONAL(对私) / LOAN_PUBLIC(对公),与提交路由口径一致 */
    private String routeBusinessBigType(CcrApplication app) {
        if ("DEPOSIT".equals(app.getBusinessType())) {
            return "DEPOSIT";
        }
        return "INDIVIDUAL".equals(app.getCustomerScope()) ? "LOAN_PERSONAL" : "LOAN_PUBLIC";
    }

    /** 存量/新增判定:优先申请授信快照 businessType(NEW/EXISTING),回退原执行利率非空即存量 */
    private String routeNewOrExisting(CcrApplication app, CcrPricingItem item) {
        if (StrUtil.isNotBlank(app.getCreditInfoJson())) {
            try {
                String bt = JSONUtil.parseObj(app.getCreditInfoJson()).getStr("businessType");
                if ("NEW".equals(bt) || "EXISTING".equals(bt)) {
                    return bt;
                }
            } catch (Exception ignore) {
                // 快照解析失败回退原利率判定
            }
        }
        return item.getOriginalRate() != null ? "EXISTING" : "NEW";
    }

    /** 客户类型:PERSONAL 个人;申请提交的企业性质优先,数仓带出兜底,缺省 NON_SOE */
    private String routeCustomerType(CcrApplication app, CcrPricingItem item) {
        if ("INDIVIDUAL".equals(app.getCustomerScope())) {
            return "PERSONAL";
        }
        // 1. 申请提交的企业性质优先(§2026-08-27 用户拍板,与 ApplicationSubmitServiceImpl.resolveCustomerType 同口径)
        if (StrUtil.isNotBlank(app.getCustomerInfoJson())) {
            try {
                String submitted = JSONUtil.parseObj(app.getCustomerInfoJson()).getStr("entpCharic");
                if ("SOE".equals(submitted) || "NON_SOE".equals(submitted)) {
                    return submitted;
                }
            } catch (Exception ignore) {
                // 快照解析失败回退数仓
            }
        }
        String customerNo = "GROUP".equals(app.getCustomerScope()) ? item.getMemberCustomerNo() : app.getCustomerNo();
        if (StrUtil.isBlank(customerNo)) {
            return "NON_SOE";
        }
        Map<String, Object> corp = dataWarehouseService.findCorpCustomer(customerNo);
        if (corp == null || corp.get("entp_charic") == null) {
            return "NON_SOE";
        }
        return "SOE".equals(String.valueOf(corp.get("entp_charic"))) ? "SOE" : "NON_SOE";
    }

    /** 担保主类型(取分项冻结担保组合) */
    private String routeGuaranteeType(CcrPricingItem item) {
        if (item.getGuaranteePackageId() == null) {
            return null;
        }
        CcrGuaranteePackage pkg = guaranteePackageMapper.selectById(item.getGuaranteePackageId());
        return pkg == null ? null : pkg.getMainGuaranteeType();
    }

    /** 集团定档金额(§B18 路由金额定档基准;非集团返回 null,calcRoute 回退本笔金额):
     *  优先取本次申请额度 group_info_json.applyAmount(集团 NEW=手工录入授信总额/EXISTING=所选授信协议额度,
     *  由申请 serializeGroupInfo 写入)——与提交定档 loadGroupCreditTotal 同口径,保证审批调价重算(recalcRoute)
     *  与申请预览/勾稽一致;空则回退数仓批复总额度(兼容旧申请/草稿) */
    private BigDecimal routeGroupCreditTotal(CcrApplication app) {
        if (!"GROUP".equals(app.getCustomerScope()) || StrUtil.isBlank(app.getGroupNo())) {
            return null;
        }
        if (StrUtil.isNotBlank(app.getGroupInfoJson())) {
            try {
                BigDecimal applyAmount = JSONUtil.parseObj(app.getGroupInfoJson()).getBigDecimal("applyAmount");
                if (applyAmount != null) {
                    return applyAmount;
                }
            } catch (Exception ignore) {
                // 快照解析失败回退数仓批复
            }
        }
        Map<String, Object> credit = dataWarehouseService.findGroupCredit(app.getGroupNo());
        return credit == null ? null : Convert.toBigDecimal(credit.get("approved_total_amount"));
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

    /** 整单多分项幂等键:首个分项落原始请求键(guardIdempotency 请求级重放拦截),其余分项按 id 派生,
     * 避免整单推进多分项时各分项 insertAction 撞 ccr_approval_action.uk_action_idem 唯一键(2026-09-02 修复) */
    private String itemIdempotencyKey(CcrPricingItem item, List<CcrPricingItem> routingItems, String requestKey) {
        if (requestKey == null) {
            return null;
        }
        if (routingItems.isEmpty() || routingItems.get(0).getId().equals(item.getId())) {
            return requestKey;
        }
        return requestKey + ":" + item.getId();
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

    /** 档案数据权限:客户经理看本人申请、审批人/行长看本人审批过、审计(admin)看全部 */
    private void checkHistoryPermission(SysUserRead user, Map<String, Object> application) {
        String role = user.getRoleCode();
        if (CurrentLoginUser.ROLE_ADMIN.equals(role)) {
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

    // ==================== 整单流转工具(整单交付改造 2026-08-29:审批推进以申请单为准) ====================

    /** 历史申请推导源:整单路由字段未冻结(DDL 前提交,current_node_code/dept_code 均空)时取首个在途分项,
     * 用于推导整单当前节点/部门归属;整单字段已冻结返回 null 不触发查询。新旧统一不改存量数据(2026-08-29)。 */
    private CcrPricingItem legacyDerivationSource(CcrApplication application) {
        if (StrUtil.isNotBlank(application.getCurrentNodeCode())
                && StrUtil.isNotBlank(application.getDeptCode())) {
            return null;
        }
        return pricingItemMapper.selectOne(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, application.getId())
                .eq(CcrPricingItem::getStatus, PricingItemStatus.ROUTING.getCode())
                .orderByAsc(CcrPricingItem::getCreateTime)
                .last("limit 1"));
    }

    /**
     * 整单流转批量更新在途分项:一次审批动作对同申请全部在途分项一致推进。
     * WHERE id=? AND status='ROUTING'、version_no 自增(整单模型不做逐分项乐观锁,防重复靠节点动作守卫);
     * approvalRates 非空时同步各分项 current_approval_rate(整单统一调价);
     * 终审(APPROVED_LEVEL)未显式传 finalRate 时回退分项有效利率落 final_rate,否决(REJECTED)不落 final_rate。
     */
    private void updateWholeOrderItems(List<CcrPricingItem> items, String targetNode, String targetStatus,
                                       Map<Long, BigDecimal> approvalRates, BigDecimal finalRate, String finalReason) {
        for (CcrPricingItem i : items) {
            BigDecimal eff = approvalRates == null ? null : approvalRates.get(i.getId());
            BigDecimal finalRateNow;
            if (finalRate != null) {
                finalRateNow = finalRate;
            } else if (PricingItemStatus.APPROVED_LEVEL.getCode().equals(targetStatus)) {
                // 整单终审:最终利率 = 分项有效利率(调价后利率或原当前审批利率)
                finalRateNow = eff != null ? eff : i.getCurrentApprovalRate();
            } else {
                finalRateNow = null;
            }
            LambdaUpdateWrapper<CcrPricingItem> wrapper = new LambdaUpdateWrapper<CcrPricingItem>()
                    .eq(CcrPricingItem::getId, i.getId())
                    .eq(CcrPricingItem::getStatus, PricingItemStatus.ROUTING.getCode())
                    .set(CcrPricingItem::getCurrentNodeCode, targetNode)
                    .set(CcrPricingItem::getStatus, targetStatus)
                    .set(eff != null, CcrPricingItem::getCurrentApprovalRate, eff)
                    .set(finalRateNow != null, CcrPricingItem::getFinalRate, finalRateNow)
                    .set(finalReason != null, CcrPricingItem::getFinalReason, finalReason)
                    .setSql("version_no = version_no + 1")
                    .set(CcrPricingItem::getUpdateTime, LocalDateTime.now())
                    .set(CcrPricingItem::getUpdateBy, currentLoginUser.requireLoginId());
            int rows = pricingItemMapper.update(null, wrapper);
            if (rows == 0) {
                throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                        "分项 " + i.getId() + " 状态已变迁,整单流转中止");
            }
        }
    }

    /** 同步申请单当前节点(status 非空时一并更新,如整单否决置 REJECTED;终态终审聚合走 afterItemTerminal) */
    private void updateApplicationNode(CcrApplication application, String next, String status) {
        LambdaUpdateWrapper<CcrApplication> wrapper = new LambdaUpdateWrapper<CcrApplication>()
                .eq(CcrApplication::getId, application.getId())
                .set(CcrApplication::getCurrentNodeCode, next)
                .set(status != null, CcrApplication::getStatus, status)
                .set(CcrApplication::getUpdateTime, LocalDateTime.now())
                .set(CcrApplication::getUpdateBy, currentLoginUser.requireLoginId());
        applicationMapper.update(null, wrapper);
        application.setCurrentNodeCode(next);
        if (status != null) {
            application.setStatus(status);
        }
    }

    /** 刷新申请单整单路由字段(route_code/route_chain/start_node_code/boundary_rate/matched_matrix_no/dept_code),
     * 同步 DB 与内存。用 wrapper 形式更新,避免 updateById 触发 BaseEntity.versionNo 乐观锁自增。 */
    private void applyRerouteApplication(CcrApplication application, RouteResult nr) {
        application.setRouteCode(nr.getFinalNodeCode());
        application.setRouteChain(JSONUtil.toJsonStr(nr.getRouteChain()));
        application.setStartNodeCode(nr.getStartNodeCode());
        application.setDeptCode(nr.getDeptCode());
        application.setBoundaryRate(nr.getBoundaryRate());
        application.setMatchedMatrixNo(nr.getMatchedMatrixNo());
        applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                .eq(CcrApplication::getId, application.getId())
                .set(CcrApplication::getRouteCode, application.getRouteCode())
                .set(CcrApplication::getRouteChain, application.getRouteChain())
                .set(CcrApplication::getStartNodeCode, application.getStartNodeCode())
                .set(CcrApplication::getDeptCode, application.getDeptCode())
                .set(CcrApplication::getBoundaryRate, application.getBoundaryRate())
                .set(CcrApplication::getMatchedMatrixNo, application.getMatchedMatrixNo()));
    }
}
