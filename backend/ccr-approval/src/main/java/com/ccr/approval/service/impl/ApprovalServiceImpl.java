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
import com.ccr.approval.mapper.CcrApprovalActionMapper;
import com.ccr.approval.mapper.CcrRateAdjustmentMapper;
import com.ccr.approval.mapper.DwLoanNoteReadMapper;
import com.ccr.approval.service.ApprovalService;
import com.ccr.approval.support.RouteChains;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.core.util.ContributionMerger;
import com.ccr.common.core.util.OrgAchievementAssembler;
import com.ccr.common.core.util.RelatedCustomerResolver;
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
        if (CurrentLoginUser.ROLE_ADMIN.equals(user.getRoleCode())) {
            return pricingItemMapper.selectList(wrapper);
        }
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
        return merged;
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
    public ApprovalResult approve(Long pricingItemId, String nodeCode, BigDecimal adjustRate, String comment,
                        Integer versionNo, String idempotencyKey, Map<Long, BigDecimal> rateAdjustments) {
        SysUserRead operator = checkOperatorAndNode(nodeCode);
        guardIdempotency(idempotencyKey);
        CcrPricingItem item = getRoutingItem(pricingItemId, nodeCode);
        CcrApplication application = getApplication(item.getApplicationId());
        // 节点审批人配置限制(§5.5.1):配置了有效指派时仅解析出的处理人可操作(§D16a 部门分流按分项 dept_code)
        guardNodeAssignee(nodeCode, application, operator, item.getDeptCode());
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
        // 调价后按新利率重算矩阵路由(§8.6):跨上会线(如 3.9→3.7)时终审岗位/链路随之变化,
        // 刷新冻结的 route_code/route_chain/dept_code 等,推进按新链取下一节点;存款链固定不重算
        RouteResult reroute = (adjusted && !deposit) ? recalcRoute(application, item, adjustRate) : null;
        if (reroute != null) {
            applyReroute(item, reroute);
        }

        // ===== 整单流转口径 v2(用户拍板 2026-08-27):中间节点逐分项审批,全齐套后整单上送 =====
        // 同申请全部分项(create_time 升序保证轨迹顺序稳定);兜底含触发分项自身
        List<CcrPricingItem> appItems = pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, application.getId())
                .orderByAsc(CcrPricingItem::getCreateTime));
        if (appItems.isEmpty()) {
            appItems = new ArrayList<>(List.of(item));
        }

        // 权限内判定:贷款 审批利率≥节点下限;存款/保证金 审批利率≤期限上限(冻结 boundary_rate,含等于)
        boolean withinPermission;
        if (deposit) {
            BigDecimal upper = item.getBoundaryRate();
            withinPermission = upper != null && effectiveRate != null && effectiveRate.compareTo(upper) <= 0;
        } else {
            withinPermission = inNodePermission(businessType, effectiveRate, perm);
        }
        // 下一节点:存款链支行过手后直上小组;贷款链优先沿分项冻结链路(矩阵驱动,可跳过无权限节点如GM),
        // 回退固定链;链终点(正常不可达)按权限内终审兜底
        List<String> frozenChain = parseRouteChain(item.getRouteChain());
        String next = deposit ? RouteChains.SIX_PEOPLE_GROUP
                : (reroute != null ? nextAfterReroute(nodeCode, reroute.getRouteChain())
                                   : RouteChains.nextNode(nodeCode, frozenChain));
        // 终审节点判定:仅当前节点 = 矩阵冻结的终审岗位(route_code)才具备权限内终审资格;
        // 链路中间节点(如强制上会场景的支行/分管行长)即使利率在权限内也只有知晓/过手权,须沿链上送,
        // 避免在此截胡终审——预览为「上会+行长决策」,实际却被中间节点直接终审,前后口径不一致
        String routeCode = item.getRouteCode();
        boolean isFinalNode = StrUtil.isBlank(routeCode) || routeCode.equals(nodeCode);

        // 齐套判定:同申请全部「本节点 ROUTING」分项均须已取得本节点 APPROVE(触发分项本次动作即视为已通过)。
        // 「本节点已同意」口径 = ccr_approval_action 按 node_code + APPROVE 过滤覆盖的分项(与详情页
        // siblingItems.agreed 同源);上级节点 APPROVE 不再视为本节点已通过——中间节点逐项审批,
        // 每个节点的每个分项都要逐个同意,全部齐套后才整单推进
        List<Long> appItemIds = appItems.stream().map(CcrPricingItem::getId).toList();
        List<Long> nodeApprovedIds = approvalActionMapper.selectList(
                new LambdaQueryWrapper<CcrApprovalAction>()
                        .select(CcrApprovalAction::getPricingItemId)
                        .eq(CcrApprovalAction::getActionType, "APPROVE")
                        .eq(CcrApprovalAction::getNodeCode, nodeCode)
                        .in(CcrApprovalAction::getPricingItemId, appItemIds))
                .stream().map(CcrApprovalAction::getPricingItemId).toList();
        // 本节点已否决分项(逐项否决模型 2026-08-27 用户拍板:否决与同意一样记本节点动作,齐套后统一分派;
        // 部分否决整单上送、否决分项置 REJECTED 展示给上级,全部否决整单退回)
        List<Long> nodeRejectedIds = approvalActionMapper.selectList(
                new LambdaQueryWrapper<CcrApprovalAction>()
                        .select(CcrApprovalAction::getPricingItemId)
                        .eq(CcrApprovalAction::getActionType, "REJECT")
                        .eq(CcrApprovalAction::getNodeCode, nodeCode)
                        .in(CcrApprovalAction::getPricingItemId, appItemIds))
                .stream().map(CcrApprovalAction::getPricingItemId).toList();
        // 齐套判定:同申请全部「本节点 ROUTING」分项均须已取得本节点处理记录(APPROVE 同意或 REJECT 否决,
        // 触发分项本次动作即视为已处理)。口径 = ccr_approval_action 按 node_code+action 过滤覆盖的分项
        // (与详情页 siblingItems.agreed/rejected 同源);上级节点动作不再视为本节点已通过——
        // 中间节点逐项审批,每个节点的每个分项都要逐个同意/否决,全部齐套后才整单推进
        boolean allProcessed = appItems.stream().allMatch(i ->
                !(PricingItemStatus.ROUTING.getCode().equals(i.getStatus())
                        && nodeCode.equals(i.getCurrentNodeCode()))
                    || (i.getId().equals(item.getId())
                        || nodeApprovedIds.contains(i.getId())
                        || nodeRejectedIds.contains(i.getId())));
        // 防重复守卫:触发分项已在本节点同意或否决过(齐套判定前,覆盖未齐套/齐套两条路径),重复提交直接拒绝,
        // 避免重复 update/重复留痕
        if (nodeApprovedIds.contains(item.getId()) || nodeRejectedIds.contains(item.getId())) {
            throw new ServiceException(ErrorCode.TASK_PROCESSED.getCode(),
                    "分项[" + item.getPricingItemNo() + "]本节点已处理,请勿重复操作");
        }

        if (!allProcessed) {
            // 未齐套:记「本节点已同意」,保持 ROUTING 在当前节点,停留待其余分项逐个同意
            updateItemWithStateAndVersion(item, nodeCode, PricingItemStatus.ROUTING.getCode(),
                    adjusted ? effectiveRate : null, null, versionNo);
            if (adjusted) {
                saveAdjustment(item, nodeCode, operator.getId(), beforeRate, adjustRate, perm);
            }
            // §14.7 流转留痕:ROUTING→ROUTING 表示本节点已同意、待整单齐套
            insertAction(buildAction(item.getId(), "APPROVE", nodeCode, operator.getId(),
                    comment, beforeRate, effectiveRate, idempotencyKey,
                    PricingItemStatus.ROUTING.getCode(), PricingItemStatus.ROUTING.getCode()));
            warmFlowService.recordBusinessTrail(item.getPricingItemNo(), nodeCode, "APPROVE",
                    operatorName(operator), comment);
            log.info("分项 {} 节点 {} 已同意(待整单齐套), 操作人 {} 调价:{}",
                    pricingItemId, nodeCode, operator.getId(), adjusted);
            // 审批提交成功提示:待同申请其余分项齐套后整单推进
            return ApprovalResult.go(nodeCode);
        }

        // 齐套:重新判定本节点 ROUTING 分项是否有任一超权限(触发分项本次动作即视为已通过;
        // sibling 按 rateAdjustments 调整后利率判定,存款按期限上限)
        boolean anyOutOfPermission = appItems.stream().anyMatch(i -> {
            if (!(PricingItemStatus.ROUTING.getCode().equals(i.getStatus())
                    && nodeCode.equals(i.getCurrentNodeCode()))) {
                return false;
            }
            // 本节点已否决分项不参与推进与超权限判定(齐套后置 REJECTED 展示给上级,不按利率上送)
            if (nodeRejectedIds.contains(i.getId())) {
                return false;
            }
            if (i.getId().equals(item.getId())) {
                return !withinPermission;
            }
            BigDecimal siblingAdjust = rateAdjustments == null ? null : rateAdjustments.get(i.getId());
            boolean siblingAdjusted = siblingAdjust != null
                    && (i.getCurrentApprovalRate() == null
                        || siblingAdjust.compareTo(i.getCurrentApprovalRate()) != 0);
            BigDecimal siblingEffective = siblingAdjusted ? siblingAdjust : i.getCurrentApprovalRate();
            if (deposit) {
                BigDecimal upper = i.getBoundaryRate();
                return upper == null || siblingEffective == null || siblingEffective.compareTo(upper) > 0;
            }
            return !inNodePermission(businessType, siblingEffective, perm);
        });

        // 本节点 ROUTING 待处理分项(含触发分项):齐套分派与整单终审/上送只针对这批——
        // 已上送/已终审 sibling 不参与,避免整单终审时对历史 sibling 重复 update 触发状态冲突
        List<CcrPricingItem> nodeItems = appItems.stream()
                .filter(i -> PricingItemStatus.ROUTING.getCode().equals(i.getStatus())
                        && nodeCode.equals(i.getCurrentNodeCode()))
                .toList();
        // 齐套终审资格:本节点全部 ROUTING 分项 route_code 均空或=当前节点——混合 route_code 时
        // 异链/超权限 sibling 须上送,不能就地终审;next==null(冻结链已尽)归终审兜底,不走整单上送
        // 把 currentNodeCode 置空(前端 go(null) 也会误判终审)
        boolean allItemsFinalAtThisNode = nodeItems.stream().allMatch(i ->
                StrUtil.isBlank(i.getRouteCode()) || i.getRouteCode().equals(nodeCode));
        // 本节点存在已否决分项:不能整单终审(B1 会把否决分项误置 APPROVED_LEVEL),须走上送分支
        // 把否决分项置 REJECTED 展示给上级(部分否决语义)
        boolean anyRejectedAtNode = nodeItems.stream().anyMatch(i -> nodeRejectedIds.contains(i.getId()));

        if ((isFinalNode || next == null) && !anyOutOfPermission && allItemsFinalAtThisNode && !anyRejectedAtNode) {
            // 整单齐套终审:全部分项一起置 APPROVED_LEVEL,走既有终态串联(决议/承诺/主申请聚合)
            updateItemWithStateAndVersion(item, nodeCode, PricingItemStatus.APPROVED_LEVEL.getCode(),
                    adjusted ? effectiveRate : null, effectiveRate, versionNo);
            if (adjusted) {
                saveAdjustment(item, nodeCode, operator.getId(), beforeRate, adjustRate, perm);
            }
            insertAction(buildAction(item.getId(), "APPROVE", nodeCode, operator.getId(),
                    comment, beforeRate, effectiveRate, idempotencyKey,
                    PricingItemStatus.ROUTING.getCode(), PricingItemStatus.APPROVED_LEVEL.getCode()));
            for (CcrPricingItem sibling : nodeItems) {
                if (sibling.getId().equals(item.getId())) {
                    continue;
                }
                // sibling 调价(与整单上送分支同口径):rateAdjustments 调整后利率作为终审利率并留痕
                BigDecimal siblingAdjust = rateAdjustments == null ? null : rateAdjustments.get(sibling.getId());
                boolean siblingAdjusted = siblingAdjust != null
                        && (sibling.getCurrentApprovalRate() == null
                            || siblingAdjust.compareTo(sibling.getCurrentApprovalRate()) != 0);
                BigDecimal siblingFinal = siblingAdjusted ? siblingAdjust : sibling.getCurrentApprovalRate();
                if (siblingAdjusted) {
                    ruleEngine.checkHardBoundary(businessType, sibling.getProductCode(), siblingAdjust);
                }
                updateSiblingWholeOrder(sibling, nodeCode, PricingItemStatus.APPROVED_LEVEL.getCode(),
                        siblingAdjusted ? siblingAdjust : null, siblingFinal, null);
                insertAction(buildAction(sibling.getId(), "APPROVE", nodeCode, operator.getId(),
                        "整单终审:本节点全部分项权限内通过,随分项[" + item.getPricingItemNo() + "]齐套终审"
                                + (siblingAdjusted ? "(调价 " + sibling.getCurrentApprovalRate() + "→" + siblingAdjust + ")" : ""),
                        sibling.getCurrentApprovalRate(), siblingFinal, null,
                        PricingItemStatus.ROUTING.getCode(), PricingItemStatus.APPROVED_LEVEL.getCode()));
                if (siblingAdjusted) {
                    saveAdjustment(sibling, nodeCode, operator.getId(),
                            sibling.getCurrentApprovalRate(), siblingAdjust, perm);
                }
            }
            // Warm-Flow 业务轨迹(失败仅记日志,不阻断主流程)
            warmFlowService.recordBusinessTrail(item.getPricingItemNo(), nodeCode, "APPROVE",
                    operatorName(operator), comment);
            // 逐项触发终态串联(决议+承诺计划+主申请聚合,仅本节点 ROUTING 分项,异常不阻断主流程)
            for (CcrPricingItem appItem : nodeItems) {
                itemFinalizationService.afterItemTerminal(appItem.getId(), "LEVEL_APPROVED");
            }
            log.info("分项 {} 节点 {} 通过, 操作人 {} 调价:{} 整单齐套终审(共 {} 项)",
                    pricingItemId, nodeCode, operator.getId(), adjusted, appItems.size());
            // 审批提交成功提示:整单齐套终审,流程完结
            return ApprovalResult.terminal();
        }

        // 整单上送:任一分项超权限通过,或权限内但当前节点非终审岗位(链路中间节点过手)
        // → 该申请全部本节点 ROUTING 分项一起推进下一节点
        if (next == null) {
            // 兜底:齐套但冻结链已尽且存在超权限分项(前文 next==null 权限内已归终审兜底,仅超权限场景到此处)——
            // 不能把 currentNodeCode 置空(前端 go(null) 会误判终审),显式报错提示链路配置异常
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "节点[" + nodeCode + "]冻结链路已尽且存在超权限分项,无法整单上送,请检查链路配置");
        }
        boolean toGroup = RouteChains.SIX_PEOPLE_GROUP.equals(next);
        updateItemWithStateAndVersion(item, next, PricingItemStatus.ROUTING.getCode(),
                adjusted ? effectiveRate : null, null, versionNo);
        if (adjusted) {
            saveAdjustment(item, nodeCode, operator.getId(), beforeRate, adjustRate, perm);
        }
        // §14.7 流转留痕:动作前 ROUTING,动作后 上送→ROUTING / 上送小组→VOTING
        insertAction(buildAction(item.getId(), "ESCALATE", nodeCode, operator.getId(),
                comment, beforeRate, effectiveRate, idempotencyKey,
                PricingItemStatus.ROUTING.getCode(),
                toGroup ? PricingItemStatus.VOTING.getCode() : PricingItemStatus.ROUTING.getCode()));
        // 逐项否决模型:本节点已否决分项不随整单上送推进,置 REJECTED 终态(带否决原因),上级审批该申请
        // 时展示否决结果;也排除在 sibling 上送分流之外
        for (CcrPricingItem rejected : nodeItems) {
            if (!nodeRejectedIds.contains(rejected.getId())) {
                continue;
            }
            updateSiblingWholeOrder(rejected, nodeCode, PricingItemStatus.REJECTED.getCode(),
                    null, "本节点否决,整单上送时随单置终态");
            insertAction(buildAction(rejected.getId(), "REJECT", nodeCode, operator.getId(),
                    "整单上送:同意分项[" + item.getPricingItemNo() + "]推进[" + next + "],本分项本节点否决随整单置终态展示上级",
                    rejected.getCurrentApprovalRate(), rejected.getCurrentApprovalRate(), null,
                    PricingItemStatus.ROUTING.getCode(), PricingItemStatus.REJECTED.getCode()));
            // 否决终态:聚合主申请(部分终态保持 ROUTING;REJECTED 非 COMMITTEE_REJECT 不签决议)
            itemFinalizationService.afterItemTerminal(rejected.getId(), null);
        }
        for (CcrPricingItem sibling : appItems) {
            if (sibling.getId().equals(item.getId())
                    || !PricingItemStatus.ROUTING.getCode().equals(sibling.getStatus())
                    || !nodeCode.equals(sibling.getCurrentNodeCode())
                    || nodeRejectedIds.contains(sibling.getId())) {
                continue;
            }
            // 本次提交带利率调整的分项(rateAdjustments):存款链固定不重算,但调整利率需保存并留痕
            BigDecimal siblingAdjust = rateAdjustments == null ? null : rateAdjustments.get(sibling.getId());
            boolean siblingAdjusted = siblingAdjust != null
                    && (sibling.getCurrentApprovalRate() == null
                        || siblingAdjust.compareTo(sibling.getCurrentApprovalRate()) != 0);
            // 存款双轨消除(§7.3):任一超上限整单上会,全部分项无条件推进小组,与 D16b 一致
            if (deposit) {
                if (siblingAdjusted) {
                    ruleEngine.checkHardBoundary(businessType, sibling.getProductCode(), siblingAdjust);
                }
                updateSiblingWholeOrder(sibling, RouteChains.SIX_PEOPLE_GROUP,
                        PricingItemStatus.ROUTING.getCode(), siblingAdjusted ? siblingAdjust : null, null, null);
                insertAction(buildAction(sibling.getId(), "ESCALATE", nodeCode, operator.getId(),
                        "整单上送:分项[" + item.getPricingItemNo() + "]保留超权限利率通过,随整单推进至[SIX_PEOPLE_GROUP]"
                                + (siblingAdjusted ? "(调价 " + sibling.getCurrentApprovalRate() + "→" + siblingAdjust + ")" : ""),
                        sibling.getCurrentApprovalRate(),
                        siblingAdjusted ? siblingAdjust : sibling.getCurrentApprovalRate(), null,
                        PricingItemStatus.ROUTING.getCode(), PricingItemStatus.VOTING.getCode()));
                if (siblingAdjusted) {
                    saveAdjustment(sibling, nodeCode, operator.getId(),
                            sibling.getCurrentApprovalRate(), siblingAdjust, perm);
                }
                continue;
            }
            // 贷款按 sibling 自身 route_code 分流(用户拍板:小组只审批自身权限内的分项):
            // 当前节点即该 sibling 冻结终审岗位(route_code==当前节点)且利率权限内 → 就地终审,
            // 不随触发分项连带进小组、不建表决轮次;其余 sibling 沿自身 route_chain 推进
            // 本次提交带利率调整的 sibling(rateAdjustments):先按调整后利率重算矩阵路由并刷新冻结链路
            // (跨上会线如 3.9→3.7 时终审岗位随之变为小组),再按新路由判定分流,修复合单上送时
            // 非触发分项利率修改被丢弃的问题
            RouteResult siblingReroute = null;
            if (siblingAdjusted) {
                ruleEngine.checkHardBoundary(businessType, sibling.getProductCode(), siblingAdjust);
                siblingReroute = recalcRoute(application, sibling, siblingAdjust);
                applyReroute(sibling, siblingReroute);
            }
            BigDecimal siblingEffective = siblingAdjusted ? siblingAdjust : sibling.getCurrentApprovalRate();
            String siblingRouteCode = siblingReroute != null ? siblingReroute.getFinalNodeCode() : sibling.getRouteCode();
            boolean siblingFinal = StrUtil.isBlank(siblingRouteCode) || siblingRouteCode.equals(nodeCode);
            boolean siblingWithin = inNodePermission(businessType, siblingEffective, perm);
            if (siblingFinal && siblingWithin) {
                updateSiblingWholeOrder(sibling, nodeCode, PricingItemStatus.APPROVED_LEVEL.getCode(),
                        siblingAdjusted ? siblingAdjust : null, siblingEffective, null);
                insertAction(buildAction(sibling.getId(), "APPROVE", nodeCode, operator.getId(),
                        "整单上送:触发分项[" + item.getPricingItemNo() + "]上送[" + next + "],本分项在节点["
                                + nodeCode + "]权限内通过就地终审"
                                + (siblingAdjusted ? "(调价 " + sibling.getCurrentApprovalRate() + "→" + siblingAdjust + ")" : ""),
                        sibling.getCurrentApprovalRate(), siblingEffective, null,
                        PricingItemStatus.ROUTING.getCode(), PricingItemStatus.APPROVED_LEVEL.getCode()));
                if (siblingAdjusted) {
                    saveAdjustment(sibling, nodeCode, operator.getId(),
                            sibling.getCurrentApprovalRate(), siblingAdjust, perm);
                }
                // 终态串联(决议+承诺计划+主申请聚合,异常不阻断主流程)
                itemFinalizationService.afterItemTerminal(sibling.getId(), "LEVEL_APPROVED");
            } else {
                List<String> siblingChain = siblingReroute != null ? siblingReroute.getRouteChain()
                        : parseRouteChain(sibling.getRouteChain());
                String siblingNext = siblingReroute != null
                        ? nextAfterReroute(nodeCode, siblingChain)
                        : RouteChains.nextNode(nodeCode, siblingChain);
                if (siblingNext == null) {
                    // 链路已尽且当前节点非终审岗位的异常口径:按权限内就地终审兜底,避免分项悬挂
                    updateSiblingWholeOrder(sibling, nodeCode, PricingItemStatus.APPROVED_LEVEL.getCode(),
                            siblingAdjusted ? siblingAdjust : null, siblingEffective, null);
                    insertAction(buildAction(sibling.getId(), "APPROVE", nodeCode, operator.getId(),
                            "整单上送:触发分项[" + item.getPricingItemNo() + "]上送[" + next + "],本分项链路已尽就地终审兜底"
                                    + (siblingAdjusted ? "(调价 " + sibling.getCurrentApprovalRate() + "→" + siblingAdjust + ")" : ""),
                            sibling.getCurrentApprovalRate(), siblingEffective, null,
                            PricingItemStatus.ROUTING.getCode(), PricingItemStatus.APPROVED_LEVEL.getCode()));
                    if (siblingAdjusted) {
                        saveAdjustment(sibling, nodeCode, operator.getId(),
                                sibling.getCurrentApprovalRate(), siblingAdjust, perm);
                    }
                    itemFinalizationService.afterItemTerminal(sibling.getId(), "LEVEL_APPROVED");
                } else {
                    boolean siblingToGroup = RouteChains.SIX_PEOPLE_GROUP.equals(siblingNext);
                    updateSiblingWholeOrder(sibling, siblingNext, PricingItemStatus.ROUTING.getCode(),
                            siblingAdjusted ? siblingAdjust : null, null, null);
                    insertAction(buildAction(sibling.getId(), "ESCALATE", nodeCode, operator.getId(),
                            "整单上送:分项[" + item.getPricingItemNo() + "]保留超权限利率通过,随整单推进至["
                                    + siblingNext + "]"
                                    + (siblingAdjusted ? "(调价 " + sibling.getCurrentApprovalRate() + "→" + siblingAdjust + ")" : ""),
                            sibling.getCurrentApprovalRate(), siblingEffective, null,
                            PricingItemStatus.ROUTING.getCode(),
                            siblingToGroup ? PricingItemStatus.VOTING.getCode() : PricingItemStatus.ROUTING.getCode()));
                    if (siblingAdjusted) {
                        saveAdjustment(sibling, nodeCode, operator.getId(),
                                sibling.getCurrentApprovalRate(), siblingAdjust, perm);
                    }
                }
            }
        }
        // Warm-Flow 业务轨迹(失败仅记日志,不阻断主流程)
        warmFlowService.recordBusinessTrail(item.getPricingItemNo(), nodeCode, "APPROVE",
                operatorName(operator), comment);

        if (toGroup) {
            // 上送小组:同申请小组节点未入批分项自动合为一批,入批后分项置 VOTING
            voteService.createGroupRound(item.getApplicationId());
        }
        log.info("分项 {} 节点 {} 超权限保留利率通过, 操作人 {} 整单上送 {} 上送小组:{}",
                pricingItemId, nodeCode, operator.getId(), next, toGroup);
        // 审批提交成功提示:整单上送推进至下一节点(六人小组等)
        return ApprovalResult.go(next);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApprovalResult reject(Long pricingItemId, String nodeCode, String comment, Integer versionNo, String idempotencyKey) {
        SysUserRead operator = checkOperatorAndNode(nodeCode);
        // §7.3 普通节点否决原因必填
        if (StrUtil.isBlank(comment)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "否决必须填写原因(§7.3)");
        }
        guardIdempotency(idempotencyKey);
        CcrPricingItem item = getRoutingItem(pricingItemId, nodeCode);
        CcrApplication application = getApplication(item.getApplicationId());
        // 节点审批人配置限制(§5.5.1):配置了有效指派时仅解析出的处理人可操作(§D16a 部门分流按分项 dept_code)
        guardNodeAssignee(nodeCode, application, operator, item.getDeptCode());
        String businessType = application.getBusinessType();
        boolean deposit = "DEPOSIT".equals(businessType);
        // 存款双轨消除:普通审批链对 DEPOSIT 分项只允许支行行长节点动作
        if (deposit && !RouteChains.BRANCH_MANAGER.equals(nodeCode)) {
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
        CcrNodePermission perm = nodePermissionMapper.selectOne(new LambdaQueryWrapper<CcrNodePermission>()
                .eq(CcrNodePermission::getNodeCode, nodeCode)
                .eq(CcrNodePermission::getBusinessType, businessType)
                .last("limit 1"));

        // ===== 逐项否决模型(用户拍板 2026-08-27):否决与同意同样逐项审批,全齐套后整单分派 =====
        // 点单个分项只记该分项「本节点已否决」(ROUTING→ROUTING 停留);逐项点完同申请全部
        // 「本节点 ROUTING」分项后整单分派:未全部否决 → 整单上送(被否决分项置 REJECTED 终态,
        // 不再上送、上级无需重复否决);全部否决 → 整单直接退回
        List<CcrPricingItem> appItems = pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, application.getId())
                .orderByAsc(CcrPricingItem::getCreateTime));
        List<Long> appItemIds = appItems.stream().map(CcrPricingItem::getId).toList();
        List<Long> nodeApprovedIds = approvalActionMapper.selectList(
                new LambdaQueryWrapper<CcrApprovalAction>()
                        .select(CcrApprovalAction::getPricingItemId)
                        .eq(CcrApprovalAction::getActionType, "APPROVE")
                        .eq(CcrApprovalAction::getNodeCode, nodeCode)
                        .in(CcrApprovalAction::getPricingItemId, appItemIds))
                .stream().map(CcrApprovalAction::getPricingItemId).toList();
        List<Long> nodeRejectedIds = approvalActionMapper.selectList(
                new LambdaQueryWrapper<CcrApprovalAction>()
                        .select(CcrApprovalAction::getPricingItemId)
                        .eq(CcrApprovalAction::getActionType, "REJECT")
                        .eq(CcrApprovalAction::getNodeCode, nodeCode)
                        .in(CcrApprovalAction::getPricingItemId, appItemIds))
                .stream().map(CcrApprovalAction::getPricingItemId).toList();
        // 防重复守卫:触发分项已在本节点同意或否决过,重复提交直接拒绝,避免重复 update/重复留痕
        if (nodeApprovedIds.contains(item.getId()) || nodeRejectedIds.contains(item.getId())) {
            throw new ServiceException(ErrorCode.TASK_PROCESSED.getCode(),
                    "分项[" + item.getPricingItemNo() + "]本节点已处理,请勿重复操作");
        }
        // 本节点 ROUTING 待处理分项(含触发分项):齐套判定与分派只针对这批——已上送/已终审 sibling 不参与
        List<CcrPricingItem> nodeItems = appItems.stream()
                .filter(i -> PricingItemStatus.ROUTING.getCode().equals(i.getStatus())
                        && nodeCode.equals(i.getCurrentNodeCode()))
                .toList();
        // 齐套判定:本节点全部 ROUTING 分项均须已取得本节点处理记录(APPROVE 同意或 REJECT 否决,
        // 触发分项本次动作即视为已处理)——与 approve 同口径
        boolean allProcessed = nodeItems.stream().allMatch(i ->
                i.getId().equals(item.getId())
                        || nodeApprovedIds.contains(i.getId())
                        || nodeRejectedIds.contains(i.getId()));

        // 记「本节点已否决」:保持 ROUTING 在当前节点(逐项模型下否决不是终态动作,停留待齐套分派)
        updateItemWithStateAndVersion(item, nodeCode, PricingItemStatus.ROUTING.getCode(),
                null, null, versionNo);
        // §14.7 流转留痕:ROUTING→ROUTING 表示本节点已否决、待整单齐套
        insertAction(buildAction(item.getId(), "REJECT", nodeCode, operator.getId(),
                comment, item.getCurrentApprovalRate(), item.getCurrentApprovalRate(), idempotencyKey,
                PricingItemStatus.ROUTING.getCode(), PricingItemStatus.ROUTING.getCode()));
        // Warm-Flow 业务轨迹(失败仅记日志,不阻断主流程)
        warmFlowService.recordBusinessTrail(item.getPricingItemNo(), nodeCode, "REJECT",
                operatorName(operator), comment);
        if (!allProcessed) {
            log.info("分项 {} 节点 {} 已否决(待整单齐套), 操作人 {}", pricingItemId, nodeCode, operator.getId());
            return ApprovalResult.go(nodeCode);
        }

        // 齐套分派一:全部否决 → 整单否决,流程直接结束(主申请聚合置否决态,非退回)。
        // 触发分项本次动作即视为已否决(nodeRejectedIds 是动作前快照不含触发项,须显式计入)
        boolean allRejected = nodeItems.stream().allMatch(i ->
                i.getId().equals(item.getId()) || nodeRejectedIds.contains(i.getId()));
        if (allRejected) {
            // 整单否决:触发分项与同申请其余 ROUTING 分项一并置 REJECTED(终态),finalReason 注明否决原因;
            // 主申请经 afterItemTerminal 聚合为 REJECTED「已否决」,流程结束,不是退回客户经理重办
            // (用户拍板 2026-08-27:整单否决≠退回)
            updateSiblingWholeOrder(item, item.getCurrentNodeCode(), PricingItemStatus.REJECTED.getCode(),
                    null, comment);
            insertAction(buildAction(item.getId(), "REJECT", nodeCode, operator.getId(),
                    comment, item.getCurrentApprovalRate(), item.getCurrentApprovalRate(), null,
                    PricingItemStatus.ROUTING.getCode(), PricingItemStatus.REJECTED.getCode()));
            for (CcrPricingItem sibling : appItems) {
                if (sibling.getId().equals(item.getId())
                        || !PricingItemStatus.ROUTING.getCode().equals(sibling.getStatus())) {
                    continue;
                }
                updateSiblingWholeOrder(sibling, sibling.getCurrentNodeCode(), PricingItemStatus.REJECTED.getCode(),
                        null, "整单否决:触发分项[" + item.getPricingItemNo() + "]");
                insertAction(buildAction(sibling.getId(), "REJECT", nodeCode, operator.getId(),
                        "整单否决:触发分项[" + item.getPricingItemNo() + "],原因:" + StrUtil.nullToEmpty(comment),
                        sibling.getCurrentApprovalRate(), sibling.getCurrentApprovalRate(), null,
                        PricingItemStatus.ROUTING.getCode(), PricingItemStatus.REJECTED.getCode()));
            }
            // 否决终态:聚合主申请状态(全部 REJECTED → 主申请 REJECTED)
            itemFinalizationService.afterItemTerminal(item.getId(), null);
            log.info("分项 {} 节点 {} 整单否决(流程直接结束,主申请置否决态), 操作人 {}", pricingItemId, nodeCode, operator.getId());
            return ApprovalResult.terminal();
        }

        // 齐套分派二:部分否决 → 整单上送。被否决分项(含触发分项)置 REJECTED 终态,不上送、
        // 上级无需重复否决(用户拍板 2026-08-27);同意分项按各自 route_chain 分流推进(与 approve 整单上送同口径)
        boolean anyToGroup = false;
        String escalateNext = null;
        for (CcrPricingItem rejected : nodeItems) {
            if (!nodeRejectedIds.contains(rejected.getId()) && !rejected.getId().equals(item.getId())) {
                continue;
            }
            updateSiblingWholeOrder(rejected, nodeCode, PricingItemStatus.REJECTED.getCode(),
                    null, "本节点否决,部分否决整单上送置终态");
            insertAction(buildAction(rejected.getId(), "REJECT", nodeCode, operator.getId(),
                    "部分否决整单上送:本分项本节点否决置终态展示上级,原因:" + StrUtil.nullToEmpty(comment),
                    rejected.getCurrentApprovalRate(), rejected.getCurrentApprovalRate(), null,
                    PricingItemStatus.ROUTING.getCode(), PricingItemStatus.REJECTED.getCode()));
            // 否决终态串联:聚合主申请(部分终态保持 ROUTING;REJECTED 非 COMMITTEE_REJECT 不签决议)
            itemFinalizationService.afterItemTerminal(rejected.getId(), null);
        }
        for (CcrPricingItem agreed : nodeItems) {
            if (agreed.getId().equals(item.getId()) || nodeRejectedIds.contains(agreed.getId())) {
                continue;
            }
            BigDecimal agreedRate = agreed.getCurrentApprovalRate();
            if (deposit) {
                // 存款链固定不重算:全部分项无条件推进小组
                updateSiblingWholeOrder(agreed, RouteChains.SIX_PEOPLE_GROUP, PricingItemStatus.ROUTING.getCode(),
                        null, null, null);
                insertAction(buildAction(agreed.getId(), "ESCALATE", nodeCode, operator.getId(),
                        "部分否决整单上送:触发否决分项[" + item.getPricingItemNo() + "],本分项同意随整单推进至[SIX_PEOPLE_GROUP]",
                        agreedRate, agreedRate, null,
                        PricingItemStatus.ROUTING.getCode(), PricingItemStatus.VOTING.getCode()));
                anyToGroup = true;
                escalateNext = RouteChains.SIX_PEOPLE_GROUP;
                continue;
            }
            // 贷款按 route_code 分流:本节点即该分项冻结终审岗位(route_code==本节点)且利率权限内 → 就地终审;
            // 其余同意分项沿自身 route_chain 推进
            String agreedRouteCode = agreed.getRouteCode();
            boolean agreedFinal = StrUtil.isBlank(agreedRouteCode) || agreedRouteCode.equals(nodeCode);
            boolean agreedWithin = inNodePermission(businessType, agreedRate, perm);
            if (agreedFinal && agreedWithin) {
                updateSiblingWholeOrder(agreed, nodeCode, PricingItemStatus.APPROVED_LEVEL.getCode(),
                        null, agreedRate, null);
                insertAction(buildAction(agreed.getId(), "APPROVE", nodeCode, operator.getId(),
                        "部分否决整单上送:触发否决分项[" + item.getPricingItemNo() + "]上送,本分项在节点["
                                + nodeCode + "]权限内通过就地终审",
                        agreedRate, agreedRate, null,
                        PricingItemStatus.ROUTING.getCode(), PricingItemStatus.APPROVED_LEVEL.getCode()));
                // 终态串联(决议+承诺计划+主申请聚合,异常不阻断主流程)
                itemFinalizationService.afterItemTerminal(agreed.getId(), "LEVEL_APPROVED");
                continue;
            }
            List<String> agreedChain = parseRouteChain(agreed.getRouteChain());
            String agreedNext = RouteChains.nextNode(nodeCode, agreedChain);
            if (agreedNext == null) {
                // 链路已尽且当前节点非终审岗位的异常口径:按就地终审兜底,避免分项悬挂
                updateSiblingWholeOrder(agreed, nodeCode, PricingItemStatus.APPROVED_LEVEL.getCode(),
                        null, agreedRate, null);
                insertAction(buildAction(agreed.getId(), "APPROVE", nodeCode, operator.getId(),
                        "部分否决整单上送:触发否决分项[" + item.getPricingItemNo() + "]上送,本分项链路已尽就地终审兜底",
                        agreedRate, agreedRate, null,
                        PricingItemStatus.ROUTING.getCode(), PricingItemStatus.APPROVED_LEVEL.getCode()));
                itemFinalizationService.afterItemTerminal(agreed.getId(), "LEVEL_APPROVED");
            } else {
                boolean agreedToGroup = RouteChains.SIX_PEOPLE_GROUP.equals(agreedNext);
                if (agreedToGroup) {
                    anyToGroup = true;
                }
                updateSiblingWholeOrder(agreed, agreedNext, PricingItemStatus.ROUTING.getCode(),
                        null, null, null);
                insertAction(buildAction(agreed.getId(), "ESCALATE", nodeCode, operator.getId(),
                        "部分否决整单上送:触发否决分项[" + item.getPricingItemNo() + "]上送,本分项同意随整单推进至["
                                + agreedNext + "]",
                        agreedRate, agreedRate, null,
                        PricingItemStatus.ROUTING.getCode(),
                        agreedToGroup ? PricingItemStatus.VOTING.getCode() : PricingItemStatus.ROUTING.getCode()));
                escalateNext = agreedNext;
            }
        }
        if (anyToGroup) {
            // 上送小组:同申请小组节点未入批分项自动合为一批,入批后分项置 VOTING
            voteService.createGroupRound(item.getApplicationId());
        }
        log.info("分项 {} 节点 {} 否决(部分否决,整单上送), 操作人 {}", pricingItemId, nodeCode, operator.getId());
        return escalateNext != null ? ApprovalResult.go(escalateNext) : ApprovalResult.terminal();
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
        log.info("审批中回填客户号:分项 {} 申请 {} 场景{} 占位号 {} → 真实号 {}, 操作人 {}",
                pricingItemId, application.getId(), groupScope ? "集团成员" : "单户", currentNo, resolved,
                currentLoginUser.requireLoginId());
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

    /** 从 JSON 快照提取指定 key 的字符串值(快照为系统序列化,格式稳定: "\"key\":\"value\"") */
    private String extractJsonName(String json, String key) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        String marker = "\"" + key + "\":\"";
        int from = json.indexOf(marker);
        if (from < 0) {
            return null;
        }
        int start = from + marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
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
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pi.application_id applicationId FROM ccr_resolution r"
                        + " JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id"
                        + " WHERE pi.application_id IN (" + in + ") AND r.del_flag = '0' GROUP BY pi.application_id");
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
        // 承诺逐期履约评估(按指标期次:实际值/达成率/风险/结果;档案页展示"每一期指标完成情况")
        result.put("commitmentEvaluations", jdbcTemplate.queryForList(
                "SELECT te.plan_id planId, te.metric_id metricId, cm.metric_code metricCode,"
                        + " te.data_dt dataDt, te.actual_value actualValue, te.achievement_ratio achievementRatio,"
                        + " te.risk_level riskLevel, te.result_status resultStatus"
                        + " FROM ccr_tracking_evaluation te"
                        + " JOIN ccr_commitment_metric cm ON cm.id = te.metric_id"
                        + " JOIN ccr_commitment_plan cp ON cp.id = cm.plan_id"
                        + " JOIN ccr_resolution r ON r.id = cp.resolution_id"
                        + " JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id"
                        + " WHERE pi.application_id = ? AND te.del_flag = '0'"
                        + " ORDER BY cm.id, te.data_dt", applicationId));

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
                "SELECT id, file_name fileName, file_size fileSize, create_time createTime FROM ccr_application_attachment WHERE application_id = ? AND del_flag = '0' ORDER BY id", applicationId));
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
        String groupName = null, groupType = null, groupStatus = null;
        for (Map<String, Object> record : snapshotRecords) {
            if (!groupNo.equals(record.get("subjectId")) || !"GROUP".equals(record.get("subjectType"))) {
                continue;
            }
            Map<String, Object> core = coreOf(record);
            groupName = jsonSafe(core.get("group_name")) == null ? null : String.valueOf(core.get("group_name"));
            groupType = jsonSafe(core.get("group_type")) == null ? null : String.valueOf(core.get("group_type"));
            groupStatus = jsonSafe(core.get("group_status")) == null ? null : String.valueOf(core.get("group_status"));
            break;
        }
        if (groupName == null) {
            Map<String, Object> dw = dataWarehouseService.findGroup(groupNo);
            if (dw != null) {
                groupName = dw.get("group_name") == null ? null : String.valueOf(dw.get("group_name"));
                groupType = dw.get("group_type") == null ? null : String.valueOf(dw.get("group_type"));
                groupStatus = dw.get("group_status") == null ? null : String.valueOf(dw.get("group_status"));
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
        if (gi != null) {
            row.put("certNo", gi.getStr("ucrCode"));
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
        member.put("registeredCapital", jsonSafe(core.get("reg_cap")));
        member.put("openOrgName", jsonSafe(core.get("openact_org_nm")));
        member.put("openDate", snapshotDate(jsonSafe(core.get("openact_dt"))));
        member.put("basicAccount", jsonSafe(core.get("basic_account_no")));
        member.put("customerClass", jsonSafe(core.get("cust_class")));
        member.put("empeNum", jsonSafe(core.get("entp_empe_num")));
        member.put("estbDate", snapshotDate(jsonSafe(core.get("estp_estb_dt"))));
        member.put("totalAssets", jsonSafe(core.get("rest_asts")));
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
                        + " reg_cap registeredCapital, openact_org_nm openOrgName, openact_dt openDate,"
                        + " basic_account_no basicAccount, cust_class customerClass, entp_empe_num empeNum,"
                        + " estp_estb_dt estbDate, rest_asts totalAssets, rest_addr restAddr"
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
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT gp.pricing_item_id pricingItemId, gp.main_guarantee_type guaranteeType, gp.package_version packageVersion,"
                        + " gm.measure_no measureNo, gm.measure_type measureType, gm.guarantee_amount guaranteeAmount, gm.ext_json extJson"
                        + " FROM ccr_guarantee_package gp"
                        + " LEFT JOIN ccr_guarantee_measure gm ON gm.package_id = gp.id"
                        + " JOIN ccr_pricing_item pi ON pi.id = gp.pricing_item_id"
                        + " WHERE pi.application_id = ? AND gp.del_flag = '0' AND gm.del_flag = '0' ORDER BY gp.id, gm.measure_no", applicationId);
        Map<Object, List<Map<String, Object>>> byItem = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byItem.computeIfAbsent(row.get("pricingItemId"), k -> new ArrayList<>()).add(row);
        }
        return byItem;
    }

    /** 机构达成(§12.16):申请机构 → ccr_sys_dept.org_code → 本系统实时组装(增量021 B方案,废弃数仓 dw_org_performance_snapshot) */
    private List<Map<String, Object>> orgPerformance(Long appId) {
        if (appId == null) {
            return List.of();
        }
        List<Map<String, Object>> orgCodes = jdbcTemplate.queryForList(
                "SELECT d.org_code orgCode FROM ccr_application a JOIN ccr_sys_dept d ON d.id = a.applicant_org_id"
                        + " WHERE a.id = ? AND d.del_flag = '0'", appId);
        if (orgCodes.isEmpty() || orgCodes.get(0).get("orgCode") == null) {
            return List.of();
        }
        return OrgAchievementAssembler.assemble(jdbcTemplate, orgCodes.get(0).get("orgCode").toString());
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

    /** 集团批复总额度(§B18 路由金额定档基准;非集团或无授信快照返回 null,calcRoute 回退本笔金额) */
    private BigDecimal routeGroupCreditTotal(CcrApplication app) {
        if (!"GROUP".equals(app.getCustomerScope()) || StrUtil.isBlank(app.getGroupNo())) {
            return null;
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
}
