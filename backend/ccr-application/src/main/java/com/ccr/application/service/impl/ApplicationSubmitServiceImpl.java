package com.ccr.application.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrApplicationCommitment;
import com.ccr.application.domain.CcrApplicationMember;
import com.ccr.application.domain.CcrApplicationRelation;
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
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrApplicationMemberMapper;
import com.ccr.application.mapper.CcrApplicationRelationMapper;
import com.ccr.application.mapper.CcrGuaranteeMeasureMapper;
import com.ccr.application.mapper.CcrGuaranteePackageMapper;
import com.ccr.application.mapper.CcrPricingItemContractRelMapper;
import com.ccr.application.mapper.CcrPricingItemDepositRelMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.application.service.ApplicationSubmitService;
import com.ccr.application.service.DataWarehouseService;
import com.ccr.application.service.SnapshotGateway;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 申请提交编排实现(§7.1 步骤7-11)
 * 快照采集:按主体从数仓最新批次采集,集团链 集团→成员→额度→分项→合同→借据(§A.6);
 * 冻结:LPR 版本 + 规则集版本 + 路由生效日期(§8.4);
 * 路由:首节点恒为 BRANCH_MANAGER,终审岗位写 route_code
 */
@Service
public class ApplicationSubmitServiceImpl implements ApplicationSubmitService {

    /** 分项终态(一合同一有效分项检查中不阻断) */
    private static final Set<String> ITEM_TERMINAL_STATUS = Set.of("FINAL", "REJECTED", "VETOED", "CLOSED", "SUPERSEDED");

    /** 允许关联重提的原申请状态(§7.6:否决后保持终态,重提创建新申请;REJECTED 为 D18b 最典型入口) */
    private static final Set<String> REAPPLY_SOURCE_STATUS = Set.of("FINAL", "REJECTED", "VETOED", "RETURNED", "CLOSED");

    /** 已批准分项状态(沿用原决议,不重新审批) */
    private static final Set<String> APPROVED_ITEM_STATUS = Set.of("FINAL", "APPROVED_LEVEL");

    /** 数据时效容忍天数(§9.4 默认 3 个自然日,超过 BLOCK 阻断提交;与快照质量规则同一配置) */
    @Value("${ccr.snapshot.data-stale-days:3}")
    private int dataStaleDays;

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
    private DataWarehouseService dataWarehouseService;
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

    // ==================== 路由预览(§13.1) ====================

    @Override
    public RoutePreviewResponse routePreview(Long id) {
        CcrApplication app = requireApplication(id);
        List<CcrPricingItem> items = routableItems(id);
        BigDecimal groupCreditTotal = loadGroupCreditTotal(app);

        RoutePreviewResponse response = new RoutePreviewResponse();
        response.setApplicationId(id);
        response.setGroupCreditTotal(groupCreditTotal);

        Map<String, Map<String, Object>> corpCache = new HashMap<>();
        List<RoutePreviewResponse.ItemRoutePreview> previews = new ArrayList<>();
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
                preview.setLprVersionId(route.getLprVersionId());
                preview.setLprVersionCode(route.getLprVersionCode());
                preview.setMessage(route.getMessage());
                if (response.getLprVersionId() == null) {
                    response.setLprVersionId(route.getLprVersionId());
                    response.setLprVersionCode(route.getLprVersionCode());
                }
            } catch (ServiceException e) {
                preview.setErrorCode(e.getCode());
                preview.setErrorMessage(e.getMessage());
            }
            previews.add(preview);
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
            if (dataWarehouseService.findGroup(app.getGroupNo()) == null) {
                items.add(precheckItem("SUBJECT_EXISTS", "BLOCK", app.getGroupNo(), "集团主数据快照缺失"));
            }
            if (dataWarehouseService.findGroupCredit(app.getGroupNo()) == null) {
                items.add(precheckItem("GROUP_CREDIT_EXISTS", "BLOCK", app.getGroupNo(), "集团授信快照缺失"));
            }
            List<Map<String, Object>> dwMembers = dataWarehouseService.groupMembers(app.getGroupNo());
            Map<String, Map<String, Object>> dwMemberMap = new HashMap<>();
            for (Map<String, Object> row : dwMembers) {
                dwMemberMap.put(String.valueOf(row.get("member_customer_no")), row);
            }
            for (CcrApplicationMember member : applicationMembers(app.getId())) {
                Map<String, Object> dwMember = dwMemberMap.get(member.getMemberCustomerNo());
                if (dwMember == null || !memberInGroup(dwMember)) {
                    items.add(precheckItem("GROUP_MEMBER_VALID", "BLOCK", member.getMemberCustomerNo(),
                            "涉及成员不在集团有效成员快照中"));
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
                items.add(precheckItem("SUBJECT_EXISTS", "BLOCK", app.getCustomerNo(), "客户主数据快照缺失"));
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
        // b) 完整性校验
        checkCompleteness(app, items);
        // c) 集团场景校验(返回批复总额度供路由定档,§B18)
        BigDecimal groupCreditTotal = checkGroupConstraints(app);
        // d) 一合同一有效分项/一账户一有效分项(跨申请,非终态阻断)
        checkCarrierUniqueness(app, items);
        // e) 逐分项硬边界(突破阻断)
        for (CcrPricingItem item : items) {
            ruleEngine.checkHardBoundary(businessBigType(app), item.getProductCode(), item.getRequestedRate());
        }
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
        Map<String, Map<String, Object>> corpCache = new HashMap<>();
        List<SubmitResponse.ItemRoute> itemRoutes = new ArrayList<>();
        for (CcrPricingItem item : items) {
            RouteResult route = rateMatrixRouter.calcRoute(buildRouteInput(app, item, groupCreditTotal, corpCache));
            item.setStatus(PricingItemStatus.ROUTING.getCode());
            item.setStartNodeCode(route.getStartNodeCode());
            item.setCurrentNodeCode(route.getStartNodeCode());
            item.setRouteCode(route.getFinalNodeCode());
            item.setBoundaryRate(route.getBoundaryRate());
            item.setMatchedMatrixNo(route.getMatchedMatrixNo());
            pricingItemMapper.updateById(item);
            itemRoutes.add(toItemRoute(item, route.getRouteChain()));
        }

        // i) 主申请置 ROUTING、冻结版本、写提交时间(freeze 已绑定快照包,重取避免乐观锁过期)
        CcrApplication fresh = applicationMapper.selectById(id);
        fresh.setStatus(ApplicationStatus.ROUTING.getCode());
        fresh.setSubmitTime(LocalDateTime.now());
        fresh.setLprVersionId(lpr.getId());
        fresh.setRuleSetVersionId(ruleSet == null ? null : ruleSet.getId());
        fresh.setRouteAsOfDate(routeAsOfDate);
        fresh.setSnapshotBundleId(bundle.getBundleId());
        applicationMapper.updateById(fresh);

        // j) 同事务写 Outbox 事件(§3.5/§7.2 步骤7):逐分项 FLOW_START + 提交通知 NOTIFY,异步消费
        publishSubmitEvents(fresh, items);

        SubmitResponse response = buildSubmitResponse(fresh, items, true);
        response.setItems(itemRoutes);
        return response;
    }

    /**
     * j) 同事务写 Outbox 事件(§3.5/§7.2 步骤7):逐分项 FLOW_START(payload 含分项+起始节点+流程定义),
     * 以及提交通知 NOTIFY(申请人 + 首节点支行行长);事件写入失败随提交事务整体回滚,不出现半成品
     */
    private void publishSubmitEvents(CcrApplication app, List<CcrPricingItem> items) {
        String createBy = app.getApplicantUserId() == null ? "0" : app.getApplicantUserId().toString();
        for (CcrPricingItem item : items) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("applicationId", app.getId());
            payload.put("applicationNo", app.getApplicationNo());
            payload.put("pricingItemId", item.getId());
            payload.put("pricingItemNo", item.getPricingItemNo());
            payload.put("nodeCode", item.getStartNodeCode());
            payload.put("routeCode", item.getRouteCode());
            // 流程定义版本:利率审批标准流程(Warm-Flow 轨迹载体)
            payload.put("flowCode", "rate_approval");
            payload.put("createBy", createBy);
            outboxService.publish(OutboxEventType.FLOW_START, item.getPricingItemNo(), JSONUtil.toJsonStr(payload));
        }
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
        branchNotify.put("recipientType", "ROLE");
        branchNotify.put("recipientId", "branch_manager");
        branchNotify.put("channel", "SYSTEM");
        branchNotify.put("messageKey", "SUBMIT_NOTIFY:APP:" + app.getId() + ":BRANCH_MANAGER");
        branchNotify.put("content", "定价申请 " + app.getApplicationNo() + " 已提交,待支行行长审批(分项:"
                + itemNos + ")");
        outboxService.publish(OutboxEventType.NOTIFY, "SUBMIT:APP:" + app.getId() + ":BRANCH_MANAGER",
                JSONUtil.toJsonStr(branchNotify));
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
            if (item.getRequestedRate() == null || item.getPricingAmount() == null
                    || StrUtil.isBlank(item.getProductCode()) || item.getTermValue() == null
                    || StrUtil.isBlank(item.getTermUnit())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "分项[" + item.getPricingItemNo() + "]必填字段不全(产品/期限/金额/申请利率)");
            }
            if (groupScope && StrUtil.isBlank(item.getMemberCustomerNo())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "分项[" + item.getPricingItemNo() + "]缺少集团成员客户号");
            }
            if ("LOAN_CONTRACT".equals(item.getPricingCarrierType())) {
                Long cnt = contractRelMapper.selectCount(new LambdaQueryWrapper<CcrPricingItemContractRel>()
                        .eq(CcrPricingItemContractRel::getPricingItemId, item.getId()));
                if (cnt == 0) {
                    throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                            "分项[" + item.getPricingItemNo() + "]缺少贷款合同关系(现有合同号或拟签合同标识)");
                }
            }
            if ("DEPOSIT_ACCOUNT".equals(item.getPricingCarrierType())) {
                Long cnt = depositRelMapper.selectCount(new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                        .eq(CcrPricingItemDepositRel::getPricingItemId, item.getId()));
                if (cnt == 0) {
                    throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                            "分项[" + item.getPricingItemNo() + "]缺少存款账户关系");
                }
            }
            checkGuaranteeCompleteness(item);
        }
    }

    /** 担保完整性(§9.3-5):非信用类(guaranteeType≠CREDIT)分项必须有担保组合且担保措施非空 */
    private void checkGuaranteeCompleteness(CcrPricingItem item) {
        if (!"LOAN_CONTRACT".equals(item.getPricingCarrierType())) {
            return; // 存款分项无担保概念
        }
        CcrGuaranteePackage pkg = item.getGuaranteePackageId() == null ? null
                : guaranteePackageMapper.selectById(item.getGuaranteePackageId());
        String guaranteeType = pkg == null ? null : pkg.getMainGuaranteeType();
        if (StrUtil.isBlank(guaranteeType) || "CREDIT".equals(guaranteeType)) {
            return; // 未冻结担保组合按信用类对待
        }
        Long measureCount = guaranteeMeasureMapper.selectCount(new LambdaQueryWrapper<CcrGuaranteeMeasure>()
                .eq(CcrGuaranteeMeasure::getPackageId, pkg.getId()));
        if (measureCount == null || measureCount == 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "分项[" + item.getPricingItemNo() + "]非信用类担保(" + guaranteeType + ")必须登记担保措施");
        }
    }

    /**
     * c) 集团校验:集团/授信快照存在、成员在团、成员分配额度合计≤集团批复总额度
     * (EXCLUSIVE 加总;SHARED 按 shared_limit_group_no 分组取其一不加总)
     *
     * @return 集团批复总额度(路由金额定档基准,§B18)
     */
    private BigDecimal checkGroupConstraints(CcrApplication app) {
        if (!"GROUP".equals(app.getCustomerScope())) {
            return null;
        }
        if (dataWarehouseService.findGroup(app.getGroupNo()) == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "集团[" + app.getGroupNo() + "]主数据快照不存在");
        }
        Map<String, Object> credit = dataWarehouseService.findGroupCredit(app.getGroupNo());
        if (credit == null) {
            throw new ServiceException(ErrorCode.LIMIT_INCONSISTENT.getCode(),
                    "集团[" + app.getGroupNo() + "]授信快照不存在");
        }
        Map<String, Map<String, Object>> dwMemberMap = new HashMap<>();
        for (Map<String, Object> row : dataWarehouseService.groupMembers(app.getGroupNo())) {
            dwMemberMap.put(String.valueOf(row.get("member_customer_no")), row);
        }
        for (CcrApplicationMember member : applicationMembers(app.getId())) {
            Map<String, Object> dwMember = dwMemberMap.get(member.getMemberCustomerNo());
            if (dwMember == null || !memberInGroup(dwMember)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "成员[" + member.getMemberCustomerNo() + "]不在集团有效成员快照中");
            }
        }
        // 额度勾稽:EXCLUSIVE 加总 + SHARED 按共享组取最大一条,不得超过批复总额度
        List<Map<String, Object>> limits = dataWarehouseService.memberLimitsByGroup(String.valueOf(credit.get("group_credit_no")));
        BigDecimal allocatedSum = BigDecimal.ZERO;
        Map<String, BigDecimal> sharedGroups = new HashMap<>();
        for (Map<String, Object> limit : limits) {
            BigDecimal allocated = toBigDecimal(limit.get("allocated_amount"));
            if ("SHARED".equals(String.valueOf(limit.get("allocation_mode")))) {
                String sharedGroupNo = String.valueOf(limit.get("shared_limit_group_no"));
                sharedGroups.merge(sharedGroupNo, allocated, BigDecimal::max);
            } else {
                allocatedSum = allocatedSum.add(allocated);
            }
        }
        for (BigDecimal shared : sharedGroups.values()) {
            allocatedSum = allocatedSum.add(shared);
        }
        BigDecimal approvedTotal = toBigDecimal(credit.get("approved_total_amount"));
        if (allocatedSum.compareTo(approvedTotal) > 0) {
            throw new ServiceException(ErrorCode.LIMIT_INCONSISTENT.getCode(),
                    "成员分配额度合计 " + allocatedSum + " 超过集团批复总额度 " + approvedTotal);
        }
        return approvedTotal;
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
                    if (StrUtil.isBlank(rel.getDepositAccountHash())) {
                        continue; // 拟开户无账号,不参与唯一性
                    }
                    List<CcrPricingItemDepositRel> conflicts = depositRelMapper.selectList(
                            new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                                    .eq(CcrPricingItemDepositRel::getDepositAccountHash, rel.getDepositAccountHash())
                                    .ne(CcrPricingItemDepositRel::getPricingItemId, item.getId()));
                    blockIfNonTerminal(conflicts.stream().map(CcrPricingItemDepositRel::getPricingItemId).toList(),
                            "存款账号[" + rel.getDepositAccountHash() + "]");
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
        /** 存款账号哈希→账户快照记录id */
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
        // 存款账户快照(按分项账户关系引用的账号哈希)
        for (CcrPricingItem item : items) {
            if (!"DEPOSIT_ACCOUNT".equals(item.getPricingCarrierType())) {
                continue;
            }
            List<CcrPricingItemDepositRel> rels = depositRelMapper.selectList(
                    new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                            .eq(CcrPricingItemDepositRel::getPricingItemId, item.getId()));
            for (CcrPricingItemDepositRel rel : rels) {
                if (StrUtil.isBlank(rel.getDepositAccountHash())
                        || collect.depositRecordIds.containsKey(rel.getDepositAccountHash())) {
                    continue;
                }
                Map<String, Object> account = dataWarehouseService.findDepositAccountByHash(rel.getDepositAccountHash());
                if (account != null) {
                    Long recordId = addSnapshotRecord(bundleId, "dw_deposit_account_snapshot", "DEPOSIT_ACCOUNT",
                            String.valueOf(account.get("customer_no")), account);
                    collect.depositRecordIds.put(rel.getDepositAccountHash(), recordId);
                }
            }
        }
        return collect;
    }

    /** 集团链采集:集团→成员→额度→分项→合同→借据 */
    private void collectGroupChain(CcrApplication app, Long bundleId, SnapshotCollect collect) {
        Map<String, Object> group = dataWarehouseService.findGroup(app.getGroupNo());
        Long groupRecordId = group == null ? null
                : addSnapshotRecord(bundleId, "dw_customer_group_snapshot", "GROUP", app.getGroupNo(), group);
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
            // 成员快照(补充 record_status/valid_to 供快照质量规则判定成员有效性)
            Map<String, Object> dwMember = dataWarehouseService.findGroupMember(app.getGroupNo(), memberNo);
            Long memberRecordId = null;
            if (dwMember != null) {
                Map<String, Object> core = new LinkedHashMap<>(dwMember);
                core.put("record_status", memberInGroup(dwMember) ? "ACTIVE" : "INACTIVE");
                if (dwMember.get("relation_end") != null) {
                    core.put("valid_to", String.valueOf(dwMember.get("relation_end")).substring(0, 10));
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
            // 成员额度→用信分项→合同→借据
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
            collectTrancheChain(bundleId, String.valueOf(limit.get("member_limit_no")), limitRecordId, collect);
        }
    }

    /** 用信分项→合同→借据链 */
    private void collectTrancheChain(Long bundleId, String memberLimitNo, Long limitRecordId, SnapshotCollect collect) {
        int trancheSeq = 0;
        for (Map<String, Object> tranche : dataWarehouseService.tranchesByLimit(memberLimitNo)) {
            trancheSeq++;
            String trancheNo = String.valueOf(tranche.get("tranche_no"));
            Long trancheRecordId = addSnapshotRecord(bundleId, "dw_credit_tranche_snapshot", "TRANCHE", trancheNo, tranche);
            if (limitRecordId != null) {
                collect.relations.add(new SnapshotRelationInput(limitRecordId, trancheRecordId, "LIMIT_TO_TRANCHE", trancheSeq));
            }
            collectContractChain(bundleId, trancheNo, trancheRecordId, collect);
        }
    }

    /** 合同→借据链 */
    private void collectContractChain(Long bundleId, String trancheNo, Long trancheRecordId, SnapshotCollect collect) {
        int contractSeq = 0;
        for (Map<String, Object> contract : dataWarehouseService.contractsByTranche(trancheNo)) {
            contractSeq++;
            String contractNo = String.valueOf(contract.get("contract_no"));
            Long contractRecordId = collect.contractRecordIds.computeIfAbsent(contractNo,
                    k -> addSnapshotRecord(bundleId, "dw_loan_contract_snapshot", "CONTRACT", contractNo, contract));
            if (trancheRecordId != null) {
                collect.relations.add(new SnapshotRelationInput(trancheRecordId, contractRecordId, "TRANCHE_TO_CONTRACT", contractSeq));
            }
            collectNotes(bundleId, contractNo, contractRecordId, collect);
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
        }
        for (Map<String, Object> financing : dataWarehouseService.ownFinancing(customerNo)) {
            addSnapshotRecord(bundleId, "dw_own_financing_snapshot", "FINANCING",
                    String.valueOf(financing.get("contract_no")), financing);
        }
        addContributionRecord(bundleId, customerNo);
        // 名下合同→借据(合同关系回填与核验数据源)
        for (Map<String, Object> contract : dataWarehouseService.contractsByBorrower(customerNo)) {
            String contractNo = String.valueOf(contract.get("contract_no"));
            Long contractRecordId = collect.contractRecordIds.computeIfAbsent(contractNo,
                    k -> addSnapshotRecord(bundleId, "dw_loan_contract_snapshot", "CONTRACT", contractNo, contract));
            collectNotes(bundleId, contractNo, contractRecordId, collect);
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
                    Long recordId = StrUtil.isBlank(rel.getDepositAccountHash()) ? null
                            : collect.depositRecordIds.get(rel.getDepositAccountHash());
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
        target.setOrgId(source.getOrgId());
        target.setApplicationRemark(source.getApplicationRemark());
        target.setSourceApplicationId(source.getId());
        target.setApplicationNo("CCR" + cn.hutool.core.date.DateUtil.format(new java.util.Date(), "yyyyMMdd")
                + IdUtil.fastSimpleUUID().substring(0, 4).toUpperCase());
        target.setStatus(ApplicationStatus.DRAFT.getCode());
        target.setDataBaselineJson(buildBaselineJson(source));
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
            copy.setCreditTrancheRef(src.getCreditTrancheRef());
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
            commitmentMapper.insert(copy);
        }

        // 原申请置 RETURNED(终态保留,不回 DRAFT,§14.1);同事务保证重提成功才落状态
        if (!ApplicationStatus.RETURNED.getCode().equals(source.getStatus())) {
            source.setStatus(ApplicationStatus.RETURNED.getCode());
            applicationMapper.updateById(source);
        }
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
            copy.setDepositAccountNoCipher(rel.getDepositAccountNoCipher());
            copy.setDepositAccountHash(rel.getDepositAccountHash());
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
        input.setNewOrExisting(item.getOriginalRate() != null ? "EXISTING" : "NEW");
        input.setCustomerType(resolveCustomerType(app, item, corpCache));
        input.setProductCode(item.getProductCode());
        input.setAmount(item.getPricingAmount());
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

    /** 客户类型:PERSONAL/SOE/NON_SOE(对公取数仓企业性质,缺省 NON_SOE) */
    private String resolveCustomerType(CcrApplication app, CcrPricingItem item,
                                       Map<String, Map<String, Object>> corpCache) {
        if ("INDIVIDUAL".equals(app.getCustomerScope())) {
            return "PERSONAL";
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

    /** 当前生效 LPR 版本(与 GET /ccr/rule/version/current 同口径) */
    private CcrLprVersion currentLpr() {
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
