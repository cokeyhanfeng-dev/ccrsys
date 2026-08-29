package com.ccr.application.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrApplicationCommitment;
import com.ccr.application.domain.CcrApplicationCreditSummary;
import com.ccr.application.domain.CcrApplicationMember;
import com.ccr.application.domain.CcrApplicationOtherLoan;
import com.ccr.application.domain.CcrApplicationRelation;
import com.ccr.application.domain.CcrGroup;
import com.ccr.application.domain.CcrGroupMember;
import com.ccr.application.domain.CcrGuaranteeMeasure;
import com.ccr.application.domain.CcrGuaranteePackage;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.domain.CcrPricingItemContractRel;
import com.ccr.application.domain.CcrPricingItemDepositRel;
import com.ccr.application.dto.RoutePreviewResponse;
import com.ccr.application.dto.SnapshotBundleResult;
import com.ccr.application.dto.SnapshotRecordInput;
import com.ccr.application.dto.SnapshotRelationInput;
import com.ccr.application.dto.SubmitCheckResponse;
import com.ccr.application.dto.SubmitResponse;
import com.ccr.application.enums.ApplicationStatus;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrApplicationCommitmentMapper;
import com.ccr.application.mapper.CcrApplicationCreditSummaryMapper;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrApplicationMemberMapper;
import com.ccr.application.mapper.CcrApplicationOtherLoanMapper;
import com.ccr.application.mapper.CcrApplicationRelationMapper;
import com.ccr.application.mapper.CcrGuaranteeMeasureMapper;
import com.ccr.application.mapper.CcrGuaranteePackageMapper;
import com.ccr.application.mapper.CcrPricingItemContractRelMapper;
import com.ccr.application.mapper.CcrPricingItemDepositRelMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.application.service.ApplicationSubmitService;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.application.service.DataWarehouseService;
import com.ccr.application.service.ManualGroupService;
import com.ccr.application.service.SnapshotGateway;
import com.ccr.application.support.CustomerNoUtil;
import com.ccr.common.cache.CcrCacheUtil;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.common.outbox.OutboxEventType;
import com.ccr.common.outbox.OutboxService;
import com.ccr.rule.domain.CcrLprVersion;
import com.ccr.rule.domain.CcrRateRuleSet;
import com.ccr.rule.dto.MatrixRouteInput;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.engine.RuleEngine;
import com.ccr.rule.mapper.CcrLprVersionMapper;
import com.ccr.rule.mapper.CcrRateRuleSetMapper;
import com.ccr.rule.service.RateMatrixRouter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 申请提交编排实现(§7.1 步骤7-11)
 * 快照采集:按主体从数仓最新批次采集,集团链 集团→成员→额度→分项→合同→借据(§A.6);
 * 冻结:LPR 版本 + 规则集版本 + 路由生效日期(§8.4);
 * 路由:首节点恒为 BRANCH_MANAGER,终审岗位写 route_code
 */
@Service
@Slf4j
public class ApplicationSubmitServiceImpl implements ApplicationSubmitService {

    /** 分项终态(一合同一有效分项检查中不阻断) */
    private static final Set<String> ITEM_TERMINAL_STATUS = Set.of("FINAL", "REJECTED", "VETOED", "CLOSED", "SUPERSEDED");

    /** 允许关联重提的原申请状态(§7.6:否决后保持终态,重提创建新申请;REJECTED 为 D18b 最典型入口) */
    private static final Set<String> REAPPLY_SOURCE_STATUS = Set.of("FINAL", "REJECTED", "VETOED", "CLOSED");

    /** 已批准分项状态(沿用原决议,不重新审批) */
    private static final Set<String> APPROVED_ITEM_STATUS = Set.of("FINAL", "APPROVED_LEVEL");

    /** 数据时效容忍天数(§9.4 默认 3 个自然日,超过 BLOCK 阻断提交;与快照质量规则同一配置) */
    @Value("${ccr.snapshot.data-stale-days:3}")
    private int dataStaleDays;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private NodeAssigneeResolver nodeAssigneeResolver;

    @Resource
    private CcrApplicationMapper applicationMapper;
    @Resource
    private CcrApplicationMemberMapper applicationMemberMapper;
    @Resource
    private CcrPricingItemMapper pricingItemMapper;
    @Resource
    private CcrPricingItemContractRelMapper contractRelMapper;
    @Resource
    private CcrPricingItemDepositRelMapper depositRelMapper;
    @Resource
    private CcrGuaranteePackageMapper guaranteePackageMapper;
    @Resource
    private CcrGuaranteeMeasureMapper guaranteeMeasureMapper;
    @Resource
    private CcrApplicationRelationMapper applicationRelationMapper;
    @Resource
    private CcrApplicationCommitmentMapper commitmentMapper;
    @Resource
    private CcrApplicationOtherLoanMapper otherLoanMapper;
    @Resource
    private CcrApplicationCreditSummaryMapper creditSummaryMapper;
    @Resource
    private DataWarehouseService dataWarehouseService;
    @Resource
    private ManualGroupService manualGroupService;
    @Resource
    private SnapshotGateway snapshotGateway;
    @Resource
    private RateMatrixRouter rateMatrixRouter;
    @Resource
    private RuleEngine ruleEngine;
    @Resource
    private CcrLprVersionMapper lprVersionMapper;
    @Resource
    private CcrRateRuleSetMapper ruleSetMapper;
    @Resource
    private OutboxService outboxService;
    @Resource
    private CcrCacheUtil cacheUtil;
    @Resource
    private ApplicationAccessService applicationAccessService;

    // ==================== 路由预览(§13.1) ====================

    @Override
    public RoutePreviewResponse routePreview(Long id) {
        applicationAccessService.requireOwner(id);
        CcrApplication app = requireApplication(id);
        List<CcrPricingItem> items = routableItems(id);
        BigDecimal groupCreditTotal = loadGroupCreditTotal(app);

        RoutePreviewResponse response = new RoutePreviewResponse();
        response.setApplicationId(id);
        response.setGroupCreditTotal(groupCreditTotal);

        Map<String, Map<String, Object>> corpCache = new HashMap<>();
        List<RoutePreviewResponse.ItemRoutePreview> previews = new ArrayList<>();
        // 整单定链分项(整单交付改造:贷款=requested_rate 最低,存款=任意分项链相同);顶层整单链=该分项链路
        RoutePreviewResponse.ItemRoutePreview anchorPreview = null;
        RouteResult anchorRoute = null;
        BigDecimal anchorRate = null;
        boolean isLoan = "LOAN".equals(app.getBusinessType());
        for (CcrPricingItem item : items) {
            RoutePreviewResponse.ItemRoutePreview preview = new RoutePreviewResponse.ItemRoutePreview();
            preview.setPricingItemId(item.getId());
            preview.setPricingItemNo(item.getPricingItemNo());
            preview.setMemberCustomerNo(item.getMemberCustomerNo());
            preview.setProductCode(item.getProductCode());
            preview.setRequestedRate(item.getRequestedRate());
            // 硬边界(分项级返回,不阻断其他分项)
            try {
                BigDecimal boundary = ruleEngine.checkHardBoundary(
                        businessBigType(app), item.getProductCode(), item.getRequestedRate());
                preview.setHardBoundaryPass(Boolean.TRUE);
                preview.setHardBoundaryRate(boundary);
            } catch (ServiceException e) {
                preview.setHardBoundaryPass(Boolean.FALSE);
                preview.setMessage(e.getMessage());
            }
            // 矩阵路由
            try {
                RouteResult route = rateMatrixRouter.calcRoute(buildRouteInput(app, item, groupCreditTotal, corpCache));
                preview.setRateDirection(route.getRateDirection());
                preview.setStartNodeCode(route.getStartNodeCode());
                preview.setFinalNodeCode(route.getFinalNodeCode());
                preview.setRouteChain(route.getRouteChain());
                // 下一步审批人姓名:routeChain 首节点按申请人机构+分项部门归属解析(§2026-08-26 预览显示审批人)
                List<String> chain = route.getRouteChain();
                if (chain != null && !chain.isEmpty()) {
                    NodeAssigneeResolver.ResolveResult resolved = nodeAssigneeResolver.resolve(
                            chain.get(0), app.getApplicantOrgId(), null, item.getDeptCode());
                    preview.setNextApproverNames(resolved.users().stream()
                            .map(NodeAssigneeResolver.AssigneeUser::getNickName)
                            .filter(StrUtil::isNotBlank)
                            .toList());
                }
                preview.setLprVersionId(route.getLprVersionId());
                preview.setLprVersionCode(route.getLprVersionCode());
                preview.setMessage(route.getMessage());
                if (response.getLprVersionId() == null) {
                    response.setLprVersionId(route.getLprVersionId());
                    response.setLprVersionCode(route.getLprVersionCode());
                }
                // 整单定链:贷款取利率最低分项,存款取任一(链相同,取首个即可)
                if (anchorPreview == null) {
                    anchorPreview = preview;
                    anchorRoute = route;
                    anchorRate = item.getRequestedRate();
                } else if (isLoan && item.getRequestedRate() != null
                        && (anchorRate == null || item.getRequestedRate().compareTo(anchorRate) < 0)) {
                    anchorPreview = preview;
                    anchorRoute = route;
                    anchorRate = item.getRequestedRate();
                }
            } catch (ServiceException e) {
                preview.setErrorCode(e.getCode());
                preview.setErrorMessage(e.getMessage());
            }
            previews.add(preview);
        }
        // 顶层整单链(前端提交预览/流程条按整单展示;贷款=利率最低分项,存款=原流程)
        if (anchorPreview != null && anchorRoute != null) {
            response.setStartNodeCode(anchorPreview.getStartNodeCode());
            response.setFinalNodeCode(anchorPreview.getFinalNodeCode());
            response.setRouteChain(anchorPreview.getRouteChain());
            response.setNextApproverNames(anchorPreview.getNextApproverNames());
            response.setMatchedMatrixNo(anchorRoute.getMatchedMatrixNo());
            response.setBoundaryRate(anchorRoute.getBoundaryRate());
        }
        response.setItems(previews);

        // 刷新数据日期基线(草稿态;§7.1 步骤9 与"上次预览时的数据日期"比较)
        if (ApplicationStatus.DRAFT.getCode().equals(app.getStatus())) {
            refreshBaseline(app);
        }
        return response;
    }

    // ==================== 提交前校验(§7.1 步骤9-10) ====================

    @Override
    public SubmitCheckResponse submitCheck(Long id) {
        applicationAccessService.requireOwner(id);
        CcrApplication app = requireApplication(id);
        List<CcrPricingItem> items = routableItems(id);

        SubmitCheckResponse response = new SubmitCheckResponse();
        response.setApplicationId(id);

        // 1. 数据批次差异:基线(草稿创建/上次预览) vs 最新成功批次
        Map<String, String> latest = dataWarehouseService.latestDataDates(
                DataWarehouseService.relevantDatasets(app.getBusinessType(), app.getCustomerScope()));
        Map<String, String> baseline = parseBaseline(app.getDataBaselineJson());
        response.setBaselineSource(baseline == null ? "NONE" : "DRAFT_CREATE_OR_ROUTE_PREVIEW");
        List<SubmitCheckResponse.DatasetDiff> diffs = new ArrayList<>();
        Set<String> allDatasets = new LinkedHashSet<>(latest.keySet());
        if (baseline != null) {
            allDatasets.addAll(baseline.keySet());
        }
        for (String dataset : allDatasets) {
            SubmitCheckResponse.DatasetDiff diff = new SubmitCheckResponse.DatasetDiff();
            diff.setDatasetCode(dataset);
            diff.setBaselineDataDt(baseline == null ? null : baseline.get(dataset));
            diff.setLatestDataDt(latest.get(dataset));
            diff.setChanged(baseline == null || !StrUtil.equals(latest.get(dataset), baseline.get(dataset)));
            diffs.add(diff);
        }
        response.setDiffs(diffs);

        // 2. 质量预校验(BLOCK/WARN)
        List<SubmitCheckResponse.QualityPrecheckItem> precheck = qualityPrecheck(app, latest);
        response.setQualityPrecheck(precheck);

        // 3. 硬边界校验(逐分项)
        BigDecimal groupCreditTotal = loadGroupCreditTotal(app);
        List<SubmitCheckResponse.HardBoundaryItem> boundaries = new ArrayList<>();
        for (CcrPricingItem item : items) {
            SubmitCheckResponse.HardBoundaryItem hb = new SubmitCheckResponse.HardBoundaryItem();
            hb.setPricingItemId(item.getId());
            hb.setPricingItemNo(item.getPricingItemNo());
            hb.setProductCode(item.getProductCode());
            hb.setRequestedRate(item.getRequestedRate());
            try {
                BigDecimal boundary = ruleEngine.checkHardBoundary(
                        businessBigType(app), item.getProductCode(), item.getRequestedRate());
                hb.setPass(Boolean.TRUE);
                hb.setBoundaryRate(boundary);
                hb.setMessage(boundary == null ? "未配置产品硬边界,放行" : "未突破硬边界");
            } catch (ServiceException e) {
                hb.setPass(Boolean.FALSE);
                hb.setMessage(e.getMessage());
            }
            // 存款起点利率硬边界(2026-08-27 用户拍板):预检阶段即阻断,避免走到正式提交才报错
            if (!Boolean.FALSE.equals(hb.getPass())) {
                try {
                    checkDepositStartRate(app, List.of(item), groupCreditTotal);
                } catch (ServiceException e) {
                    hb.setPass(Boolean.FALSE);
                    hb.setMessage(e.getMessage());
                }
            }
            boundaries.add(hb);
        }
        response.setHardBoundaries(boundaries);

        boolean block = precheck.stream().anyMatch(p -> "BLOCK".equals(p.getLevel()))
                || boundaries.stream().anyMatch(b -> Boolean.FALSE.equals(b.getPass()));
        response.setBlockSubmit(block);
        return response;
    }

    /** 质量预校验:主体数据缺失/数据过旧 BLOCK,贡献度缺失 WARN */
    private List<SubmitCheckResponse.QualityPrecheckItem> qualityPrecheck(CcrApplication app, Map<String, String> latest) {
        List<SubmitCheckResponse.QualityPrecheckItem> items = new ArrayList<>();
        boolean groupScope = "GROUP".equals(app.getCustomerScope());
        if (groupScope) {
            // 数据以数仓为准(§docs/19 §4.1):数仓收录=存量(数仓优先),数仓无=新增(补录数据生效)
            boolean newGroup = dataWarehouseService.findGroup(app.getGroupNo()) == null;
            // 集团主数据:存量需在团(数仓/手工);新增集团客户经理补录 group_info_json 即视为人工确权,降 WARN 放行
            if (!groupExistsForSubmit(app)) {
                items.add(precheckItem("SUBJECT_EXISTS", "BLOCK", app.getGroupNo(),
                        "集团主数据缺失(数仓与手工集团均无,请补录集团信息)"));
            } else if (newGroup && StrUtil.isNotBlank(app.getGroupInfoJson())) {
                items.add(precheckItem("SUBJECT_EXISTS", "WARN", app.getGroupNo(),
                        "集团主数据快照缺失(新增集团,已按人工补录提交,请确认无误)"));
            }
            // 申请额度(本次新增授信)必填:所有集团申请统一;数仓批复授信仅作展示参考,不参与勾稽
            if (applyAmountOf(app) == null) {
                items.add(precheckItem("GROUP_APPLY_AMOUNT", "BLOCK", app.getGroupNo(),
                        "请录入本次申请额度(集团新增授信,必填)"));
            }
            // 成员存在性:数仓成员快照 ∪ 手工成员,任一侧在团即放行;新增集团成员手工录入即放行
            Map<String, Map<String, Object>> dwMemberMap = dwMemberMap(app.getGroupNo());
            Map<String, CcrGroupMember> manualMemberMap = manualMemberMap(app.getGroupNo());
            for (CcrApplicationMember member : applicationMembers(app.getId())) {
                if (!newGroup && !memberValid(dwMemberMap, manualMemberMap, member.getMemberCustomerNo())) {
                    items.add(precheckItem("GROUP_MEMBER_VALID", "BLOCK", member.getMemberCustomerNo(),
                            "涉及成员不在集团有效成员快照中(可手工补录成员)"));
                }
                if (dataWarehouseService.contribution(member.getMemberCustomerNo()).isEmpty()) {
                    items.add(precheckItem("CONTRIBUTION_EXISTS", "WARN", member.getMemberCustomerNo(),
                            "成员贡献度数据缺失"));
                }
            }
        } else {
            Map<String, Object> basic = "INDIVIDUAL".equals(app.getCustomerScope())
                    ? dataWarehouseService.findIndvCustomer(app.getCustomerNo())
                    : dataWarehouseService.findCorpCustomer(app.getCustomerNo());
            if (basic == null) {
                // 新增客户:后台数仓拉不出主数据时,客户经理手工填写(customer_info_json 非空)即视为人工确权,降为 WARN 放行
                boolean manualProvided = StrUtil.isNotBlank(app.getCustomerInfoJson());
                items.add(precheckItem("SUBJECT_EXISTS", manualProvided ? "WARN" : "BLOCK", app.getCustomerNo(),
                        manualProvided ? "客户主数据快照缺失(已按人工录入信息提交,请确认无误)" : "客户主数据快照缺失"));
            }
            if (dataWarehouseService.contribution(app.getCustomerNo()).isEmpty()) {
                items.add(precheckItem("CONTRIBUTION_EXISTS", "WARN", app.getCustomerNo(), "客户贡献度数据缺失"));
            }
        }
        // 数据时效:最新批次距当前超过容忍天数 BLOCK 阻断提交(§9.4)
        LocalDate staleBefore = LocalDate.now().minusDays(dataStaleDays);
        for (Map.Entry<String, String> e : latest.entrySet()) {
            LocalDate dt = LocalDate.parse(e.getValue().substring(0, 10));
            if (dt.isBefore(staleBefore)) {
                items.add(precheckItem("DATA_TIMELINESS", "BLOCK", e.getKey(),
                        "数据源数据日期过期,请联系数据中心刷新(数据集最新批次 " + dt
                                + ",容忍 " + dataStaleDays + " 个自然日)"));
            }
        }
        if (items.isEmpty()) {
            items.add(precheckItem("PRECHECK", "PASS", null, "质量预校验通过"));
        }
        return items;
    }

    private SubmitCheckResponse.QualityPrecheckItem precheckItem(String rule, String level, String subjectId, String message) {
        SubmitCheckResponse.QualityPrecheckItem item = new SubmitCheckResponse.QualityPrecheckItem();
        item.setRuleCode(rule);
        item.setLevel(level);
        item.setSubjectId(subjectId);
        item.setMessage(message);
        return item;
    }

    // ==================== 提交(§7.1 步骤7-11) ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubmitResponse submit(Long id) {
        applicationAccessService.requireOwner(id);
        CcrApplication app = requireApplication(id);
        // a) 状态守卫:仅 DRAFT 可提交;重复提交幂等返回既有结果
        if (!ApplicationStatus.DRAFT.getCode().equals(app.getStatus())) {
            if (app.getSubmitTime() != null) {
                return buildSubmitResponse(app, pricingItems(id), false);
            }
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "当前状态不可提交(" + app.getStatus() + ")");
        }

        List<CcrPricingItem> items = routableItems(id);
        // b0) 新增客户无客户号(§2026-08-20 #017):先按证件号反查数仓回填真实客户号(未命中回填占位号),
        //     补号后走完整性校验/快照采集/审批/承诺,保证全链路客户号一致
        resolvePlaceholderCustomerNo(app, items);
        // b0-集团) 集团成员占位号回填(§2026-08-20 #017,与单户对称):补录成员按 ucrCode 反查数仓回填真实号,
        //     未命中保留占位号(memberValid 对 NEW 前缀放行);须在 persistGroupSupplement 之前,落手工表用真实号
        resolveGroupMemberPlaceholder(app, items);
        // b) 完整性校验
        checkCompleteness(app, items);
        // b1) 提交时落表(§docs/19 §4.6):解析 group_info_json 补录数据落 ccr_group/ccr_group_member(幂等、数仓优先、最新覆盖)
        persistGroupSupplement(app);
        // c) 集团场景校验(返回申请额度供路由定档,§B18)
        BigDecimal groupCreditTotal = checkGroupConstraints(app);
        // d) 一合同一有效分项/一账户一有效分项(跨申请,非终态阻断)
        checkCarrierUniqueness(app, items);
        // e) 逐分项硬边界(突破阻断)
        for (CcrPricingItem item : items) {
            ruleEngine.checkHardBoundary(businessBigType(app), item.getProductCode(), item.getRequestedRate());
        }
        // e2) 存款起点利率硬边界(2026-08-27 用户拍板):申请利率必须严格高于矩阵起点利率(挂牌价)才能提交
        checkDepositStartRate(app, items, groupCreditTotal);
        // 主申请先置 SUBMITTED(§7.2 步骤6 中间态:校验通过、快照采集/路由前),路由完成后置 ROUTING
        applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                .eq(CcrApplication::getId, id)
                .set(CcrApplication::getStatus, ApplicationStatus.SUBMITTED.getCode()));
        // g) 冻结 LPR 版本/规则集版本/路由生效日期(§8.4)
        CcrLprVersion lpr = currentLpr();
        CcrRateRuleSet ruleSet = currentRuleSet();
        LocalDateTime routeAsOfDate = LocalDateTime.now();

        // f) 采集快照:创建包→按主体采集记录→关系链→校验(BLOCK 回滚)→冻结→绑定申请
        Long bundleId = snapshotGateway.createBundle(id);
        SnapshotCollect collect = collectSnapshot(app, items, bundleId);
        snapshotGateway.addRelations(bundleId, collect.relations);
        backfillCarrierSnapshot(items, collect);
        String quality = snapshotGateway.validate(bundleId);
        if ("BLOCK".equals(quality)) {
            throw new ServiceException(ErrorCode.QUALITY_BLOCK.getCode(), "数据质量阻断,不能提交");
        }
        SnapshotBundleResult bundle = snapshotGateway.freeze(bundleId);

        // h) 逐分项算路由:置 ROUTING + 首节点 BRANCH_MANAGER + 终审岗位 + 冻结边界/矩阵行号(§8.6)
        //    整单交付改造:分项路由字段保留冻结(审计溯源),审批推进以申请单整单链为准
        Map<String, Map<String, Object>> corpCache = new HashMap<>();
        List<SubmitResponse.ItemRoute> itemRoutes = new ArrayList<>();
        CcrPricingItem chainAnchorItem = null;
        RouteResult chainAnchorRoute = null;
        BigDecimal anchorRate = null;
        boolean isLoan = "LOAN".equals(app.getBusinessType());
        for (CcrPricingItem item : items) {
            RouteResult route = rateMatrixRouter.calcRoute(buildRouteInput(app, item, groupCreditTotal, corpCache));
            item.setStatus(PricingItemStatus.ROUTING.getCode());
            item.setStartNodeCode(route.getStartNodeCode());
            item.setCurrentNodeCode(route.getStartNodeCode());
            item.setRouteCode(route.getFinalNodeCode());
            item.setBoundaryRate(route.getBoundaryRate());
            item.setMatchedMatrixNo(route.getMatchedMatrixNo());
            // 部门归属(矩阵透出,提交冻结;§D16a 部门分流,节点处理人按分项 dept_code 解析)
            item.setDeptCode(route.getDeptCode());
            // 完整审批链路冻结(§8.6):审批推进沿此链,保证与提交预览一致(矩阵驱动,可跳过无权限节点如GM)
            item.setRouteChain(JSONUtil.toJsonStr(route.getRouteChain()));
            pricingItemMapper.updateById(item);
            itemRoutes.add(toItemRoute(item, route.getRouteChain()));
            // 整单定链分项:贷款取利率最低分项(流程最深),存款取任一(链相同,取首个即可)
            if (chainAnchorItem == null) {
                chainAnchorItem = item;
                chainAnchorRoute = route;
                anchorRate = item.getRequestedRate();
            } else if (isLoan && item.getRequestedRate() != null
                    && (anchorRate == null || item.getRequestedRate().compareTo(anchorRate) < 0)) {
                chainAnchorItem = item;
                chainAnchorRoute = route;
                anchorRate = item.getRequestedRate();
            }
        }
        // 整单链冻结到申请单(贷款=利率最低分项,存款=原流程;审批推进以此为准,§2026-08-29 整单交付)
        if (chainAnchorRoute != null) {
            app.setRouteCode(chainAnchorRoute.getFinalNodeCode());
            app.setRouteChain(JSONUtil.toJsonStr(chainAnchorRoute.getRouteChain()));
            app.setStartNodeCode(chainAnchorRoute.getStartNodeCode());
            app.setCurrentNodeCode(chainAnchorRoute.getStartNodeCode());
            app.setBoundaryRate(chainAnchorRoute.getBoundaryRate());
            app.setMatchedMatrixNo(chainAnchorRoute.getMatchedMatrixNo());
            app.setDeptCode(chainAnchorRoute.getDeptCode());
        }

        // i) 主申请置 ROUTING、冻结版本、写提交时间(freeze 已绑定快照包,重取避免乐观锁过期)
        CcrApplication fresh = applicationMapper.selectById(id);
        fresh.setStatus(ApplicationStatus.ROUTING.getCode());
        fresh.setSubmitTime(LocalDateTime.now());
        fresh.setLprVersionId(lpr.getId());
        fresh.setRuleSetVersionId(ruleSet == null ? null : ruleSet.getId());
        fresh.setRouteAsOfDate(routeAsOfDate);
        fresh.setSnapshotBundleId(bundle.getBundleId());
        // 整单路由字段随主申请落库(整单交付改造;贷款=利率最低分项,存款=原流程)
        fresh.setRouteCode(app.getRouteCode());
        fresh.setRouteChain(app.getRouteChain());
        fresh.setStartNodeCode(app.getStartNodeCode());
        fresh.setCurrentNodeCode(app.getCurrentNodeCode());
        fresh.setBoundaryRate(app.getBoundaryRate());
        fresh.setMatchedMatrixNo(app.getMatchedMatrixNo());
        fresh.setDeptCode(app.getDeptCode());
        applicationMapper.updateById(fresh);

        // 审计留痕(§15.2):提交核心字段快照(主单+分项要素),同事务写入
        writeSubmitAudit(fresh, items);

        // j) 同事务写 Outbox 事件(§3.5/§7.2 步骤7):逐分项 FLOW_START + 提交通知 NOTIFY,异步消费
        publishSubmitEvents(fresh, items);

        SubmitResponse response = buildSubmitResponse(fresh, items, true);
        response.setItems(itemRoutes);
        return response;
    }

    /**
     * b0) 新增客户无客户号支持(2026-08-20 #017):
     * 单户场景 customer_no 为空时,按 customer_info_json 证件号反查数仓 caps_*_cust_basic_info.cert_no:
     *   - 命中 → 回填真实客户号(ccr_application.customer_no + 各分项 pricing_customer_no),快照/审批/决议/承诺全链路一致
     *   - 未命中 → 回填占位号(NEW+证件后6位),走人工快照(MANUAL)通道,WARN 放行
     * 集团场景成员客户号必填,无此问题。补号在 checkCompleteness 之前,使"单户场景客户号必填"校验自然通过。
     */
    private void resolvePlaceholderCustomerNo(CcrApplication app, List<CcrPricingItem> items) {
        if ("GROUP".equals(app.getCustomerScope()) || StrUtil.isNotBlank(app.getCustomerNo())) {
            return; // 集团(成员号必填)或已有客户号,无需占位处理
        }
        String certNo = CustomerNoUtil.certNoFromInfoJson(app.getCustomerInfoJson(), app.getCustomerScope());
        if (StrUtil.isBlank(certNo)) {
            return; // 无客户号也无证件号:后续 checkCompleteness 拦截提示"客户号必填"
        }
        Map<String, Object> dw = "INDIVIDUAL".equals(app.getCustomerScope())
                ? dataWarehouseService.findIndvByCertNo(certNo)
                : dataWarehouseService.findCorpByCertNo(certNo);
        String resolvedNo = dw == null ? CustomerNoUtil.placeholderCustomerNo(certNo)
                : String.valueOf(dw.get("cust_no"));

        // 回填主申请 customer_no
        app.setCustomerNo(resolvedNo);
        applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                .eq(CcrApplication::getId, app.getId())
                .set(CcrApplication::getCustomerNo, resolvedNo));

        // 同步分项 pricing_customer_no(保存草稿时已生成占位号,替换为真实号/确认占位号)
        for (CcrPricingItem item : items) {
            if (CustomerNoUtil.isPlaceholder(item.getPricingCustomerNo())) {
                item.setPricingCustomerNo(resolvedNo);
                pricingItemMapper.updateById(item);
            }
        }

        // 同步人工快照 JSON 的 customerNo(审批详情 overwriteCustomer 仅非空覆盖,保证展示真实号/占位号)
        if (StrUtil.isNotBlank(app.getCustomerInfoJson())) {
            try {
                JSONObject json = JSONUtil.parseObj(app.getCustomerInfoJson());
                json.set("customerNo", resolvedNo);
                app.setCustomerInfoJson(json.toString());
                applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                        .eq(CcrApplication::getId, app.getId())
                        .set(CcrApplication::getCustomerInfoJson, json.toString()));
            } catch (Exception e) {
                log.warn("回填 customer_info_json.customerNo 失败,忽略:{}", e.getMessage());
            }
        }
    }

    /**
     * b0-集团) 集团成员占位号回填(2026-08-20 #017,与单户对称)。
     *
     * <p>前端补录新增客户成员(有证件号无客户号)时生成 {@code NEW+完整证件号} 占位号落
     * {@code ccr_application_member};提交时从 {@code group_info_json.supplementMembers[].ucrCode}
     * 取证件号 → {@code findCorpByCertNo} 反查数仓 → 命中回填真实客户号,未命中保留占位号
     * ({@link #memberValid} 对 NEW 前缀放行,审批中可回填)。须在 {@link #persistGroupSupplement}
     * 之前执行,保证手工集团落表/快照/承诺全链路客户号一致。</p>
     */
    private void resolveGroupMemberPlaceholder(CcrApplication app, List<CcrPricingItem> items) {
        if (!"GROUP".equals(app.getCustomerScope()) || StrUtil.isBlank(app.getGroupInfoJson())) {
            return;
        }
        // 1) 解析 group_info_json 补录成员:占位号 → 证件号(ucrCode)
        JSONObject json;
        Map<String, String> certByPlaceholder = new HashMap<>();
        try {
            json = JSONUtil.parseObj(app.getGroupInfoJson());
            JSONArray supplementMembers = json.getJSONArray("supplementMembers");
            if (supplementMembers != null) {
                for (int i = 0; i < supplementMembers.size(); i++) {
                    JSONObject m = supplementMembers.getJSONObject(i);
                    String no = m.getStr("memberCustomerNo");
                    String certNo = m.getStr("ucrCode");
                    if (CustomerNoUtil.isPlaceholder(no) && StrUtil.isNotBlank(certNo)) {
                        certByPlaceholder.put(no, certNo.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 group_info_json 补录成员证件号失败,集团占位回填跳过:{}", e.getMessage());
            return;
        }
        if (certByPlaceholder.isEmpty()) {
            return;
        }
        // 2) 遍历占位成员:反查数仓 → 回填 member 表(未命中保留占位号,memberValid 放行)
        Map<String, String> resolvedMap = new HashMap<>();
        for (CcrApplicationMember member : applicationMembers(app.getId())) {
            String certNo = certByPlaceholder.get(member.getMemberCustomerNo());
            if (StrUtil.isBlank(certNo)) {
                continue;
            }
            Map<String, Object> dw = dataWarehouseService.findCorpByCertNo(certNo);
            if (dw == null || dw.get("cust_no") == null) {
                continue;
            }
            String resolved = String.valueOf(dw.get("cust_no"));
            if (resolved.equals(member.getMemberCustomerNo())) {
                continue;
            }
            resolvedMap.put(member.getMemberCustomerNo(), resolved);
            member.setMemberCustomerNo(resolved);
            applicationMemberMapper.updateById(member);
        }
        if (resolvedMap.isEmpty()) {
            return;
        }
        // 3) 分项同步(集团分项 member_customer_no = pricing_customer_no = 成员号,占位→真实)
        for (CcrPricingItem item : items) {
            String resolved = resolvedMap.get(item.getMemberCustomerNo());
            if (StrUtil.isBlank(resolved)) {
                continue;
            }
            item.setMemberCustomerNo(resolved);
            item.setPricingCustomerNo(resolved);
            pricingItemMapper.updateById(item);
        }
        // 4) group_info_json.supplementMembers 占位号替换为真实号(使 persistGroupSupplement 落手工表用真实号)
        try {
            JSONArray supplementMembers = json.getJSONArray("supplementMembers");
            if (supplementMembers != null) {
                for (int i = 0; i < supplementMembers.size(); i++) {
                    JSONObject m = supplementMembers.getJSONObject(i);
                    String resolved = resolvedMap.get(m.getStr("memberCustomerNo"));
                    if (StrUtil.isNotBlank(resolved)) {
                        m.set("memberCustomerNo", resolved);
                    }
                }
            }
            app.setGroupInfoJson(json.toString());
            applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                    .eq(CcrApplication::getId, app.getId())
                    .set(CcrApplication::getGroupInfoJson, json.toString()));
        } catch (Exception e) {
            log.warn("同步 group_info_json 补录成员客户号失败,忽略:{}", e.getMessage());
        }
    }

    /**
     * j) 同事务写 Outbox 事件(§3.5/§7.2 步骤7):整单交付改造后 FLOW_START 逐分项一条改整单一条
     * (business_id=applicationNo,Warm-Flow 流程实例按申请单一个),以及提交通知 NOTIFY(申请人 + 首节点支行行长);
     * 事件写入失败随提交事务整体回滚,不出现半成品
     */
    private void publishSubmitEvents(CcrApplication app, List<CcrPricingItem> items) {
        String createBy = app.getApplicantUserId() == null ? "0" : app.getApplicantUserId().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", app.getId());
        payload.put("applicationNo", app.getApplicationNo());
        payload.put("nodeCode", app.getStartNodeCode());
        payload.put("routeCode", app.getRouteCode());
        // 流程定义版本:利率审批标准流程(Warm-Flow 轨迹载体)
        payload.put("flowCode", "rate_approval");
        payload.put("createBy", createBy);
        outboxService.publish(OutboxEventType.FLOW_START, app.getApplicationNo(), JSONUtil.toJsonStr(payload));
        // 提交通知:申请人 + 首节点审批人(支行行长);messageKey 幂等防重
        String itemNos = items.stream().map(CcrPricingItem::getPricingItemNo).reduce((a, b) -> a + "," + b).orElse("");
        Map<String, Object> applicantNotify = new LinkedHashMap<>();
        applicantNotify.put("recipientType", "USER");
        applicantNotify.put("recipientId", createBy);
        applicantNotify.put("channel", "SYSTEM");
        applicantNotify.put("messageKey", "SUBMIT_NOTIFY:APP:" + app.getId() + ":APPLICANT");
        applicantNotify.put("content", "您提交的定价申请 " + app.getApplicationNo() + " 已进入审批(分项:"
                + itemNos + "),首节点:支行行长");
        outboxService.publish(OutboxEventType.NOTIFY, "SUBMIT:APP:" + app.getId() + ":APPLICANT",
                JSONUtil.toJsonStr(applicantNotify));

        Map<String, Object> branchNotify = new LinkedHashMap<>();
        branchNotify.put("recipientType", "BRANCH_MANAGER");
        branchNotify.put("orgId", app.getApplicantOrgId());
        branchNotify.put("channel", "SYSTEM");
        branchNotify.put("messageKey", "SUBMIT_NOTIFY:APP:" + app.getId() + ":BRANCH_MANAGER");
        branchNotify.put("content", "定价申请 " + app.getApplicationNo() + " 已提交,待支行行长审批(分项:"
                + itemNos + ")");
        outboxService.publish(OutboxEventType.NOTIFY, "SUBMIT:APP:" + app.getId() + ":BRANCH_MANAGER",
                JSONUtil.toJsonStr(branchNotify));
    }

    /** 提交审计留痕(§15.2):主单+分项核心要素 JSON 快照;写入失败不阻断提交 */
    private void writeSubmitAudit(CcrApplication app, List<CcrPricingItem> items) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("applicationNo", app.getApplicationNo());
            snap.put("businessType", app.getBusinessType());
            snap.put("customerScope", app.getCustomerScope());
            snap.put("customerNo", app.getCustomerNo());
            snap.put("groupNo", app.getGroupNo());
            snap.put("submitTime", app.getSubmitTime() == null ? null : app.getSubmitTime().toString());
            List<Map<String, Object>> pis = new ArrayList<>();
            for (CcrPricingItem it : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pricingItemNo", it.getPricingItemNo());
                m.put("carrierType", it.getPricingCarrierType());
                m.put("productCode", it.getProductCode());
                m.put("pricingAmount", it.getPricingAmount());
                m.put("term", it.getTermValue() + it.getTermUnit());
                m.put("requestedRate", it.getRequestedRate());
                m.put("originalRate", it.getOriginalRate());
                m.put("deptCode", it.getDeptCode());
                pis.add(m);
            }
            snap.put("items", pis);
            String content = JSONUtil.toJsonStr(snap);
            Long applicantId = app.getApplicantUserId();
            String operatorName = applicantId == null ? null
                    : jdbcTemplate.queryForList(
                            "SELECT nick_name FROM ccr_sys_user WHERE id = ? AND del_flag = '0'",
                            String.class, applicantId).stream().findFirst().orElse(null);
            jdbcTemplate.update("""
                            INSERT INTO ccr_audit_log
                            (id, log_type, biz_id, content, operator_id, operator_name, operate_time)
                            VALUES (?, 'APPLY_SUBMIT', ?, ?, ?, ?, ?)
                            """,
                    IdUtil.getSnowflakeNextId(), String.valueOf(app.getId()), content,
                    applicantId == null ? 0L : applicantId, operatorName, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("提交审计留痕写入失败(不影响提交): {}", e.getMessage());
        }
    }

    /** b) 完整性:分项非空、客户/集团字段齐、分项必填字段齐、载体关系齐 */
    private void checkCompleteness(CcrApplication app, List<CcrPricingItem> items) {
        if (items.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "定价分项不能为空,无法提交");
        }
        boolean groupScope = "GROUP".equals(app.getCustomerScope());
        if (groupScope) {
            if (StrUtil.isBlank(app.getGroupNo())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "集团场景集团客户编号必填");
            }
            if (applicationMembers(app.getId()).isEmpty()) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "集团场景涉及成员不能为空");
            }
        } else if (StrUtil.isBlank(app.getCustomerNo())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "单户场景客户号必填");
        }
        for (CcrPricingItem item : items) {
            // 存款无期限产品(2026-08-26 修复):协定存款与银票/信用证保证金无固定期限,期限可空;
            // 与前端 deposit.vue termRequired 口径一致(仅对公定期/通知存款强制期限)。贷款等非存款载体仍强制期限必填。
            boolean isDeposit = "DEPOSIT_ACCOUNT".equals(item.getPricingCarrierType());
            boolean termRequired = !isDeposit
                    || "CORP_TIME_DEPOSIT".equals(item.getProductCode())
                    || "NOTICE_DEPOSIT".equals(item.getProductCode());
            boolean missing = item.getRequestedRate() == null || item.getPricingAmount() == null
                    || StrUtil.isBlank(item.getProductCode());
            if (termRequired) {
                missing = missing || item.getTermValue() == null || StrUtil.isBlank(item.getTermUnit());
            }
            if (missing) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "分项[" + item.getPricingItemNo() + "]必填字段不全(产品/期限/金额/申请利率)");
            }
            if (groupScope && StrUtil.isBlank(item.getMemberCustomerNo())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "分项[" + item.getPricingItemNo() + "]缺少集团成员客户号");
            }
            if ("LOAN_CONTRACT".equals(item.getPricingCarrierType())) {
                // 需求②(2026-08-24):存量利率申请按担保项拆分,分项可不挂合同关系(plannedContractFlag='N'),
                // 放开"必须有合同 rel"阻断;新增拟签(PLANNED)仍建合同关系;两者均走重复申请防重(口径=客户+担保措施)
                checkDuplicateContractApplication(app, item);
            }
            if ("DEPOSIT_ACCOUNT".equals(item.getPricingCarrierType())) {
                Long cnt = depositRelMapper.selectCount(new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                        .eq(CcrPricingItemDepositRel::getPricingItemId, item.getId()));
                if (cnt == 0) {
                    throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                            "分项[" + item.getPricingItemNo() + "]缺少存款账户关系");
                }
            }
        }
        // 拟达成贡献度承诺:截止日期必填(§7.1 提交校验;草稿保存 saveCommitments 不强制,仅提交时把关)
        checkCommitmentCompleteness(app);
    }

    /** 拟达成贡献度承诺完整性(§7.1):已录承诺的截止日期(end_date)必填,缺失阻断提交 */
    private void checkCommitmentCompleteness(CcrApplication app) {
        List<CcrApplicationCommitment> commitments = commitmentMapper.selectList(
                new LambdaQueryWrapper<CcrApplicationCommitment>()
                        .eq(CcrApplicationCommitment::getApplicationId, app.getId()));
        for (CcrApplicationCommitment c : commitments) {
            if (c.getEndDate() == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "拟达成贡献度承诺缺少截止日期,请补录承诺截止日期后提交");
            }
        }
    }

    /**
     * 贷款重复申请校验(需求②):存量调息带数仓拆分项编号(source_split_no)时按拆分项精确防重
     * (同一拆分项已有在途调息申请即阻断);拆分项为空(新增授信/存量手工补录)回退「客户+担保方式」兜底
     * (同一客户同一担保方式非信用类已有进行中申请阻断)。拟签订(planned='Y' 或无正式合同号)不构成重复;
     * 重提 reapply 源申请已终态,天然豁免。进行中 = 状态不在终态集(DRAFT 未提交/FINAL/VETOED/REJECTED/CLOSED)。
     */
    private void checkDuplicateContractApplication(CcrApplication app, CcrPricingItem item) {
        if (StrUtil.isNotBlank(item.getSourceSplitNo())) {
            List<Map<String, Object>> splitRows = jdbcTemplate.queryForList(
                    """
                    SELECT a.application_no
                    FROM ccr_pricing_item pi
                    JOIN ccr_application a ON a.id = pi.application_id
                    WHERE pi.source_split_no = ?
                      AND a.id != ?
                      AND a.del_flag = '0' AND pi.del_flag = '0'
                      AND a.status NOT IN ('DRAFT','FINAL','VETOED','REJECTED','CLOSED')
                    """,
                    item.getSourceSplitNo(), app.getId());
            if (!splitRows.isEmpty()) {
                String inAppNo = splitRows.get(0).get("application_no") == null ? ""
                        : splitRows.get(0).get("application_no").toString();
                throw new ServiceException(ErrorCode.DUPLICATE_APPLICATION.getCode(),
                        "拆分项[" + item.getSourceSplitNo() + "]已有进行中调息申请(" + inAppNo + "),请勿重复申请");
            }
            return;
        }
        String customerNo = StrUtil.blankToDefault(item.getMemberCustomerNo(), app.getCustomerNo());
        if (StrUtil.isBlank(customerNo)) {
            return;
        }
        // 担保措施:分项担保组合主担保类型;信用类无担保措施,不参与防重
        CcrGuaranteePackage pkg = item.getGuaranteePackageId() == null ? null
                : guaranteePackageMapper.selectById(item.getGuaranteePackageId());
        if (pkg == null || StrUtil.isBlank(pkg.getMainGuaranteeType()) || "CREDIT".equals(pkg.getMainGuaranteeType())) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT a.application_no
                FROM ccr_guarantee_package gp
                JOIN ccr_pricing_item pi ON pi.id = gp.pricing_item_id
                JOIN ccr_application a ON a.id = pi.application_id
                WHERE (a.customer_no = ? OR pi.member_customer_no = ?)
                  AND gp.main_guarantee_type = ?
                  AND a.id != ?
                  AND a.del_flag = '0' AND pi.del_flag = '0'
                  AND a.status NOT IN ('DRAFT','FINAL','VETOED','REJECTED','CLOSED')
                """,
                customerNo, customerNo, pkg.getMainGuaranteeType(), app.getId());
        if (!rows.isEmpty()) {
            String inAppNo = rows.get(0).get("application_no") == null ? ""
                    : rows.get(0).get("application_no").toString();
            throw new ServiceException(ErrorCode.DUPLICATE_APPLICATION.getCode(),
                    "客户[" + customerNo + "]的「" + pkg.getMainGuaranteeType() + "」担保措施已有进行中申请("
                            + inAppNo + "),请勿重复申请");
        }
    }

    /**
     * c) 集团校验(§docs/19 §4.7):集团主数据存在、成员在团、申请额度必填 + 成员申请金额合计≤本次申请额度。
     * 数据以数仓为准:数仓收录=存量(数仓优先),数仓无=新增(补录数据生效,豁免存量校验);
     * 申请额度为本次新增授信(必填),随申请存 group_info_json 多条并存,数仓批复授信仅展示参考不参与勾稽。
     *
     * @return 本次申请额度(路由展示/定档基准,§B18)
     */
    private BigDecimal checkGroupConstraints(CcrApplication app) {
        if (!"GROUP".equals(app.getCustomerScope())) {
            return null;
        }
        if (!groupExistsForSubmit(app)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "集团[" + app.getGroupNo() + "]主数据缺失(请先补录集团信息)");
        }
        boolean newGroup = dataWarehouseService.findGroup(app.getGroupNo()) == null;
        BigDecimal applyAmount = applyAmountOf(app);
        if (applyAmount == null) {
            throw new ServiceException(ErrorCode.LIMIT_INCONSISTENT.getCode(),
                    "请录入本次申请额度(集团新增授信,必填)");
        }
        // 成员存在性:数仓成员快照 ∪ 手工成员,任一侧在团即放行;新增集团成员手工录入即放行
        Map<String, Map<String, Object>> dwMemberMap = dwMemberMap(app.getGroupNo());
        Map<String, CcrGroupMember> manualMemberMap = manualMemberMap(app.getGroupNo());
        for (CcrApplicationMember member : applicationMembers(app.getId())) {
            if (!newGroup && !memberValid(dwMemberMap, manualMemberMap, member.getMemberCustomerNo())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "成员[" + member.getMemberCustomerNo() + "]不在集团有效成员快照中(可手工补录成员)");
            }
        }
        // 额度勾稽:成员申请金额合计 ≤ 本次申请额度(所有集团申请统一)
        BigDecimal allocatedSum = applicationMembers(app.getId()).stream()
                .filter(m -> m.getRequestAmount() != null)
                .map(CcrApplicationMember::getRequestAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocatedSum.compareTo(applyAmount) > 0) {
            throw new ServiceException(ErrorCode.LIMIT_INCONSISTENT.getCode(),
                    "成员申请金额合计 " + allocatedSum + " 超过本次申请额度 " + applyAmount);
        }
        return applyAmount;
    }

    /** d) 一合同/一账户一有效分项:同载体存在非终态分项则阻断(跨申请) */
    private void checkCarrierUniqueness(CcrApplication app, List<CcrPricingItem> items) {
        for (CcrPricingItem item : items) {
            if ("LOAN_CONTRACT".equals(item.getPricingCarrierType())) {
                List<CcrPricingItemContractRel> rels = contractRelMapper.selectList(
                        new LambdaQueryWrapper<CcrPricingItemContractRel>()
                                .eq(CcrPricingItemContractRel::getPricingItemId, item.getId()));
                for (CcrPricingItemContractRel rel : rels) {
                    List<CcrPricingItemContractRel> conflicts = contractRelMapper.selectList(
                            new LambdaQueryWrapper<CcrPricingItemContractRel>()
                                    .eq(CcrPricingItemContractRel::getContractBusinessKey, rel.getContractBusinessKey())
                                    .ne(CcrPricingItemContractRel::getPricingItemId, item.getId()));
                    blockIfNonTerminal(conflicts.stream().map(CcrPricingItemContractRel::getPricingItemId).toList(),
                            "合同[" + rel.getContractBusinessKey() + "]");
                }
            }
            if ("DEPOSIT_ACCOUNT".equals(item.getPricingCarrierType())) {
                List<CcrPricingItemDepositRel> rels = depositRelMapper.selectList(
                        new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                                .eq(CcrPricingItemDepositRel::getPricingItemId, item.getId()));
                for (CcrPricingItemDepositRel rel : rels) {
                    if (StrUtil.isBlank(rel.getDepositAccountNo())) {
                        continue; // 拟开户无账号,不参与唯一性
                    }
                    List<CcrPricingItemDepositRel> conflicts = depositRelMapper.selectList(
                            new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                                    .eq(CcrPricingItemDepositRel::getDepositAccountNo, rel.getDepositAccountNo())
                                    .ne(CcrPricingItemDepositRel::getPricingItemId, item.getId()));
                    blockIfNonTerminal(conflicts.stream().map(CcrPricingItemDepositRel::getPricingItemId).toList(),
                            "存款账号[" + rel.getDepositAccountNo() + "]");
                }
            }
        }
    }

    /** 存在非终态分项占用同载体则阻断 */
    private void blockIfNonTerminal(List<Long> conflictItemIds, String carrierDesc) {
        if (conflictItemIds.isEmpty()) {
            return;
        }
        List<CcrPricingItem> others = pricingItemMapper.selectBatchIds(conflictItemIds);
        for (CcrPricingItem other : others) {
            // DRAFT(草稿未提交)未进入审批,不算在途,不阻断(§7.1 一合同/账户一有效分项仅约束已提交在途)
            if (PricingItemStatus.DRAFT.getCode().equals(other.getStatus())) {
                continue;
            }
            if (!ITEM_TERMINAL_STATUS.contains(other.getStatus())) {
                throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                        carrierDesc + "已存在在途定价分项[" + other.getPricingItemNo() + "](状态 "
                                + other.getStatus() + "),一合同/账户只允许一个有效分项");
            }
        }
    }

    // ---------- 快照采集(§A.6) ----------

    private static class SnapshotCollect {
        private final List<SnapshotRelationInput> relations = new ArrayList<>();
        /** 合同号→合同快照记录id */
        private final Map<String, Long> contractRecordIds = new HashMap<>();
        /** 存款账号→账户快照记录id */
        private final Map<String, Long> depositRecordIds = new HashMap<>();
    }

    /** 按主体从数仓最新批次采集快照记录并登记关系链 */
    private SnapshotCollect collectSnapshot(CcrApplication app, List<CcrPricingItem> items, Long bundleId) {
        SnapshotCollect collect = new SnapshotCollect();
        boolean groupScope = "GROUP".equals(app.getCustomerScope());
        if (groupScope) {
            collectGroupChain(app, bundleId, collect);
        } else {
            collectSingleCustomer(app, bundleId, collect);
        }
        // 存款账户快照(按分项账户关系引用的明文账号)
        for (CcrPricingItem item : items) {
            if (!"DEPOSIT_ACCOUNT".equals(item.getPricingCarrierType())) {
                continue;
            }
            List<CcrPricingItemDepositRel> rels = depositRelMapper.selectList(
                    new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                            .eq(CcrPricingItemDepositRel::getPricingItemId, item.getId()));
            for (CcrPricingItemDepositRel rel : rels) {
                if (StrUtil.isBlank(rel.getDepositAccountNo())
                        || collect.depositRecordIds.containsKey(rel.getDepositAccountNo())) {
                    continue;
                }
                Map<String, Object> account = dataWarehouseService.findDepositAccountByNo(rel.getDepositAccountNo());
                if (account != null) {
                    Long recordId = addSnapshotRecord(bundleId, "dw_deposit_account_snapshot", "DEPOSIT_ACCOUNT",
                            String.valueOf(account.get("customer_no")), account);
                    collect.depositRecordIds.put(rel.getDepositAccountNo(), recordId);
                }
            }
        }
        return collect;
    }

    /** 集团链采集:集团→成员→额度→分项→合同→借据 */
    private void collectGroupChain(CcrApplication app, Long bundleId, SnapshotCollect collect) {
        Map<String, Object> group = dataWarehouseService.findGroup(app.getGroupNo());
        // 手工集团(数仓未统计)回退 ccr_group 构造集团快照行,保证 GROUP 记录与 GROUP_TO_MEMBER 关系成立
        Long groupRecordId = group != null
                ? addSnapshotRecord(bundleId, "dw_customer_group_snapshot", "GROUP", app.getGroupNo(), group)
                : addManualGroupRecord(bundleId, app.getGroupNo());
        Map<String, Object> credit = dataWarehouseService.findGroupCredit(app.getGroupNo());
        if (groupRecordId != null && credit != null) {
            Long creditRecordId = addSnapshotRecord(bundleId, "dw_group_credit_snapshot", "GROUP_CREDIT",
                    String.valueOf(credit.get("group_credit_no")), credit);
            collect.relations.add(new SnapshotRelationInput(groupRecordId, creditRecordId, "GROUP_TO_CREDIT", 1));
        }
        String groupCreditNo = credit == null ? null : String.valueOf(credit.get("group_credit_no"));
        int memberSeq = 0;
        for (CcrApplicationMember member : applicationMembers(app.getId())) {
            memberSeq++;
            String memberNo = member.getMemberCustomerNo();
            // 成员快照(补充 record_status/valid_to 供快照质量规则判定成员有效性;数仓无成员时手工成员回退)
            Map<String, Object> dwMember = dataWarehouseService.findGroupMember(app.getGroupNo(), memberNo);
            Long memberRecordId = null;
            Map<String, Object> core = dwMember != null ? new LinkedHashMap<>(dwMember)
                    : manualMemberCore(app.getGroupNo(), memberNo);
            if (core != null) {
                core.put("record_status", memberInGroup(core) ? "ACTIVE" : "INACTIVE");
                if (core.get("relation_end") != null) {
                    core.put("valid_to", String.valueOf(core.get("relation_end")).substring(0, 10));
                }
                memberRecordId = addSnapshotRecord(bundleId, "dw_customer_group_member_snapshot", "MEMBER", memberNo, core);
                if (groupRecordId != null) {
                    collect.relations.add(new SnapshotRelationInput(groupRecordId, memberRecordId, "GROUP_TO_MEMBER", memberSeq));
                }
            }
            // 成员客户主数据
            Map<String, Object> corp = dataWarehouseService.findCorpCustomer(memberNo);
            if (corp != null) {
                addSnapshotRecord(bundleId, "caps_corp_cust_basic_info", "CORPORATE", memberNo, corp);
            }
            // 成员贡献度
            addContributionRecord(bundleId, memberNo);
            // 成员额度→合同→借据
            if (groupCreditNo == null) {
                continue;
            }
            Map<String, Object> limit = dataWarehouseService.findMemberLimit(groupCreditNo, memberNo);
            if (limit == null) {
                continue;
            }
            Long limitRecordId = addSnapshotRecord(bundleId, "dw_member_credit_limit_snapshot", "MEMBER_LIMIT",
                    String.valueOf(limit.get("member_limit_no")), limit);
            if (memberRecordId != null) {
                collect.relations.add(new SnapshotRelationInput(memberRecordId, limitRecordId, "MEMBER_TO_LIMIT", 1));
            }
            collectContractChain(bundleId, memberNo, limitRecordId, collect);
        }
    }

    /** 合同→借据链(按成员 borrower 直接采合同;存量无分项层,合同直接挂成员额度下) */
    private void collectContractChain(Long bundleId, String memberCustomerNo, Long limitRecordId, SnapshotCollect collect) {
        int contractSeq = 0;
        for (Map<String, Object> contract : dataWarehouseService.contractsByBorrower(memberCustomerNo)) {
            contractSeq++;
            String contractNo = String.valueOf(contract.get("contract_no"));
            // 合同去重(数据源可能存在多批次重复):仅首次采集合同并采集其借据,避免同借据重复采集撞 uk_snapshot_record
            Long contractRecordId = collect.contractRecordIds.computeIfAbsent(contractNo, k -> {
                Long rid = addSnapshotRecord(bundleId, "dw_loan_contract_snapshot", "CONTRACT", contractNo, contract);
                collectNotes(bundleId, contractNo, rid, collect);
                return rid;
            });
            if (limitRecordId != null && contractRecordId != null) {
                collect.relations.add(new SnapshotRelationInput(limitRecordId, contractRecordId, "LIMIT_TO_CONTRACT", contractSeq));
            }
        }
    }

    private void collectNotes(Long bundleId, String contractNo, Long contractRecordId, SnapshotCollect collect) {
        int noteSeq = 0;
        for (Map<String, Object> note : dataWarehouseService.notesByContract(contractNo)) {
            noteSeq++;
            Long noteRecordId = addSnapshotRecord(bundleId, "dw_loan_note_snapshot", "NOTE",
                    String.valueOf(note.get("loan_note_no")), note);
            collect.relations.add(new SnapshotRelationInput(contractRecordId, noteRecordId, "CONTRACT_TO_NOTE", noteSeq));
        }
    }

    /** 单户采集:客户主数据+本行融资+贡献度+名下合同/借据 */
    private void collectSingleCustomer(CcrApplication app, Long bundleId, SnapshotCollect collect) {
        String customerNo = app.getCustomerNo();
        if (StrUtil.isBlank(customerNo)) {
            return;
        }
        Map<String, Object> basic = "INDIVIDUAL".equals(app.getCustomerScope())
                ? dataWarehouseService.findIndvCustomer(customerNo)
                : dataWarehouseService.findCorpCustomer(customerNo);
        if (basic != null) {
            addSnapshotRecord(bundleId,
                    "INDIVIDUAL".equals(app.getCustomerScope()) ? "caps_indv_cust_basic_info" : "caps_corp_cust_basic_info",
                    "INDIVIDUAL".equals(app.getCustomerScope()) ? "INDIVIDUAL" : "CORPORATE", customerNo, basic);
        } else if (StrUtil.isNotBlank(app.getCustomerInfoJson())) {
            // 新增客户:数仓拉不出主数据,以人工录入信息(customer_info_json)构建客户主数据快照,保证快照包非空且审批详情可回溯(§用户要求②)
            cn.hutool.json.JSONObject manual = JSONUtil.parseObj(app.getCustomerInfoJson());
            boolean indv = "INDIVIDUAL".equals(app.getCustomerScope());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cust_no", customerNo);
            row.put(indv ? "cust_nm" : "cust_name", manual.getStr("customerName"));
            row.put("cert_tp", manual.getStr("idType"));
            row.put("cert_no", manual.getStr("idNo"));
            if (indv) {
                row.put("ocupn", manual.getStr("occupation"));
                row.put("whlyr_incm", manual.getStr("annualIncome"));
                row.put("mrrg_sittn", manual.getStr("maritalStatus"));
                row.put("tel_no", manual.getStr("phone"));
            } else {
                row.put("blgd_idsty", manual.getStr("industry"));
                row.put("crdt_grd", manual.getStr("creditLevel"));
            }
            row.put("ffthlv_class", manual.getStr("fiveLevelClass"));
            row.put(indv ? "opnact_org_nm" : "openact_org_nm", manual.getStr("openOrg"));
            row.put(indv ? "opnact_dt" : "openact_dt", manual.getStr("openDate"));
            row.put("cust_class", "NEW");
            row.put("etl_md5", customerNo);
            row.put("data_dt", LocalDate.now().toString());
            row.put("data_source", "MANUAL"); // 人工录入快照标记:审批详情识别为纯人工录入(区别于数仓带出后人工修正)
            addSnapshotRecord(bundleId, indv ? "caps_indv_cust_basic_info" : "caps_corp_cust_basic_info",
                    indv ? "INDIVIDUAL" : "CORPORATE", customerNo, row);
        }
        // 2026-08-11 去冗余:原 dw_own_financing 并入贷款合同,合同快照统一由下方 contractsByBorrower 存 CONTRACT 记录
        addContributionRecord(bundleId, customerNo);
        // 名下合同→借据(合同关系回填与核验数据源)
        for (Map<String, Object> contract : dataWarehouseService.contractsByBorrower(customerNo)) {
            String contractNo = String.valueOf(contract.get("contract_no"));
            // 合同去重(数据源可能存在多批次重复):仅首次采集合同并采集其借据,避免同借据重复采集撞 uk_snapshot_record
            collect.contractRecordIds.computeIfAbsent(contractNo, k -> {
                Long rid = addSnapshotRecord(bundleId, "dw_loan_contract_snapshot", "CONTRACT", contractNo, contract);
                collectNotes(bundleId, contractNo, rid, collect);
                return rid;
            });
        }
    }

    /** 贡献度记录(同客户同批次指标合并为一条快照记录) */
    private void addContributionRecord(Long bundleId, String customerNo) {
        List<Map<String, Object>> rows = dataWarehouseService.contribution(customerNo);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("cust_no", customerNo);
        core.put("metrics", rows);
        SnapshotRecordInput record = new SnapshotRecordInput();
        record.setDatasetCode("dw_contribution_metric");
        record.setSubjectType("CONTRIBUTION");
        record.setSubjectId(customerNo);
        record.setSourceSystemCode("DW");
        record.setSourceRecordId(customerNo + "@" + rows.get(0).get("data_dt"));
        record.setSourceDataDt(DataWarehouseService.rowDataDt(rows.get(0)));
        record.setCoreJson(core);
        snapshotGateway.addRecord(bundleId, record);
    }

    private Long addSnapshotRecord(Long bundleId, String datasetCode, String subjectType, String subjectId,
                                   Map<String, Object> row) {
        SnapshotRecordInput record = new SnapshotRecordInput();
        record.setDatasetCode(datasetCode);
        record.setSubjectType(subjectType);
        record.setSubjectId(subjectId);
        record.setSourceSystemCode("DW");
        record.setSourceRecordId(String.valueOf(row.get("etl_md5")));
        record.setSourceDataDt(DataWarehouseService.rowDataDt(row));
        record.setCoreJson(new LinkedHashMap<>(row));
        return snapshotGateway.addRecord(bundleId, record);
    }

    /** 回填合同/账户快照记录id 到分项载体关系 */
    private void backfillCarrierSnapshot(List<CcrPricingItem> items, SnapshotCollect collect) {
        for (CcrPricingItem item : items) {
            if ("LOAN_CONTRACT".equals(item.getPricingCarrierType())) {
                List<CcrPricingItemContractRel> rels = contractRelMapper.selectList(
                        new LambdaQueryWrapper<CcrPricingItemContractRel>()
                                .eq(CcrPricingItemContractRel::getPricingItemId, item.getId()));
                for (CcrPricingItemContractRel rel : rels) {
                    Long recordId = collect.contractRecordIds.get(rel.getContractBusinessKey());
                    if (recordId == null && StrUtil.isNotBlank(rel.getLoanContractNo())) {
                        recordId = collect.contractRecordIds.get(rel.getLoanContractNo());
                    }
                    if (recordId != null) {
                        rel.setContractSnapshotId(recordId);
                        contractRelMapper.updateById(rel);
                    }
                }
            }
            if ("DEPOSIT_ACCOUNT".equals(item.getPricingCarrierType())) {
                List<CcrPricingItemDepositRel> rels = depositRelMapper.selectList(
                        new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                                .eq(CcrPricingItemDepositRel::getPricingItemId, item.getId()));
                for (CcrPricingItemDepositRel rel : rels) {
                    Long recordId = StrUtil.isBlank(rel.getDepositAccountNo()) ? null
                            : collect.depositRecordIds.get(rel.getDepositAccountNo());
                    if (recordId != null) {
                        rel.setAccountSnapshotId(recordId);
                        depositRelMapper.updateById(rel);
                    }
                }
            }
        }
    }

    // ==================== 关联重提(§7.6) ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrApplication reapply(Long id) {
        applicationAccessService.requireOwner(id);
        CcrApplication source = requireApplication(id);
        if (!REAPPLY_SOURCE_STATUS.contains(source.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅终态/退回申请可关联重提(当前:" + source.getStatus() + ")");
        }

        // 新草稿:复制客户/集团/申请说明,记录来源申请
        CcrApplication target = new CcrApplication();
        target.setBusinessType(source.getBusinessType());
        target.setCustomerScope(source.getCustomerScope());
        target.setCustomerNo(source.getCustomerNo());
        target.setGroupNo(source.getGroupNo());
        target.setApplicantUserId(source.getApplicantUserId());
        target.setApplicantOrgId(source.getApplicantOrgId());
        target.setApplyBranchCode(source.getApplyBranchCode());
        target.setOrgId(source.getOrgId());
        target.setApplicationRemark(source.getApplicationRemark());
        target.setSourceApplicationId(source.getId());
        target.setApplicationNo("CCR" + cn.hutool.core.date.DateUtil.format(new java.util.Date(), "yyyyMMdd")
                + IdUtil.fastSimpleUUID().substring(0, 4).toUpperCase());
        target.setStatus(ApplicationStatus.DRAFT.getCode());
        target.setDataBaselineJson(buildBaselineJson(source));
        // 集团补录/申请额度快照随重提草稿保留(新增集团数仓未收录时,重提免二次补录;§docs/19 §4.5 跨申请带出)
        target.setGroupInfoJson(source.getGroupInfoJson());
        if (target.getVersionNo() == null) {
            target.setVersionNo(1); // 与 DB DEFAULT 一致,保证重提草稿返回体携带版本号
        }
        applicationMapper.insert(target);

        // 复制涉及成员
        for (CcrApplicationMember member : applicationMembers(id)) {
            CcrApplicationMember copy = new CcrApplicationMember();
            copy.setApplicationId(target.getId());
            copy.setMemberCustomerNo(member.getMemberCustomerNo());
            copy.setMemberLimitRef(member.getMemberLimitRef());
            copy.setMemberLimitAmount(member.getMemberLimitAmount());
            copy.setRequestAmount(member.getRequestAmount());
            copy.setCurrency(member.getCurrency());
            copy.setMemberRole(member.getMemberRole());
            applicationMemberMapper.insert(copy);
        }

        // 分项:已批准(FINAL/APPROVED_LEVEL)沿用原决议生成占位(D18b);其余重新生成 DRAFT 分项重走路由
        int inheritCount = 0;
        int rerouteCount = 0;
        Long firstSourceItemId = null;
        Map<Long, Long> itemIdMap = new HashMap<>();
        for (CcrPricingItem src : pricingItems(id)) {
            if (firstSourceItemId == null) {
                firstSourceItemId = src.getId();
            }
            boolean approved = APPROVED_ITEM_STATUS.contains(src.getStatus());
            CcrPricingItem copy = new CcrPricingItem();
            copy.setApplicationId(target.getId());
            copy.setPricingItemNo("PI-" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
            copy.setPricingCustomerNo(src.getPricingCustomerNo());
            copy.setMemberCustomerNo(src.getMemberCustomerNo());
            copy.setPricingCarrierType(src.getPricingCarrierType());
            copy.setProductCode(src.getProductCode());
            copy.setTermValue(src.getTermValue());
            copy.setTermUnit(src.getTermUnit());
            copy.setPricingAmount(src.getPricingAmount());
            copy.setCurrency(src.getCurrency());
            copy.setOriginalRate(src.getOriginalRate());
            copy.setRateDirection(src.getRateDirection());
            copy.setSourcePricingItemId(src.getId());
            if (approved) {
                // 沿用原决议:连同最终利率保留,不重新审批
                copy.setInheritFlag("Y");
                copy.setStatus(PricingItemStatus.FINAL.getCode());
                copy.setFinalRate(src.getFinalRate());
                copy.setRequestedRate(src.getFinalRate() != null ? src.getFinalRate() : src.getRequestedRate());
                copy.setCurrentApprovalRate(copy.getRequestedRate());
                copy.setRouteCode(src.getRouteCode());
                inheritCount++;
            } else {
                copy.setInheritFlag("N");
                copy.setStatus(PricingItemStatus.DRAFT.getCode());
                copy.setRequestedRate(src.getRequestedRate());
                copy.setCurrentApprovalRate(src.getRequestedRate());
                rerouteCount++;
            }
            pricingItemMapper.insert(copy);
            itemIdMap.put(src.getId(), copy.getId());
            copyCarrierRelations(target.getId(), src.getId(), copy.getId());
            copyGuarantee(src, copy);
        }

        // 重提关系(uk_rel 一申请对一行;逐分项沿用标记在 ccr_pricing_item.source_pricing_item_id/inherit_flag)
        CcrApplicationRelation relation = new CcrApplicationRelation();
        relation.setSourceApplicationId(id);
        relation.setTargetApplicationId(target.getId());
        relation.setRelationType("REAPPLY");
        relation.setSourcePricingItemId(firstSourceItemId);
        relation.setInheritFlag(inheritCount > 0 ? "Y" : "N");
        relation.setRemark("关联重提:沿用原决议 " + inheritCount + " 项,重新路由 " + rerouteCount + " 项");
        applicationRelationMapper.insert(relation);

        // 复制拟达成贡献度承诺(分项引用映射到新分项)
        for (CcrApplicationCommitment c : commitmentMapper.selectList(new LambdaQueryWrapper<CcrApplicationCommitment>()
                .eq(CcrApplicationCommitment::getApplicationId, id))) {
            CcrApplicationCommitment copy = new CcrApplicationCommitment();
            copy.setApplicationId(target.getId());
            copy.setPricingItemId(c.getPricingItemId() == null ? null : itemIdMap.get(c.getPricingItemId()));
            copy.setMetricCode(c.getMetricCode());
            copy.setTargetType(c.getTargetType());
            copy.setBaselineValue(c.getBaselineValue());
            copy.setTargetValue(c.getTargetValue());
            copy.setUnit(c.getUnit());
            copy.setMetricScope(c.getMetricScope());
            copy.setMemberCustomerNo(c.getMemberCustomerNo());
            copy.setEndDate(c.getEndDate());
            commitmentMapper.insert(copy);
        }

        // 原申请保持原终态(已否决等)供溯源,重提只创建新申请、不改变原申请状态(§14.1)
        return target;
    }

    /** 复制分项载体关系(合同/存款账户)到新分项 */
    private void copyCarrierRelations(Long targetAppId, Long sourceItemId, Long targetItemId) {
        for (CcrPricingItemContractRel rel : contractRelMapper.selectList(
                new LambdaQueryWrapper<CcrPricingItemContractRel>()
                        .eq(CcrPricingItemContractRel::getPricingItemId, sourceItemId))) {
            CcrPricingItemContractRel copy = new CcrPricingItemContractRel();
            copy.setApplicationId(targetAppId);
            copy.setPricingItemId(targetItemId);
            copy.setContractBusinessKey(rel.getContractBusinessKey());
            copy.setLoanContractNo(rel.getLoanContractNo());
            copy.setPlannedContractFlag(rel.getPlannedContractFlag());
            contractRelMapper.insert(copy);
        }
        for (CcrPricingItemDepositRel rel : depositRelMapper.selectList(
                new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                        .eq(CcrPricingItemDepositRel::getPricingItemId, sourceItemId))) {
            CcrPricingItemDepositRel copy = new CcrPricingItemDepositRel();
            copy.setApplicationId(targetAppId);
            copy.setPricingItemId(targetItemId);
            copy.setDepositAccountNo(rel.getDepositAccountNo());
            copy.setPlannedAccountFlag(rel.getPlannedAccountFlag());
            depositRelMapper.insert(copy);
        }
    }

    /** 复制担保组合及措施到新分项 */
    private void copyGuarantee(CcrPricingItem src, CcrPricingItem copy) {
        if (src.getGuaranteePackageId() == null) {
            return;
        }
        CcrGuaranteePackage srcPkg = guaranteePackageMapper.selectById(src.getGuaranteePackageId());
        if (srcPkg == null) {
            return;
        }
        CcrGuaranteePackage pkg = new CcrGuaranteePackage();
        pkg.setPackageNo("GP-" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        pkg.setPricingItemId(copy.getId());
        pkg.setPackageVersion(1);
        pkg.setMainGuaranteeType(srcPkg.getMainGuaranteeType());
        pkg.setExtJson(srcPkg.getExtJson());
        guaranteePackageMapper.insert(pkg);
        copy.setGuaranteePackageId(pkg.getId());
        pricingItemMapper.updateById(copy);
        for (CcrGuaranteeMeasure measure : guaranteeMeasureMapper.selectList(
                new LambdaQueryWrapper<CcrGuaranteeMeasure>().eq(CcrGuaranteeMeasure::getPackageId, srcPkg.getId()))) {
            CcrGuaranteeMeasure m = new CcrGuaranteeMeasure();
            m.setPackageId(pkg.getId());
            m.setMeasureNo("GM-" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
            m.setMeasureType(measure.getMeasureType());
            m.setGuarantorCustomerNo(measure.getGuarantorCustomerNo());
            m.setCollateralNo(measure.getCollateralNo());
            m.setGuaranteeAmount(measure.getGuaranteeAmount());
            m.setCurrency(measure.getCurrency());
            m.setExtJson(measure.getExtJson());
            guaranteeMeasureMapper.insert(m);
        }
    }

    // ==================== 私有工具 ====================

    private CcrApplication requireApplication(Long id) {
        CcrApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new ServiceException(404, "申请不存在");
        }
        return app;
    }

    private List<CcrApplicationMember> applicationMembers(Long applicationId) {
        return applicationMemberMapper.selectList(new LambdaQueryWrapper<CcrApplicationMember>()
                .eq(CcrApplicationMember::getApplicationId, applicationId)
                .orderByAsc(CcrApplicationMember::getId));
    }

    /** 申请全部有效分项(含沿用占位) */
    private List<CcrPricingItem> pricingItems(Long applicationId) {
        return pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, applicationId)
                .orderByAsc(CcrPricingItem::getId));
    }

    /** 待路由分项(草稿态、非沿用占位) */
    private List<CcrPricingItem> routableItems(Long applicationId) {
        return pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, applicationId)
                .eq(CcrPricingItem::getStatus, PricingItemStatus.DRAFT.getCode())
                .ne(CcrPricingItem::getInheritFlag, "Y")
                .orderByAsc(CcrPricingItem::getId));
    }

    /**
     * 存款起点利率硬边界(2026-08-27 用户拍板):存款申请利率必须严格高于矩阵起点利率(挂牌价)
     * 才能提交——等于起点利率无需提交利率申请(柜面按挂牌价直接办理),低于起点不合理。
     * 起点利率 = 矩阵该产品/期限档命中的边界值(PRD 表 7.2.4:对公定期 3M>0.85%/1Y>1.25% 等);
     * 未配置矩阵边界(起点为空)不拦截。校验时机在状态变更/快照采集前,失败整单回滚。
     */
    private void checkDepositStartRate(CcrApplication app, List<CcrPricingItem> items, BigDecimal groupCreditTotal) {
        if (!"DEPOSIT".equals(businessBigType(app))) {
            return;
        }
        Map<String, Map<String, Object>> corpCache = new HashMap<>();
        for (CcrPricingItem item : items) {
            RouteResult route = rateMatrixRouter.calcRoute(buildRouteInput(app, item, groupCreditTotal, corpCache));
            BigDecimal start = route.getBoundaryRate();
            BigDecimal rate = item.getRequestedRate();
            if (start != null && (rate == null || rate.compareTo(start) <= 0)) {
                throw new ServiceException(ErrorCode.HARD_BOUNDARY.getCode(),
                        "存款分项[" + item.getPricingItemNo() + "]申请利率必须高于起点利率 "
                                + start.stripTrailingZeros().toPlainString()
                                + "%,等于起点利率无需提交利率申请");
            }
        }
    }

    /** 业务大类:LOAN_PUBLIC/LOAN_PERSONAL/DEPOSIT(硬边界与矩阵路由入参) */
    private String businessBigType(CcrApplication app) {
        if ("DEPOSIT".equals(app.getBusinessType())) {
            return "DEPOSIT";
        }
        return "INDIVIDUAL".equals(app.getCustomerScope()) ? "LOAN_PERSONAL" : "LOAN_PUBLIC";
    }

    /** 集团批复总额度(§B18 路由金额定档基准;非集团或无授信快照返回 null) */
    private BigDecimal loadGroupCreditTotal(CcrApplication app) {
        if (!"GROUP".equals(app.getCustomerScope()) || StrUtil.isBlank(app.getGroupNo())) {
            return null;
        }
        Map<String, Object> credit = dataWarehouseService.findGroupCredit(app.getGroupNo());
        return credit == null ? null : toBigDecimal(credit.get("approved_total_amount"));
    }

    /** 构造矩阵路由入参(沿用申请冻结的 LPR 版本与生效日期,§8.4) */
    private MatrixRouteInput buildRouteInput(CcrApplication app, CcrPricingItem item, BigDecimal groupCreditTotal,
                                             Map<String, Map<String, Object>> corpCache) {
        MatrixRouteInput input = new MatrixRouteInput();
        input.setBusinessBigType(businessBigType(app));
        input.setNewOrExisting(resolveNewOrExisting(app, item));
        input.setCustomerType(resolveCustomerType(app, item, corpCache));
        input.setProductCode(item.getProductCode());
        // 需求:审批链路按总授信额度定档(存量=数仓授信协议金额合计,新增=手工录入;集团=集团综合授信批复总额度优先)
        input.setAmount(totalCreditOf(app, item));
        input.setAmountBasis(MatrixRouteInput.AMOUNT_BASIS_GROUP_TOTAL_CREDIT);
        input.setGroupCreditTotal(groupCreditTotal);
        input.setTermValue(item.getTermValue());
        input.setTermUnit(item.getTermUnit());
        input.setGuaranteeType(resolveGuaranteeType(item));
        input.setRequestedRate(item.getRequestedRate());
        input.setOriginalRate(item.getOriginalRate());
        input.setLprVersionId(app.getLprVersionId());
        input.setAsOfDate(app.getRouteAsOfDate());
        return input;
    }

    /** 总授信额度(审批链路金额定档口径):优先取申请授信快照 credit_info_json.totalCredit
     * (存量=数仓授信协议金额合计自动带出,新增=手工录入);缺省回退分项金额(兼容旧申请/草稿) */
    private BigDecimal totalCreditOf(CcrApplication app, CcrPricingItem item) {
        if (StrUtil.isNotBlank(app.getCreditInfoJson())) {
            try {
                BigDecimal tc = JSONUtil.parseObj(app.getCreditInfoJson()).getBigDecimal("totalCredit");
                if (tc != null) {
                    return tc;
                }
            } catch (Exception ignored) {
                // 快照解析失败按分项金额回退
            }
        }
        return item.getPricingAmount();
    }

    /** 存量/新增判定:优先以申请授信快照中的授信业务类型(credit_info_json.businessType,NEW=新增授信/EXISTING=存量调息)为准;
     *  该字段由前端申请页业务类型显式提交(§用户要求),不以分项原利率推断——原利率属存量贷款合同带出,不能代表授信新增/存量的判定口径 */
    private String resolveNewOrExisting(CcrApplication app, CcrPricingItem item) {
        // 存款按期限档设上限、D16b 无部门层级,矩阵无存量/新增之分,恒按新增路由
        // (存款存量账户反查带出的 originalRate 仅作展示,不得据此判 EXISTING 匹配不到矩阵行)
        if ("DEPOSIT".equals(businessBigType(app))) {
            return "NEW";
        }
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

    /** 客户类型:PERSONAL/SOE/NON_SOE(申请提交的企业性质优先,数仓带出兜底,缺省 NON_SOE) */
    private String resolveCustomerType(CcrApplication app, CcrPricingItem item,
                                       Map<String, Map<String, Object>> corpCache) {
        if ("INDIVIDUAL".equals(app.getCustomerScope())) {
            return "PERSONAL";
        }
        // 1. 申请提交的企业性质优先(§2026-08-27 用户拍板:新增客户申请页人工选国企/非国企,数仓仅带出默认;
        //    老申请快照无 entpCharic 键 → 回退数仓,兼容存量)
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
        Map<String, Object> corp = corpCache.computeIfAbsent(customerNo, dataWarehouseService::findCorpCustomer);
        if (corp == null || corp.get("entp_charic") == null) {
            return "NON_SOE";
        }
        String entpCharic = String.valueOf(corp.get("entp_charic"));
        return "SOE".equals(entpCharic) ? "SOE" : "NON_SOE";
    }

    /** 担保主类型(取分项冻结担保组合) */
    private String resolveGuaranteeType(CcrPricingItem item) {
        if (item.getGuaranteePackageId() == null) {
            return null;
        }
        CcrGuaranteePackage pkg = guaranteePackageMapper.selectById(item.getGuaranteePackageId());
        return pkg == null ? null : pkg.getMainGuaranteeType();
    }

    /** 当前生效 LPR 版本(与 GET /ccr/rule/version/current 同口径;缓存 §3.6 key ccr:cfg:lpr:effective,发布时失效) */
    private CcrLprVersion currentLpr() {
        Object cached = cacheUtil.get(CcrCacheUtil.KEY_LPR_EFFECTIVE);
        if (cached instanceof CcrLprVersion v) {
            return v;
        }
        CcrLprVersion lpr = lprVersionMapper.selectOne(new LambdaQueryWrapper<CcrLprVersion>()
                .eq(CcrLprVersion::getStatus, "EFFECTIVE")
                .le(CcrLprVersion::getEffectiveFrom, LocalDateTime.now())
                .and(w -> w.isNull(CcrLprVersion::getEffectiveTo)
                        .or().gt(CcrLprVersion::getEffectiveTo, LocalDateTime.now()))
                .orderByDesc(CcrLprVersion::getEffectiveFrom)
                .last("limit 1"));
        if (lpr == null) {
            throw new ServiceException(ErrorCode.LPR_NOT_EFFECTIVE.getCode(), "当前无生效的LPR版本,无法提交");
        }
        cacheUtil.set(CcrCacheUtil.KEY_LPR_EFFECTIVE, lpr);
        return lpr;
    }

    /** 当前生效规则集版本(无生效规则集时返回 null,矩阵路由不依赖规则集) */
    private CcrRateRuleSet currentRuleSet() {
        return ruleSetMapper.selectOne(new LambdaQueryWrapper<CcrRateRuleSet>()
                .eq(CcrRateRuleSet::getStatus, "EFFECTIVE")
                .orderByDesc(CcrRateRuleSet::getEffectiveFrom)
                .last("limit 1"));
    }

    /** 成员在团判定:relation_end 空或不早于当日 */
    private boolean memberInGroup(Map<String, Object> dwMember) {
        Object relationEnd = dwMember.get("relation_end");
        if (relationEnd == null || StrUtil.isBlank(String.valueOf(relationEnd))) {
            return true;
        }
        LocalDate end = LocalDate.parse(String.valueOf(relationEnd).substring(0, 10));
        return !end.isBefore(LocalDate.now());
    }

    // ---------- 手工集团(ccr_group/ccr_group_member)合并辅助 ----------

    /** 集团主数据合并判定:数仓快照优先,手工集团回退 */
    private boolean groupExists(String groupNo) {
        return dataWarehouseService.findGroup(groupNo) != null
                || manualGroupService.findGroup(groupNo) != null;
    }

    /** 集团批复总额度:数仓授信快照优先,手工集团回退补录值(路由定档/额度勾稽基准) */
    private BigDecimal mergedApprovedTotal(String groupNo) {
        Map<String, Object> credit = dataWarehouseService.findGroupCredit(groupNo);
        if (credit != null && credit.get("approved_total_amount") != null) {
            return toBigDecimal(credit.get("approved_total_amount"));
        }
        CcrGroup manual = manualGroupService.findGroup(groupNo);
        return manual == null ? null : manual.getApprovedTotalAmount();
    }

    /** 本次申请额度(集团申请:从 group_info_json 读,新增授信必填;非集团/未补录返回 null) */
    private BigDecimal applyAmountOf(CcrApplication app) {
        if (!"GROUP".equals(app.getCustomerScope()) || StrUtil.isBlank(app.getGroupInfoJson())) {
            return null;
        }
        try {
            return toBigDecimal(JSONUtil.parseObj(app.getGroupInfoJson()).get("applyAmount"));
        } catch (Exception e) {
            return null;
        }
    }

    /** 提交判定集团存在:数仓收录 ∨ 手工表 ∨ 申请上下文 group_info_json 已补录(数据以数仓为准,数仓无则补录数据生效) */
    private boolean groupExistsForSubmit(CcrApplication app) {
        if (groupExists(app.getGroupNo())) {
            return true;
        }
        if (StrUtil.isNotBlank(app.getGroupInfoJson())) {
            try {
                JSONObject json = JSONUtil.parseObj(app.getGroupInfoJson());
                return StrUtil.isNotBlank(json.getStr("groupNo")) && StrUtil.isNotBlank(json.getStr("groupName"));
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 提交时落表(§docs/19 §4.6):解析 group_info_json,新增集团(数仓无)落 ccr_group、补录成员(数仓无该成员)落 ccr_group_member。
     * 幂等、数仓优先、最新覆盖;申请额度不落主表(随申请存多条),仅同步至 ccr_group.approved_total_amount 作展示参考。
     */
    private void persistGroupSupplement(CcrApplication app) {
        if (!"GROUP".equals(app.getCustomerScope()) || StrUtil.isBlank(app.getGroupInfoJson())) {
            return;
        }
        JSONObject json;
        try {
            json = JSONUtil.parseObj(app.getGroupInfoJson());
        } catch (Exception e) {
            return;
        }
        String groupNo = app.getGroupNo();
        // 新增集团:数仓未收录则落 ccr_group(最新覆盖;approved_total_amount=本次申请额度,展示参考)
        if (dataWarehouseService.findGroup(groupNo) == null) {
            BigDecimal applyAmount = applyAmountOf(app);
            if (applyAmount == null) {
                return; // 申请额度未录,交 checkGroupConstraints 拦截报错,不落表(同事务回滚)
            }
            CcrGroup g = manualGroupService.findGroup(groupNo);
            if (g == null) {
                g = new CcrGroup();
            }
            g.setGroupNo(groupNo);
            g.setGroupName(StrUtil.blankToDefault(json.getStr("groupName"), "集团-" + groupNo));
            g.setGroupType(StrUtil.blankToDefault(json.getStr("groupType"), "INDUSTRY_GROUP"));
            g.setGroupStatus(StrUtil.blankToDefault(json.getStr("groupStatus"), "NORMAL"));
            g.setStateOwnedFlag(json.getStr("stateOwnedFlag"));
            g.setCurrency(StrUtil.blankToDefault(json.getStr("currency"), "CNY"));
            g.setManagerOrgId(json.getLong("managerOrgId"));
            g.setApprovedTotalAmount(applyAmount);
            manualGroupService.saveGroup(g);
        }
        // 补录成员:数仓无该成员的补录成员落 ccr_group_member(最新覆盖)
        JSONArray supplementMembers = json.getJSONArray("supplementMembers");
        if (supplementMembers == null) {
            return;
        }
        for (int i = 0; i < supplementMembers.size(); i++) {
            JSONObject m = supplementMembers.getJSONObject(i);
            String memberNo = m.getStr("memberCustomerNo");
            if (StrUtil.isBlank(memberNo)) {
                continue;
            }
            if (dataWarehouseService.findGroupMember(groupNo, memberNo) != null) {
                continue; // 数仓优先:数仓已有该成员,不落手工表
            }
            CcrGroupMember gm = manualGroupService.findGroupMember(groupNo, memberNo);
            if (gm == null) {
                gm = new CcrGroupMember();
            }
            gm.setGroupNo(groupNo);
            gm.setMemberCustomerNo(memberNo);
            gm.setMemberName(StrUtil.blankToDefault(m.getStr("memberName"), memberNo));
            gm.setMemberRole(StrUtil.blankToDefault(m.getStr("memberRole"), "GENERAL"));
            gm.setControlRelation(m.getStr("controlRelation"));
            gm.setRelationStart(parseLocalDate(m.getStr("relationStart")));
            gm.setRelationEnd(parseLocalDate(m.getStr("relationEnd")));
            manualGroupService.upsertMember(gm);
        }
    }

    /** 字符串日期转 LocalDate(空/非法返回 null) */
    private static LocalDate parseLocalDate(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    /** 数仓成员快照 map(成员客户号→行) */
    private Map<String, Map<String, Object>> dwMemberMap(String groupNo) {
        Map<String, Map<String, Object>> map = new HashMap<>();
        for (Map<String, Object> row : dataWarehouseService.groupMembers(groupNo)) {
            map.put(String.valueOf(row.get("member_customer_no")), row);
        }
        return map;
    }

    /** 手工成员 map(成员客户号→实体) */
    private Map<String, CcrGroupMember> manualMemberMap(String groupNo) {
        Map<String, CcrGroupMember> map = new HashMap<>();
        for (CcrGroupMember m : manualGroupService.listMembers(groupNo)) {
            map.put(m.getMemberCustomerNo(), m);
        }
        return map;
    }

    /** 成员在团校验:数仓命中且在团,或手工命中且在团(任一侧有效即放行) */
    private boolean memberValid(Map<String, Map<String, Object>> dwMap,
                                Map<String, CcrGroupMember> manualMap, String memberNo) {
        // 内部合成号(MANUAL- 前缀):本次手工补录的非我行客户成员,视为在团放行(数仓无该客户数据)
        // 占位号(NEW 前缀,2026-08-20 #017):新增客户成员(有证件号无客户号),提交时未命中数仓保留占位,放行待审批中回填
        if (memberNo != null && (memberNo.startsWith("MANUAL-") || CustomerNoUtil.isPlaceholder(memberNo))) {
            return true;
        }
        Map<String, Object> dw = dwMap.get(memberNo);
        if (dw != null && memberInGroup(dw)) {
            return true;
        }
        CcrGroupMember manual = manualMap.get(memberNo);
        if (manual != null && manual.getRelationEnd() == null) {
            return true;
        }
        return manual != null && manual.getRelationEnd() != null
                && !manual.getRelationEnd().isBefore(LocalDate.now());
    }

    /** 手工集团快照记录(数仓无集团主数据时;含补录批复总额度) */
    private Long addManualGroupRecord(Long bundleId, String groupNo) {
        CcrGroup g = manualGroupService.findGroup(groupNo);
        if (g == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        // 手工集团无数据仓批次:etl_md5 用 MANUAL- 前缀标识来源,data_dt 用补录当日(快照 source_data_dt 必填)
        // 日期一律字符串(快照内容哈希 HASH_MAPPER 未注册 JavaTimeModule,LocalDate 序列化失败)
        row.put("etl_md5", "MANUAL-GROUP-" + g.getGroupNo());
        row.put("data_dt", String.valueOf(LocalDate.now()));
        row.put("group_no", g.getGroupNo());
        row.put("group_name", g.getGroupName());
        row.put("group_type", g.getGroupType());
        row.put("manager_org_id", g.getManagerOrgId());
        row.put("group_status", g.getGroupStatus());
        row.put("approved_total_amount", g.getApprovedTotalAmount());
        return addSnapshotRecord(bundleId, "dw_customer_group_snapshot", "GROUP", g.getGroupNo(), row);
    }

    /** 手工成员快照行(数仓无成员时;含补录名称;relation_end 空=在团) */
    private Map<String, Object> manualMemberCore(String groupNo, String memberNo) {
        CcrGroupMember m = manualGroupService.findGroupMember(groupNo, memberNo);
        if (m == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        // 日期一律字符串(快照内容哈希 HASH_MAPPER 未注册 JavaTimeModule,LocalDate 序列化失败)
        row.put("etl_md5", "MANUAL-MEMBER-" + m.getGroupNo() + "-" + m.getMemberCustomerNo());
        row.put("data_dt", String.valueOf(LocalDate.now()));
        row.put("group_no", m.getGroupNo());
        row.put("member_customer_no", m.getMemberCustomerNo());
        row.put("member_name", m.getMemberName());
        row.put("member_role", m.getMemberRole());
        row.put("control_relation", m.getControlRelation());
        row.put("relation_start", m.getRelationStart() == null ? null : String.valueOf(m.getRelationStart()));
        row.put("relation_end", m.getRelationEnd() == null ? null : String.valueOf(m.getRelationEnd()));
        return row;
    }

    /** 刷新数据日期基线(不触发乐观锁版本递增,仅记录比对基准) */
    private void refreshBaseline(CcrApplication app) {
        applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                .eq(CcrApplication::getId, app.getId())
                .set(CcrApplication::getDataBaselineJson, buildBaselineJson(app)));
    }

    private String buildBaselineJson(CcrApplication app) {
        return JSONUtil.toJsonStr(dataWarehouseService.latestDataDates(
                DataWarehouseService.relevantDatasets(app.getBusinessType(), app.getCustomerScope())));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseBaseline(String baselineJson) {
        if (StrUtil.isBlank(baselineJson)) {
            return null;
        }
        Map<String, Object> raw = JSONUtil.toBean(baselineJson, Map.class);
        Map<String, String> baseline = new LinkedHashMap<>();
        raw.forEach((k, v) -> baseline.put(k, v == null ? null : String.valueOf(v)));
        return baseline;
    }

    private SubmitResponse buildSubmitResponse(CcrApplication app, List<CcrPricingItem> items, boolean submitted) {
        SubmitResponse response = new SubmitResponse();
        response.setApplicationId(app.getId());
        response.setApplicationNo(app.getApplicationNo());
        response.setStatus(app.getStatus());
        response.setSnapshotBundleId(app.getSnapshotBundleId());
        response.setLprVersionId(app.getLprVersionId());
        response.setRuleSetVersionId(app.getRuleSetVersionId());
        response.setRouteAsOfDate(app.getRouteAsOfDate());
        response.setSubmitTime(app.getSubmitTime());
        response.setSubmitted(submitted);
        List<SubmitResponse.ItemRoute> itemRoutes = new ArrayList<>();
        for (CcrPricingItem item : items) {
            itemRoutes.add(toItemRoute(item, null));
        }
        response.setItems(itemRoutes);
        return response;
    }

    private SubmitResponse.ItemRoute toItemRoute(CcrPricingItem item, List<String> routeChain) {
        SubmitResponse.ItemRoute route = new SubmitResponse.ItemRoute();
        route.setPricingItemId(item.getId());
        route.setPricingItemNo(item.getPricingItemNo());
        route.setStatus(item.getStatus());
        route.setCurrentNodeCode(item.getCurrentNodeCode());
        route.setRouteCode(item.getRouteCode());
        route.setRouteChain(routeChain);
        return route;
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        return new BigDecimal(v.toString());
    }
}
