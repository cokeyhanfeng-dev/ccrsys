package com.ccr.application.service.impl;

import com.ccr.common.outbox.NodeReminderPublisher;
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
import com.ccr.application.support.CommitmentBaselineResolver;
import com.ccr.application.support.CustomerNoUtil;
import com.ccr.common.core.util.BranchTypeSupport;
import com.ccr.common.core.util.WarehouseCustomerSync;
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

    /** 链首支行行长节点码(§2026-09-18):与 ccr-approval RouteChains.BRANCH_MANAGER 同值。
     *  此处用字面量而非引常量——ccr-application 只依赖 ccr-common/ccr-rule,不依赖 ccr-approval,
     *  引入会造成反向模块依赖。 */
    private static final String BRANCH_MANAGER_NODE = "BRANCH_MANAGER";

    /** 管理综合支行长节点码(§2026-09-18 零售支行):仅零售支行申请链含此节点,紧随 BRANCH_MANAGER 之后。 */
    private static final String PARENT_BRANCH_MANAGER_NODE = "PARENT_BRANCH_MANAGER";

    /** 数据时效容忍天数(§9.4 默认 3 个自然日,超过 BLOCK 阻断提交;与快照质量规则同一配置) */
    @Value("${ccr.snapshot.data-stale-days:3}")
    private int dataStaleDays;

    @Resource
    private NodeReminderPublisher nodeReminderPublisher;

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
        // 存量新增(§docs/43)分流:与 submit 同口径——存量行(source_split_no 非空,数仓协议带出)不匹配矩阵、
        // 不参与整单链锚定,待整单链确定后原样搭链。原先预览漏了这层分流,存量行照常调矩阵,
        // 其低利率又把整单链带走,导致预览显示的长链与实际提交冻结的链不一致(§2026-09-17 用户报「流程是错的」)
        boolean mixedExistingNew = isMixedExistingNew(app);
        List<RoutePreviewResponse.ItemRoutePreview> carriedPreviews = new ArrayList<>();
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
            // 存量行:不匹配矩阵、不参与整单锚定,待整单链确定后统一回填(硬边界仍照常校验,
            // 与 submit 的 e) 逐分项硬边界不区分来源同口径)
            if (mixedExistingNew && StrUtil.isNotBlank(item.getSourceSplitNo())) {
                previews.add(preview);
                carriedPreviews.add(preview);
                continue;
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
                    NodeAssigneeResolver.ResolveResult resolved = nodeAssigneeResolver.resolvePreview(
                            chain.get(0), app.getApplicantOrgId(), item.getDeptCode(), app.getApplyBranchCode());
                    preview.setNextApproverNames(resolved.displayNames());
                    preview.setNextApproverMessage(resolved.previewMessage());
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
            response.setNextApproverMessage(anchorPreview.getNextApproverMessage());
            response.setMatchedMatrixNo(anchorRoute.getMatchedMatrixNo());
            response.setBoundaryRate(anchorRoute.getBoundaryRate());
        }
        // 存量行预览补齐:路由字段原样取新增分项锚定出的整单链,不匹配矩阵、无自身矩阵行号语义
        // (与 submit 存量行搭链完全同口径,保证预览与提交后冻结的链一致)
        for (RoutePreviewResponse.ItemRoutePreview carried : carriedPreviews) {
            if (anchorRoute != null) {
                carried.setRateDirection(anchorRoute.getRateDirection());
                carried.setStartNodeCode(anchorRoute.getStartNodeCode());
                carried.setFinalNodeCode(anchorRoute.getFinalNodeCode());
                carried.setRouteChain(anchorRoute.getRouteChain());
                carried.setNextApproverNames(anchorPreview.getNextApproverNames());
                carried.setNextApproverMessage(anchorPreview.getNextApproverMessage());
                carried.setLprVersionId(anchorRoute.getLprVersionId());
                carried.setLprVersionCode(anchorRoute.getLprVersionCode());
                carried.setMessage("存量拆分项不参与矩阵定档,随整单链搭链上送");
            } else {
                carried.setMessage("存量新增须至少录入一个新增授信分项(数仓带出的存量拆分项不参与审批链定档)");
            }
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
        // 分项金额勾稽(§2026-09-07):分项申请金额合计不得超过授信总额(存量自动带出拆分项可小于协议/批复总额,超限才 BLOCK)
        precheck.addAll(guaranteeTotalPrecheck(app, items));
        // 存量调息申请利率上限(§2026-09-07 用户拍板):贷款存量(EXISTING)申请利率不得高于原利率,高于才 BLOCK
        precheck.addAll(existingRatePrecheck(app, items));
        // 链首支行行长审批人(§2026-09-18):本机构未配置行长则 BLOCK,避免提交后审批无人可指派、提醒送不达
        precheck.addAll(branchAssigneePrecheck(app));
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
            boolean individual = "INDIVIDUAL".equals(app.getCustomerScope());
            Map<String, Object> basic = individual
                    ? dataWarehouseService.findIndvCustomer(app.getCustomerNo())
                    : dataWarehouseService.findCorpCustomer(app.getCustomerNo());
            // 集团成员单户阻断(2026-09-01):客户属于集团不能以单户申请利率,须走集团客户申请流程(数仓优先,手工集团回退)
            if (!individual) {
                Map<String, Object> groupOf = dataWarehouseService.groupOfCustomer(app.getCustomerNo());
                if (groupOf == null) {
                    groupOf = manualGroupService.groupOfCustomer(app.getCustomerNo());
                }
                if (groupOf != null) {
                    items.add(precheckItem("GROUP_MEMBER_SINGLE", "BLOCK", app.getCustomerNo(),
                            "该客户属于集团[" + groupOf.get("groupName") + "]，不能以单户方式申请利率，请走集团客户申请流程"));
                }
            }
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

    /** 分项金额勾稽预校验(§2026-09-07):分项申请金额合计不得超过授信总额——存量自动带出拆分项可小于协议/批复
     *  总额(部分拆分执行,如集团存量拆分 2800 vs 协议 10000),不再强制相等,仅当分项合计超过总额才 BLOCK;
     *  总额口径:单户=credit_info_json.totalCredit 快照值,集团=applyAmountOf(app);快照缺省(无明确总额)时跳过,前端已拦 */
    private List<SubmitCheckResponse.QualityPrecheckItem> guaranteeTotalPrecheck(CcrApplication app, List<CcrPricingItem> items) {
        List<SubmitCheckResponse.QualityPrecheckItem> result = new ArrayList<>();
        BigDecimal totalCredit;
        if ("GROUP".equals(app.getCustomerScope())) {
            totalCredit = applyAmountOf(app);
        } else {
            if (StrUtil.isBlank(app.getCreditInfoJson())) {
                return result;
            }
            try {
                totalCredit = JSONUtil.parseObj(app.getCreditInfoJson()).getBigDecimal("totalCredit");
            } catch (Exception ignored) {
                return result; // 快照解析失败跳过勾稽(前端已拦,后端不强堵)
            }
        }
        if (totalCredit == null || totalCredit.signum() <= 0) {
            return result;
        }
        BigDecimal itemSum = items.stream()
                .map(CcrPricingItem::getPricingAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (itemSum.subtract(totalCredit).compareTo(new BigDecimal("0.01")) > 0) {
            result.add(precheckItem("ITEM_TOTAL_CREDIT", "BLOCK", app.getCustomerNo(),
                    "分项申请金额合计 " + itemSum + " 万元超过授信总额 " + totalCredit + " 万元,请调减分项金额(合计不得超过授信总额)"));
        }
        return result;
    }

    /** 存量调息申请利率上限预校验(§2026-09-07 用户拍板):仅贷款存量调息(EXISTING,单户+集团)申请利率不得高于原利率——
     *  高于才 BLOCK、等于(维持原利率)放行;原利率为空(拆分项未带出/未补录)该分项跳过。
     *  存量判定复用 resolveNewOrExisting(credit_info_json.businessType=EXISTING,存款恒 NEW 不走此)。
     *  与提交硬校验 checkExistingRateCap 同口径双拦截。 */
    private List<SubmitCheckResponse.QualityPrecheckItem> existingRatePrecheck(CcrApplication app, List<CcrPricingItem> items) {
        List<SubmitCheckResponse.QualityPrecheckItem> result = new ArrayList<>();
        for (CcrPricingItem item : items) {
            if (!"EXISTING".equals(resolveNewOrExisting(app, item))) {
                continue;
            }
            BigDecimal original = item.getOriginalRate();
            BigDecimal requested = item.getRequestedRate();
            if (original == null || requested == null || requested.compareTo(original) <= 0) {
                continue;
            }
            result.add(precheckItem("ITEM_EXISTING_RATE", "BLOCK", item.getPricingItemNo(),
                    "授信分项[" + item.getPricingItemNo() + "]存量调息申请利率 " + requested.stripTrailingZeros().toPlainString()
                            + "% 不得高于原利率 " + original.stripTrailingZeros().toPlainString() + "%,请保持或调低利率"));
        }
        return result;
    }

    /**
     * 链首支行行长审批人预校验(§2026-09-18 生产问题收口):链首节点恒为支行行长(贷款链/存款链首节点
     * 均为 BRANCH_MANAGER,零售申请的管理综合支行长插在其后),按申请机构 applicant_org_id 解析本机构
     * 行长、兜底按申请支行码 apply_branch_code 前缀匹配(与 NodeReminderHandler 的兜底同口径)。
     * 解析为空即该机构未配置 branch_manager 账号:提交后支行行长节点无人可指派——审批侧原先退化为
     * "任一支行行长可审"(越权),待办提醒则因收件人为空致 Outbox 事件终态失败(生产开发区支行案例)。
     * 故在提交预检即 BLOCK,把问题暴露在业务发起时。与提交硬校验 checkBranchAssignee 同口径双拦截。
     * 零售支行(2026-09-04 综合/零售两级支行,branch_type='RETAIL')申请链上另有管理综合支行长节点
     * (PARENT_BRANCH_MANAGER),同样须有在岗行长,故一并预检;该节点仅零售申请走,非零售机构解析
     * 恒空,不可无条件校验(否则综合支行/总行的单子会被误拦)。
     */
    private List<SubmitCheckResponse.QualityPrecheckItem> branchAssigneePrecheck(CcrApplication app) {
        List<SubmitCheckResponse.QualityPrecheckItem> result = new ArrayList<>();
        if (assigneeMissing(app, BRANCH_MANAGER_NODE)) {
            result.add(precheckItem("NODE_BRANCH_ASSIGNEE", "BLOCK", app.getApplyBranchCode(),
                    branchAssigneeMessage("支行行长", app.getApplyBranchCode())));
        }
        // 管理综合支行长:仅零售支行申请链含该节点,故先判管理综合支行是否存在——非零售机构该节点
        // 解析恒空,若无条件校验会把综合支行/总行的单子全部误拦。
        String parentOrgCode = BranchTypeSupport.managingComprehensiveBranchCode(
                jdbcTemplate, app.getApplicantOrgId());
        if (parentOrgCode != null && assigneeMissing(app, PARENT_BRANCH_MANAGER_NODE)) {
            result.add(precheckItem("NODE_PARENT_BRANCH_ASSIGNEE", "BLOCK", app.getApplyBranchCode(),
                    branchAssigneeMessage("管理综合支行长", parentOrgCode)));
        }
        return result;
    }

    /** 链首支行行长审批人硬校验:与 submitCheck 预校验 branchAssigneePrecheck 同口径,失败整单回滚。 */
    private void checkBranchAssignee(CcrApplication app) {
        if (assigneeMissing(app, BRANCH_MANAGER_NODE)) {
            throw new ServiceException(ErrorCode.QUALITY_BLOCK.getCode(),
                    branchAssigneeMessage("支行行长", app.getApplyBranchCode()));
        }
        String parentOrgCode = BranchTypeSupport.managingComprehensiveBranchCode(
                jdbcTemplate, app.getApplicantOrgId());
        if (parentOrgCode != null && assigneeMissing(app, PARENT_BRANCH_MANAGER_NODE)) {
            throw new ServiceException(ErrorCode.QUALITY_BLOCK.getCode(),
                    branchAssigneeMessage("管理综合支行长", parentOrgCode));
        }
    }

    /** 该节点在申请机构下是否解析不到任何审批人(与提醒侧同口径:含 apply_branch_code 前缀兜底)。
     *  配置查询本身异常(LEVEL_ERROR)不算缺失:属数据库/配置暂不可用,应由用户重试,不误判为"未配行长"。 */
    private boolean assigneeMissing(CcrApplication app, String nodeCode) {
        NodeAssigneeResolver.ResolveResult resolved = nodeAssigneeResolver.resolvePreview(
                nodeCode, app.getApplicantOrgId(), null, app.getApplyBranchCode());
        return resolved.users().isEmpty()
                && !NodeAssigneeResolver.LEVEL_ERROR.equals(resolved.getHitLevel());
    }

    /** 未配审批人提示:带上待补配置的机构号(零售支行的上级管理综合支行),便于管理员直接定位。 */
    private String branchAssigneeMessage(String roleLabel, String orgCode) {
        String org = StrUtil.isBlank(orgCode) ? "—" : orgCode;
        return "本申请机构未配置" + roleLabel + "审批人,无法提交:请联系管理员为该机构(机构号 " + org
                + ")配置" + roleLabel + "账号后重试";
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
        // b0-绑定对齐) 单户:提交时把本申请产生的关联人绑定(ccr_relation)主体对齐最终主单客户号
        //     (resolvePlaceholderCustomerNo 已把主单占位/空定稿为真实号或占位号,§2026-09-02 无号客户流程)
        syncRelationBindCustomerNo(app);
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
        // e3) 分项金额勾稽(2026-09-07 用户拍板):分项申请金额合计不得超过授信总额(存量自动带出拆分项可小于协议/批复总额),
        //     超过不容许提交(与 submitCheck 预校验 guaranteeTotalPrecheck 同口径双拦截)
        checkGuaranteeTotal(app, items);
        // e4) 存量调息申请利率上限(2026-09-07 用户拍板):贷款存量(EXISTING)申请利率不得高于原利率,高于整单回滚
        //     (与 submitCheck 预校验 existingRatePrecheck 同口径双拦截;等于放行,原利率空跳过)
        checkExistingRateCap(app, items);
        // e5) 链首支行行长审批人(§2026-09-18):本机构未配置行长则拒绝提交、整单回滚
        //     (与 submitCheck 预校验 branchAssigneePrecheck 同口径双拦截)
        checkBranchAssignee(app);
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
        //
        //    存量新增(§docs/43):单内分项按来源分两类处理——
        //      · 新增行(source_split_no 空,客户经理手工录入)= 匹配 NEW 矩阵,并从中锚定整单链;
        //      · 存量行(source_split_no 非空,数仓协议带出)= <b>不匹配矩阵、不参与定链</b>,
        //        待整单链确定后原样搭链上送(路由字段与申请单整单链完全一致)。
        //    故存量行没有自身的矩阵行号/边界语义,其 matched_matrix_no 即新增锚定出的那一行。
        boolean mixedExistingNew = isMixedExistingNew(app);
        Map<String, Map<String, Object>> corpCache = new HashMap<>();
        Map<Long, SubmitResponse.ItemRoute> routeByItemId = new HashMap<>();
        List<SubmitResponse.ItemRoute> itemRoutes = new ArrayList<>();
        List<CcrPricingItem> carriedItems = new ArrayList<>();
        CcrPricingItem chainAnchorItem = null;
        RouteResult chainAnchorRoute = null;
        BigDecimal anchorRate = null;
        boolean isLoan = "LOAN".equals(app.getBusinessType());
        for (CcrPricingItem item : items) {
            if (mixedExistingNew && StrUtil.isNotBlank(item.getSourceSplitNo())) {
                carriedItems.add(item);
                continue; // 存量行:不匹配矩阵,待整单链冻结后回填
            }
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
            routeByItemId.put(item.getId(), toItemRoute(item, route.getRouteChain()));
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
        // 存量新增:必须至少有一个新增分项——整单链由新增分项锚定,全是存量行则无链可搭(§docs/43)
        if (mixedExistingNew && chainAnchorRoute == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "存量新增须至少录入一个新增授信分项(数仓带出的存量拆分项不参与审批链定档);"
                            + "若本次仅调整存量分项,请改选「存量调息」");
        }
        // 存量新增:反向也必须至少有一个存量分项(§2026-09-17 用户要求)——本类型语义是「原协议下新增分项」,
        // 依托的是数仓原协议的存量拆分项;全是手工新增行则该类型名不副实,应直接走「新增授信」。
        if (mixedExistingNew && carriedItems.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "存量新增须至少包含一条存量分项(数仓带出的原协议拆分项);"
                            + "若本次仅新增授信,请改选「新增授信」");
        }
        // 存量行搭链:路由字段原样取新增锚定出的整单链,不匹配矩阵、无自身矩阵行号语义
        for (CcrPricingItem item : carriedItems) {
            item.setStatus(PricingItemStatus.ROUTING.getCode());
            item.setStartNodeCode(chainAnchorRoute.getStartNodeCode());
            item.setCurrentNodeCode(chainAnchorRoute.getStartNodeCode());
            item.setRouteCode(chainAnchorRoute.getFinalNodeCode());
            item.setBoundaryRate(chainAnchorRoute.getBoundaryRate());
            item.setMatchedMatrixNo(chainAnchorRoute.getMatchedMatrixNo());
            item.setDeptCode(chainAnchorRoute.getDeptCode());
            item.setRouteChain(JSONUtil.toJsonStr(chainAnchorRoute.getRouteChain()));
            pricingItemMapper.updateById(item);
            routeByItemId.put(item.getId(), toItemRoute(item, chainAnchorRoute.getRouteChain()));
        }
        // 提交响应按分项录入原顺序组装(前端展示顺序与录入顺序一致)
        for (CcrPricingItem item : items) {
            SubmitResponse.ItemRoute r = routeByItemId.get(item.getId());
            if (r != null) {
                itemRoutes.add(r);
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
     * b0) 单户客户号定稿:按 customer_info_json 证件号反查数仓 caps_*_cust_basic_info.cert_no,
     * 以反查结果为准覆盖录入值(2026-08-20 #017 立,2026-09-16 收口):
     *   - 命中 → 数仓真实客户号(ccr_application.customer_no + 各分项 pricing_customer_no),快照/审批/决议/承诺全链路一致
     *   - 未命中 → 回填占位号(NEW+证件后6位),走人工快照(MANUAL)通道,WARN 放行
     *
     * <p>§2026-09-16 手填号收口:此前只有「空号 / NEW 占位号」才走本方法,非空且非 NEW 的录入值被当作
     * "已是真实号"免检放行——实测有客户经理把外部编号(如 ECM…)填进客户号输入框,该值自此与数仓
     * 完全脱钩:档案查不到客户、贡献度跟踪按该号读数仓恒为空。§2026-09-02 已拍板"手动回填取消,
     * 占位→真实的唯一通道是按证件号反查",此处落实该口径:单户一律以反查结果为准,不再信任录入值;
     * 申请页(贷款/存款)客户号输入框同步改只读,录入侧不再产生手填号。</p>
     *
     * 集团场景成员客户号必填,无此问题。补号在 checkCompleteness 之前,使"单户场景客户号必填"校验自然通过。
     */
    private void resolvePlaceholderCustomerNo(CcrApplication app, List<CcrPricingItem> items) {
        if ("GROUP".equals(app.getCustomerScope())) {
            return; // 集团(成员号必填),占位处理走 resolveGroupMemberPlaceholder
        }
        String certNo = CustomerNoUtil.certNoFromInfoJson(app.getCustomerInfoJson(), app.getCustomerScope());
        if (StrUtil.isBlank(certNo)) {
            return; // 无证件号:后续 checkCompleteness 拦截提示"证件号码必填"
        }
        Map<String, Object> dw = "INDIVIDUAL".equals(app.getCustomerScope())
                ? dataWarehouseService.findIndvByCertNo(certNo)
                : dataWarehouseService.findCorpByCertNo(certNo);
        // 数仓主档取到 cust_no 才算命中;dw 非空但 cust_no 为 null 时若直接 String.valueOf 会写成字面量 "null"
        String resolvedNo = (dw == null || dw.get("cust_no") == null)
                ? CustomerNoUtil.placeholderCustomerNo(certNo)
                : String.valueOf(dw.get("cust_no"));

        // 回填主申请 customer_no
        app.setCustomerNo(resolvedNo);
        applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                .eq(CcrApplication::getId, app.getId())
                .set(CcrApplication::getCustomerNo, resolvedNo));

        // 同步分项 pricing_customer_no:单户场景分项定价客户号恒等于主单客户号,一律对齐反查结果
        // (原仅改占位号,手填号的分项会漏改而与主单不一致)
        for (CcrPricingItem item : items) {
            if (!resolvedNo.equals(item.getPricingCustomerNo())) {
                item.setPricingCustomerNo(resolvedNo);
                pricingItemMapper.updateById(item);
            }
        }

        // 同步人工快照 JSON 的 customerNo(审批详情 overwriteCustomer 仅非空覆盖,保证展示真实号/占位号);
        // §2026-09-02 #460 数仓命中时以数仓主档行为权威,一并覆盖其余可查出的客户字段(名称/性质/行业/评级等)
        if (StrUtil.isNotBlank(app.getCustomerInfoJson())) {
            try {
                JSONObject json = JSONUtil.parseObj(app.getCustomerInfoJson());
                json.set("customerNo", resolvedNo);
                if (dw != null) {
                    WarehouseCustomerSync.applyCustomerInfo(json, dw, "INDIVIDUAL".equals(app.getCustomerScope()));
                }
                app.setCustomerInfoJson(json.toString());
                applicationMapper.update(null, new LambdaUpdateWrapper<CcrApplication>()
                        .eq(CcrApplication::getId, app.getId())
                        .set(CcrApplication::getCustomerInfoJson, json.toString()));
            } catch (Exception e) {
                log.warn("回填 customer_info_json 客户信息失败,忽略:{}", e.getMessage());
            }
        }
    }

    /**
     * §2026-09-02 无客户号单户:提交定稿主单客户号(真实或占位)后,把本申请在草稿期产生的
     * 关联人绑定({@code ccr_relation})主体 customer_no 对齐主单最终号。
     *
     * <p>关联人 bind 在录入即落库,绑定主体取主单当时占位号;提交 resolve 命中数仓后主单已是真实号,
     * 此处把该申请绑定的占位/空主体一并对齐,避免残留占位主体(否则审批详情自动回填因主单已真实不触发)。
     * 集团场景绑定对象是 group_no,不涉及。失败不阻断提交(审批回填可补)。</p>
     */
    private void syncRelationBindCustomerNo(CcrApplication app) {
        if ("GROUP".equals(app.getCustomerScope()) || StrUtil.isBlank(app.getCustomerNo())) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    UPDATE ccr_relation
                    SET customer_no = ?
                    WHERE bind_application_no = ? AND del_flag = '0' AND group_no IS NULL
                      AND (customer_no IS NULL OR customer_no LIKE 'NEW%' OR customer_no <> ?)""",
                    app.getCustomerNo(), app.getApplicationNo(), app.getCustomerNo());
        } catch (Exception e) {
            log.warn("同步关联人绑定主体失败,忽略(审批自动回填可补):申请 {} 原因:{}",
                    app.getId(), e.getMessage());
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

        nodeReminderPublisher.publish(app.getId(), app.getStartNodeCode(), "SUBMIT", null, null);
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
        } else {
            if (StrUtil.isBlank(app.getCustomerNo())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "单户场景客户号必填");
            }
            // §2026-09-02 用户拍板:单户主客户证件号码必填(对公=统一社会信用代码,对私=身份证号)。
            // 手动回填已取消,占位→真实的自动回填唯一通道按证件号反查数仓;无证件号则永远无法回填,
            // 故真实号/占位号一律要求 customer_info_json 内证件号非空。集团场景走 group_no,不适用。
            String mainCert = CustomerNoUtil.certNoFromInfoJson(app.getCustomerInfoJson(), app.getCustomerScope());
            if (StrUtil.isBlank(mainCert)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "单户主客户证件号码必填(对公:统一社会信用代码;对私:身份证号)");
            }
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
        // 拟达成贡献度承诺:至少一条 + 截止日期必填(§7.1 提交校验;草稿保存 saveCommitments 不强制,仅提交时把关)。
        // §2026-09-16 仅对含贷款分项的申请校验:存款申请页无承诺录入入口,若一并对存款要求「至少一条」则存款业务
        // 全线阻断(原先承诺为空时 for 不执行、恰好放行,是存款单的隐性兼容);混合单含贷款分项 → 仍需承诺。
        boolean hasLoanItem = items.stream().anyMatch(it -> "LOAN_CONTRACT".equals(it.getPricingCarrierType()));
        if (hasLoanItem) {
            checkCommitmentCompleteness(app);
            // §2026-09-17 提交时重算基线(用户报「当前贡献度有值、历史申请与审批页基线显示空」)
            recalcCommitmentBaselines(app);
        }
    }

    /**
     * 拟达成贡献度承诺完整性(§7.1;2026-09-16 新增「至少一条」):提交时至少录入一条拟达成贡献度承诺,
     * 且已录承诺的截止日期(end_date)必填。仅在提交链路调用——草稿保存走 saveCommitments,不经过本方法,
     * 客户经理可先存半成品(与 endDate 同款「草稿宽松、提交把关」口径)。
     *
     * <p>调用前提:申请含贷款分项(调用点已判定)。存款申请无承诺录入入口,不适用本校验。</p>
     */
    private void checkCommitmentCompleteness(CcrApplication app) {
        List<CcrApplicationCommitment> commitments = commitmentMapper.selectList(
                new LambdaQueryWrapper<CcrApplicationCommitment>()
                        .eq(CcrApplicationCommitment::getApplicationId, app.getId()));
        if (commitments.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "请至少录入一条拟达成贡献度承诺后提交");
        }
        // 同一指标只允许一条承诺(§2026-09-18 用户拍板):保存草稿链路已拦,此处兜住加校验之前
        // 就已落库的重复草稿——否则这类历史单会带着重复承诺一路走到审批。
        Set<String> seenMetrics = new LinkedHashSet<>();
        for (CcrApplicationCommitment c : commitments) {
            if (c.getEndDate() == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "拟达成贡献度承诺缺少截止日期,请补录承诺截止日期后提交");
            }
            if (!seenMetrics.add(c.getMetricCode())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "同一贡献度指标只能录入一条承诺(重复指标:" + c.getMetricCode()
                                + "),请删除重复承诺后重新提交");
            }
        }
    }

    /**
     * 提交时按数仓重算承诺基线并回写(§2026-09-17 用户报「当前贡献度有值、历史申请与审批页基线显示空」)。
     *
     * <p><b>为什么要有这一步:</b>baseline_value 原只在保存草稿那一刻由
     * {@code CcrApplicationServiceImpl.saveCommitments}→{@code resolveBaseline} 算一次落库,
     * <b>提交链路不重算</b>。草稿期数仓尚无该指标数据 → 落 NULL,此后数仓有值也不回填,
     * 于是「当前贡献度」(实时取数仓)有值、而历史申请页/审批页读落库的基线恒空,两边对不上。
     * 现提交时重算一次,把基线定格在<b>提交时点</b>的数仓值——这也正是「基线=申请时点当前值」的原意。</p>
     *
     * <p><b>顺带收口同一个洞的另一面:</b>「拟达成目标须高于基线」原先也只在保存草稿时校验
     * ({@code saveCommitments} 内)。草稿期基线为 0 可以先存,待数仓值涨上来后提交不再校验,
     * 就会落一条目标低于基线的承诺。此处与回写同一次遍历内比对,不通过即阻断提交(2026-09-17 用户拍板)。</p>
     *
     * <p>口径与保存草稿完全同源——共用 {@link CommitmentBaselineResolver#resolveBaseline}(2026-09-16 收敛),
     * 不另立第二套;基线取不到(OTHER 手工承诺 / 无客户标识 / 指标已停用被字典收敛)时跳过本条,
     * 不覆盖已落库值。</p>
     */
    private void recalcCommitmentBaselines(CcrApplication app) {
        List<CcrApplicationCommitment> commitments = commitmentMapper.selectList(
                new LambdaQueryWrapper<CcrApplicationCommitment>()
                        .eq(CcrApplicationCommitment::getApplicationId, app.getId()));
        for (CcrApplicationCommitment c : commitments) {
            BigDecimal baseline = CommitmentBaselineResolver.resolveBaseline(
                    jdbcTemplate, c.getMetricCode(), app.getCustomerNo(), app.getGroupNo());
            if (baseline == null) {
                continue;
            }
            if (c.getTargetValue() != null && c.getTargetValue().compareTo(baseline) <= 0) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "拟达成贡献度承诺「" + c.getMetricCode() + "」的目标值须高于基线值(提交时基线 "
                                + baseline.stripTrailingZeros().toPlainString() + ",目标 "
                                + c.getTargetValue().stripTrailingZeros().toPlainString()
                                + ")。请刷新当前贡献度并调整目标值后提交");
            }
            if (c.getBaselineValue() == null || c.getBaselineValue().compareTo(baseline) != 0) {
                // 用 LambdaUpdateWrapper 只 set baseline_value:updateById 会把 update_by/update_time
                // 一并按 NULL 写进 SET(实测日志 SET baseline_value=0, update_by=NULL, update_time=NULL),
                // 清掉原有的更新人/更新时间。这里只改基线值本身。
                commitmentMapper.update(null, new LambdaUpdateWrapper<CcrApplicationCommitment>()
                        .eq(CcrApplicationCommitment::getId, c.getId())
                        .set(CcrApplicationCommitment::getBaselineValue, baseline));
            }
        }
    }

    /**
     * 贷款重复申请校验:存量调息带数仓拆分项编号(source_split_no)时按拆分项精确防重
     * (同一拆分项已有在途调息申请即阻断)。2026-09-01 用户拍板:不同申请(总额等条件可能不同)
     * 允许并行提交,取消「客户+担保方式」进行中申请兜底拦截。拟签订(planned='Y' 或无正式合同号)不构成重复;
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
        // §2026-09-03 集团存量调息协议必选(后端防护,防绕过前端直提):EXISTING 须已选数仓集团授信协议
        checkGroupExistingAgreement(app);
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

    /** §2026-09-03 集团存量调息协议必选(提交防护):EXISTING 集团申请 credit_info_json.agreementNo 为空即拒绝——
     *  存量调息=以所选数仓集团授信协议为依托(授信总额=该协议批复额度),无协议不算有批复/授信总额存量;
     *  快照解析失败或非存量调息不拦截(兼容新增/旧单) */
    private void checkGroupExistingAgreement(CcrApplication app) {
        if (!"GROUP".equals(app.getCustomerScope()) || StrUtil.isBlank(app.getCreditInfoJson())) {
            return;
        }
        String businessType = null;
        String agreementNo = null;
        try {
            cn.hutool.json.JSONObject ci = JSONUtil.parseObj(app.getCreditInfoJson());
            businessType = ci.getStr("businessType");
            agreementNo = ci.getStr("agreementNo");
        } catch (Exception ignore) {
            // 快照解析失败视为非存量调息,不拦截
        }
        // 存量新增(EXISTING_NEW,§docs/43)同属存量类,协议必选与存量调息同口径
        // (申请页 validateStep 已用 isExistingLike 拦截,此处服务端兜底防绕过前端提交)
        if (("EXISTING".equals(businessType) || "EXISTING_NEW".equals(businessType))
                && StrUtil.isBlank(agreementNo)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "集团存量调息须选择存量授信协议(集团授信协议编号),请返回申请页选择后重新提交");
        }
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

        // 新草稿:复制客户/集团/申请说明与客户信息快照,记录来源申请
        // (单户/对公 customerInfoJson/creditInfoJson 一并带出——漏复制会导致重提草稿丢客户快照,
        //  年收入/职业/证件号等回填为空;与 CcrApplicationServiceImpl.copyForCreate 复制口径对齐)
        CcrApplication target = new CcrApplication();
        target.setBusinessType(source.getBusinessType());
        target.setCustomerScope(source.getCustomerScope());
        target.setCustomerNo(source.getCustomerNo());
        target.setCustomerInfoJson(source.getCustomerInfoJson());
        target.setCreditInfoJson(source.getCreditInfoJson());
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
            // §2026-09-17 用户报:重提后测算利率为空——原实现只复制了 originalRate,漏了 calculatedRate;
            // 前端测算利率为必填并有「未录入测算利率」校验,漏复制会导致重提草稿必须先手工重填才能提交
            copy.setCalculatedRate(src.getCalculatedRate());
            copy.setRateDirection(src.getRateDirection());
            // 存量拆分项标记:存量新增重提时必须保留,否则存量行丢失来源标记、
            // 被当作手工新增行匹配 NEW 矩阵并参与整单锚定(§docs/43 分流判据即 sourceSplitNo)
            copy.setSourceSplitNo(src.getSourceSplitNo());
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

        // 复制他行融资概要/明细(§2026-09-17 用户报:重提后融资情况全空——原实现整块漏复制;
        // 前端 loadDraftIntoForm 从草稿详情的 otherLoans/creditSummary 回填,新草稿无数据则必然回填为空)
        for (CcrApplicationOtherLoan loan : otherLoanMapper.selectList(
                new LambdaQueryWrapper<CcrApplicationOtherLoan>()
                        .eq(CcrApplicationOtherLoan::getApplicationId, id))) {
            CcrApplicationOtherLoan copy = new CcrApplicationOtherLoan();
            copy.setApplicationId(target.getId());
            copy.setLenderName(loan.getLenderName());
            copy.setCreditAmount(loan.getCreditAmount());
            copy.setUsedAmount(loan.getUsedAmount());
            copy.setBalanceAmount(loan.getBalanceAmount());
            copy.setAnnualRate(loan.getAnnualRate());
            copy.setInputMode(loan.getInputMode());
            otherLoanMapper.insert(copy);
        }
        for (CcrApplicationCreditSummary summary : creditSummaryMapper.selectList(
                new LambdaQueryWrapper<CcrApplicationCreditSummary>()
                        .eq(CcrApplicationCreditSummary::getApplicationId, id))) {
            CcrApplicationCreditSummary copy = new CcrApplicationCreditSummary();
            copy.setApplicationId(target.getId());
            copy.setLenderCount(summary.getLenderCount());
            copy.setCreditAmountTotal(summary.getCreditAmountTotal());
            copy.setUsedAmountTotal(summary.getUsedAmountTotal());
            copy.setLoanAccountCount(summary.getLoanAccountCount());
            copy.setOverdueAccountCount(summary.getOverdueAccountCount());
            copy.setOverdueBalance(summary.getOverdueBalance());
            copy.setNplBalance(summary.getNplBalance());
            copy.setSpecialMentionBalance(summary.getSpecialMentionBalance());
            copy.setExternalGuaranteeBalance(summary.getExternalGuaranteeBalance());
            copy.setReportDate(summary.getReportDate());
            creditSummaryMapper.insert(copy);
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
     * 分项金额勾稽硬校验(2026-09-07 用户拍板):分项申请金额合计不得超过授信总额才能提交——
     * 存量调息自动带出拆分项可能只是协议/批复总额的一部分(部分拆分执行),合计小于总额属正常,
     * 不再强制相等;仅当分项合计超过授信总额(实际执行额度超过可用额度,说明拆分超限)才整单回滚。
     * 单户授信总额=credit_info_json.totalCredit(存量=数仓授信协议金额合计自动带出,新增=手工录入);
     * 集团授信总额=group_info_json.applyAmount(前端按集团批复授信额度优先回退手工录入总授信落库,
     * 与勾稽条/序列化/路由定档同口径:存量集团=集团当前授信协议批复总额,新增集团=新增授信总额)。
     * 集团同时沿用成员额度勾稽(checkGroupConstraints,成员申请金额合计≤申请额度)并存。
     * 快照缺省回退分项金额(无明确总额)时跳过(前端已拦)。
     * 与 submitCheck 预校验 guaranteeTotalPrecheck 同口径双拦截,失败整单回滚。
     */
    private void checkGuaranteeTotal(CcrApplication app, List<CcrPricingItem> items) {
        BigDecimal totalCredit;
        if ("GROUP".equals(app.getCustomerScope())) {
            totalCredit = applyAmountOf(app);
        } else {
            if (StrUtil.isBlank(app.getCreditInfoJson())) {
                return;
            }
            try {
                totalCredit = JSONUtil.parseObj(app.getCreditInfoJson()).getBigDecimal("totalCredit");
            } catch (Exception ignored) {
                return; // 快照解析失败跳过勾稽(前端已拦)
            }
        }
        if (totalCredit == null || totalCredit.signum() <= 0) {
            return;
        }
        BigDecimal itemSum = items.stream()
                .map(CcrPricingItem::getPricingAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (itemSum.subtract(totalCredit).compareTo(new BigDecimal("0.01")) > 0) {
            throw new ServiceException(ErrorCode.QUALITY_BLOCK.getCode(),
                    "分项申请金额合计 " + itemSum + " 万元超过授信总额 " + totalCredit + " 万元,请调减分项金额(合计不得超过授信总额)");
        }
    }

    /**
     * 存量调息申请利率上限硬校验(2026-09-07 用户拍板):仅贷款存量调息(EXISTING,单户+集团)申请利率不得高于原利率——
     * 高于原利率(调升)整单回滚;等于(维持原利率)放行;原利率为空该分项跳过。
     * 存量判定复用 resolveNewOrExisting(credit_info_json.businessType=EXISTING,存款恒 NEW 不走此)。
     * 与 submitCheck 预校验 existingRatePrecheck 同口径双拦截,失败整单回滚。
     */
    private void checkExistingRateCap(CcrApplication app, List<CcrPricingItem> items) {
        for (CcrPricingItem item : items) {
            if (!"EXISTING".equals(resolveNewOrExisting(app, item))) {
                continue;
            }
            BigDecimal original = item.getOriginalRate();
            BigDecimal requested = item.getRequestedRate();
            if (original == null || requested == null || requested.compareTo(original) <= 0) {
                continue;
            }
            throw new ServiceException(ErrorCode.QUALITY_BLOCK.getCode(),
                    "授信分项[" + item.getPricingItemNo() + "]存量调息申请利率 " + requested.stripTrailingZeros().toPlainString()
                            + "% 不得高于原利率 " + original.stripTrailingZeros().toPlainString() + "%,请保持或调低利率");
        }
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

    /** 集团定档金额(§B18 路由金额定档基准;非集团返回 null):
     *  优先取本次申请额度 group_info_json.applyAmount(集团 NEW=本次手工录入授信总额/EXISTING=所选授信协议额度,
     *  均由前端 serializeGroupInfo 写入)——与提交勾稽 checkGroupConstraints→applyAmountOf 同口径;
     *  空则回退数仓批复总额度(兼容旧申请/草稿,其 group_info_json 未落 applyAmount) */
    private BigDecimal loadGroupCreditTotal(CcrApplication app) {
        if (!"GROUP".equals(app.getCustomerScope()) || StrUtil.isBlank(app.getGroupNo())) {
            return null;
        }
        BigDecimal applyAmount = applyAmountOf(app);
        if (applyAmount != null) {
            return applyAmount;
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
        // 2026-09-04 综合/零售两级支行:申请机构为零售支行时链上插管理综合支行长节点、支行层终审上收
        input.setRetailBranch(BranchTypeSupport.isRetailBranch(jdbcTemplate, app.getApplicantOrgId()));
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
     *  该字段由前端申请页业务类型显式提交(§用户要求),不以分项原利率推断——原利率属存量贷款合同带出,不能代表授信新增/存量的判定口径。
     *
     *  <p>EXISTING_NEW(存量新增,docs/43 §三):<b>恒返 NEW</b>。该返回值有两处作用——
     *  其一,新增行据此按 NEW 匹配矩阵(存量行根本不过矩阵,由提交循环直接搭链上送,见 h 段);
     *  其二,存量利率上限两道校验(existingRatePrecheck / checkExistingRateCap)首句均为
     *  {@code if (!"EXISTING".equals(resolveNewOrExisting(...))) continue;},恒返 NEW 即整单跳过,
     *  这正是「存量新增的存量行不受原执行利率上限约束」的口径实现(docs/43 口径 11)。</p> */
    private String resolveNewOrExisting(CcrApplication app, CcrPricingItem item) {
        // 存款按期限档设上限、D16b 无部门层级,矩阵无存量/新增之分,恒按新增路由
        // (存款存量账户反查带出的 originalRate 仅作展示,不得据此判 EXISTING 匹配不到矩阵行)
        if ("DEPOSIT".equals(businessBigType(app))) {
            return "NEW";
        }
        if (StrUtil.isNotBlank(app.getCreditInfoJson())) {
            try {
                String bt = JSONUtil.parseObj(app.getCreditInfoJson()).getStr("businessType");
                // 存量新增:恒返 NEW。必须显式判定,否则掉到下方逐分项回退——存量行(有 originalRate)返 EXISTING,
                // 会反过来被存量利率上限校验拦住(口径:存量新增的存量行不受该约束)
                if ("EXISTING_NEW".equals(bt)) {
                    return "NEW";
                }
                if ("NEW".equals(bt) || "EXISTING".equals(bt)) {
                    return bt;
                }
            } catch (Exception ignore) {
                // 快照解析失败回退原利率判定
            }
        }
        return item.getOriginalRate() != null ? "EXISTING" : "NEW";
    }

    /** 是否「存量新增」申请(§docs/43):credit_info_json.businessType = EXISTING_NEW。
     *  <p>该类单内分项按来源分两类:存量行(source_split_no 非空,数仓协议带出)不匹配矩阵、搭新增链上送;
     *  新增行(source_split_no 空,客户经理手工录入)匹配 NEW 矩阵并锚定整单链。</p> */
    private boolean isMixedExistingNew(CcrApplication app) {
        if (StrUtil.isBlank(app.getCreditInfoJson())) {
            return false;
        }
        try {
            return "EXISTING_NEW".equals(JSONUtil.parseObj(app.getCreditInfoJson()).getStr("businessType"));
        } catch (Exception ignore) {
            return false;
        }
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
