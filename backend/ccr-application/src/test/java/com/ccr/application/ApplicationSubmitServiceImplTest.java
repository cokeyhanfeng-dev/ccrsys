package com.ccr.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrApplicationMember;
import com.ccr.application.domain.CcrApplicationRelation;
import com.ccr.application.domain.CcrGroup;
import com.ccr.application.domain.CcrGroupMember;
import com.ccr.application.domain.CcrGuaranteePackage;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.domain.CcrPricingItemContractRel;
import com.ccr.application.dto.SnapshotBundleResult;
import com.ccr.application.dto.SubmitCheckResponse;
import com.ccr.application.dto.SubmitResponse;
import com.ccr.common.cache.CcrCacheUtil;
import com.ccr.common.enums.ErrorCode;
import com.ccr.application.mapper.CcrApplicationCommitmentMapper;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrApplicationMemberMapper;
import com.ccr.application.mapper.CcrApplicationRelationMapper;
import com.ccr.application.mapper.CcrGuaranteeMeasureMapper;
import com.ccr.application.mapper.CcrGuaranteePackageMapper;
import com.ccr.application.mapper.CcrPricingItemContractRelMapper;
import com.ccr.application.mapper.CcrPricingItemDepositRelMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.application.service.DataWarehouseService;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.application.service.ManualGroupService;
import com.ccr.application.service.SnapshotGateway;
import com.ccr.application.service.impl.ApplicationSubmitServiceImpl;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrLprVersion;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.engine.RuleEngine;
import com.ccr.rule.mapper.CcrLprVersionMapper;
import com.ccr.rule.mapper.CcrRateRuleSetMapper;
import com.ccr.rule.service.RateMatrixRouter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交编排单元测试(§7.1:集团额度勾稽/一合同一有效分项/硬边界/快照绑定与版本冻结/幂等;§7.6 重提沿用)
 */
@ExtendWith(MockitoExtension.class)
class ApplicationSubmitServiceImplTest {

    @Mock
    private CcrApplicationMapper applicationMapper;
    @Mock
    private CcrApplicationMemberMapper applicationMemberMapper;
    @Mock
    private CcrPricingItemMapper pricingItemMapper;
    @Mock
    private CcrPricingItemContractRelMapper contractRelMapper;
    @Mock
    @SuppressWarnings("unused")
    private CcrPricingItemDepositRelMapper depositRelMapper;
    @Mock
    private CcrGuaranteePackageMapper guaranteePackageMapper;
    @Mock
    @SuppressWarnings("unused")
    private CcrGuaranteeMeasureMapper guaranteeMeasureMapper;
    @Mock
    private CcrApplicationRelationMapper applicationRelationMapper;
    @Mock
    private CcrApplicationCommitmentMapper commitmentMapper;
    @Mock
    private DataWarehouseService dataWarehouseService;
    @Mock
    private SnapshotGateway snapshotGateway;
    @Mock
    private RateMatrixRouter rateMatrixRouter;
    @Mock
    private RuleEngine ruleEngine;
    @Mock
    private CcrLprVersionMapper lprVersionMapper;
    @Mock
    private CcrRateRuleSetMapper ruleSetMapper;
    @Mock
    private com.ccr.common.outbox.OutboxService outboxService;
    @Mock
    private CcrCacheUtil cacheUtil;
    @Mock
    private ApplicationAccessService applicationAccessService;
    @Mock
    private ManualGroupService manualGroupService;

    @InjectMocks
    private ApplicationSubmitServiceImpl service;

    /** 纯 Mockito 单测无 MyBatis 启动过程,需手工初始化实体 TableInfo(Lambda 包装器列解析依赖) */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrApplication.class);
        TableInfoHelper.initTableInfo(assistant, CcrPricingItem.class);
    }

    // ---------- 测试数据构造 ----------

    private CcrApplication groupApp() {
        CcrApplication app = new CcrApplication();
        app.setId(1L);
        app.setApplicationNo("CCR20260806ABCD");
        app.setBusinessType("LOAN");
        app.setCustomerScope("GROUP");
        app.setGroupNo("GROUP001");
        app.setApplicantUserId(1000L);
        app.setApplicantOrgId(1001L);
        app.setStatus("DRAFT");
        app.setVersionNo(1);
        return app;
    }

    private CcrPricingItem loanItem(Long id, String memberNo) {
        CcrPricingItem item = new CcrPricingItem();
        item.setId(id);
        item.setApplicationId(1L);
        item.setPricingItemNo("PI-" + id);
        item.setPricingCustomerNo(memberNo);
        item.setMemberCustomerNo(memberNo);
        item.setPricingCarrierType("LOAN_CONTRACT");
        item.setProductCode("LOAN_GENERAL");
        item.setTermValue(12);
        item.setTermUnit("MONTH");
        item.setPricingAmount(new BigDecimal("1500"));
        item.setRequestedRate(new BigDecimal("3.60"));
        item.setCurrentApprovalRate(new BigDecimal("3.60"));
        item.setRateDirection("LOWER_BETTER");
        item.setStatus("DRAFT");
        item.setInheritFlag("N");
        item.setVersionNo(1);
        return item;
    }

    private CcrApplicationMember member(String memberNo) {
        CcrApplicationMember member = new CcrApplicationMember();
        member.setApplicationId(1L);
        member.setMemberCustomerNo(memberNo);
        member.setRequestAmount(new BigDecimal("1500"));
        member.setCurrency("CNY");
        return member;
    }

    /** 集团数仓:批复总额度 1 亿 */
    private void stubGroupDw(BigDecimal approvedTotal, List<Map<String, Object>> limits) {
        Map<String, Object> group = new HashMap<>();
        group.put("group_no", "GROUP001");
        group.put("group_status", "NORMAL");
        lenient().when(dataWarehouseService.findGroup("GROUP001")).thenReturn(group);
        Map<String, Object> credit = new HashMap<>();
        credit.put("group_credit_no", "GCREDIT001");
        credit.put("approved_total_amount", approvedTotal);
        lenient().when(dataWarehouseService.findGroupCredit("GROUP001")).thenReturn(credit);
        Map<String, Object> memberRow = new HashMap<>();
        memberRow.put("member_customer_no", "MEMBER_A");
        memberRow.put("relation_end", null);
        lenient().when(dataWarehouseService.groupMembers("GROUP001")).thenReturn(List.of(memberRow));
        lenient().when(dataWarehouseService.memberLimitsByGroup("GCREDIT001")).thenReturn(limits);
    }

    private Map<String, Object> exclusiveLimit(String memberNo, String amount) {
        Map<String, Object> limit = new HashMap<>();
        limit.put("member_limit_no", "ML-" + memberNo);
        limit.put("member_customer_no", memberNo);
        limit.put("allocation_mode", "EXCLUSIVE");
        limit.put("allocated_amount", new BigDecimal(amount));
        return limit;
    }

    // ---------- 手工集团(ccr_group 回退;数仓无主数据/授信) ----------

    private CcrApplication manualGroupApp() {
        CcrApplication app = groupApp();
        app.setGroupNo("GROUP9001");
        // 数仓未收录 → 新增集团,申请额度(本次新增授信)随申请补录(§docs/19 §4.5)
        app.setGroupInfoJson("{\"applyAmount\":\"10000\",\"groupNo\":\"GROUP9001\",\"groupName\":\"手工集团GROUP9001\"}");
        return app;
    }

    /** 存量集团申请上下文(数仓已收录,补录本次申请额度) */
    private CcrApplication existingGroupApp(String applyAmount) {
        CcrApplication app = groupApp();
        app.setGroupInfoJson("{\"applyAmount\":\"" + applyAmount + "\",\"groupNo\":\"GROUP001\",\"groupName\":\"集团001\"}");
        return app;
    }

    /** 手工集团:数仓无主数据/授信快照,回退 ccr_group 补录批复总额度;成员为手工成员(ccr_group_member) */
    private void stubManualGroup(String groupNo, BigDecimal approvedTotal, CcrGroupMember... manualMembers) {
        CcrGroup g = new CcrGroup();
        g.setGroupNo(groupNo);
        g.setGroupName("手工集团" + groupNo);
        g.setGroupType("INDUSTRY_GROUP");
        g.setGroupStatus("NORMAL");
        g.setApprovedTotalAmount(approvedTotal);
        lenient().when(dataWarehouseService.findGroup(groupNo)).thenReturn(null);
        lenient().when(dataWarehouseService.findGroupCredit(groupNo)).thenReturn(null);
        lenient().when(dataWarehouseService.groupMembers(groupNo)).thenReturn(new ArrayList<>());
        lenient().when(manualGroupService.findGroup(groupNo)).thenReturn(g);
        lenient().when(manualGroupService.listMembers(groupNo)).thenReturn(List.of(manualMembers));
    }

    private CcrGroupMember manualMember(String memberNo, LocalDate relationEnd) {
        CcrGroupMember m = new CcrGroupMember();
        m.setGroupNo("GROUP9001");
        m.setMemberCustomerNo(memberNo);
        m.setMemberName("手工公司" + memberNo);
        m.setMemberRole("GENERAL");
        m.setRelationEnd(relationEnd);
        return m;
    }

    // ---------- 集团额度勾稽:成员申请金额合计 ≤ 本次申请额度(§docs/19 §4.5) ----------

    @Test
    void submitBlocksWhenMemberRequestsExceedApplyAmount() {
        CcrApplication app = existingGroupApp("10000");
        CcrPricingItem item = loanItem(11L, "MEMBER_A");
        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        CcrApplicationMember m = member("MEMBER_A");
        m.setRequestAmount(new BigDecimal("12000")); // 12000 > 本次申请额度 10000
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(m));
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);
        // 数仓已收录集团(存量);批复授信 50000 仅展示参考,勾稽基准为本次申请额度 10000
        stubGroupDw(new BigDecimal("50000"), List.of(exclusiveLimit("MEMBER_A", "6000")));

        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(1L));
        assertEquals(ErrorCode.LIMIT_INCONSISTENT.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("超过本次申请额度"));
    }

    // ---------- 手工集团提交(数仓无主数据,回退 ccr_group 补录批复总额度) ----------

    @Test
    void manualGroupSubmitSucceedsAndCollectsGroupChain() {
        CcrApplication app = manualGroupApp();
        CcrPricingItem item = loanItem(11L, "MGROUP1");
        CcrGroupMember manualMember = manualMember("MGROUP1", null);
        stubManualGroup("GROUP9001", new BigDecimal("10000"), manualMember);
        lenient().when(manualGroupService.findGroupMember("GROUP9001", "MGROUP1")).thenReturn(manualMember);

        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(member("MGROUP1")));
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);
        when(contractRelMapper.selectList(any()))
                .thenReturn(List.of(new CcrPricingItemContractRel())) // 唯一性:本项关系
                .thenReturn(List.of())                                // 唯一性:无冲突
                .thenReturn(List.of(new CcrPricingItemContractRel())); // 快照回填
        when(ruleEngine.checkHardBoundary(anyString(), anyString(), any())).thenReturn(new BigDecimal("3.00"));

        CcrLprVersion lpr = new CcrLprVersion();
        lpr.setId(9601L);
        lpr.setVersionCode("LPR_V1");
        when(lprVersionMapper.selectOne(any())).thenReturn(lpr);

        when(snapshotGateway.createBundle(1L)).thenReturn(7001L);
        lenient().when(snapshotGateway.addRecord(eq(7001L), any())).thenReturn(8001L);
        when(snapshotGateway.validate(7001L)).thenReturn("PASS");
        SnapshotBundleResult bundle = new SnapshotBundleResult();
        bundle.setBundleId(7001L);
        bundle.setStatus("FROZEN");
        when(snapshotGateway.freeze(7001L)).thenReturn(bundle);

        RouteResult route = new RouteResult();
        route.setStartNodeCode("BRANCH_MANAGER");
        route.setFinalNodeCode("SIX_PEOPLE_GROUP");
        route.setRouteChain(List.of("BRANCH_MANAGER", "SIX_PEOPLE_GROUP"));
        route.setRateDirection("LOWER_BETTER");
        route.setBoundaryRate(new BigDecimal("3.20"));
        route.setMatchedMatrixNo("MX-001");
        route.setLprVersionId(9601L);
        when(rateMatrixRouter.calcRoute(any())).thenReturn(route);

        SubmitResponse response = service.submit(1L);

        assertTrue(response.getSubmitted());
        // 快照:建包→采集→校验→冻结
        verify(snapshotGateway).createBundle(1L);
        verify(snapshotGateway).validate(7001L);
        verify(snapshotGateway).freeze(7001L);
        verify(snapshotGateway, atLeastOnce()).addRecord(eq(7001L), any());
        // 手工集团回退路径:数仓无主数据,提交链路取 ccr_group 补录批复总额度
        // (groupExists/mergedApprovedTotal/addManualGroupRecord 各命中一次)
        verify(manualGroupService, atLeastOnce()).findGroup("GROUP9001");
        verify(manualGroupService, atLeastOnce()).listMembers("GROUP9001");
    }

    @Test
    void manualGroupSubmitBlocksWhenMemberRequestsExceedApprovedTotal() {
        CcrApplication app = manualGroupApp();
        CcrPricingItem item = loanItem(11L, "MGROUP1");
        stubManualGroup("GROUP9001", new BigDecimal("10000"),
                manualMember("MGROUP1", null), manualMember("MGROUP2", null));

        CcrApplicationMember m1 = member("MGROUP1");
        CcrApplicationMember m2 = member("MGROUP2");
        m2.setRequestAmount(new BigDecimal("20000")); // 1500+20000=21500 > 批复 10000

        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(m1, m2));
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);

        // 手工成员(无数仓额度)本次申请金额计入额度占用,超批复总额度阻断
        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(1L));
        assertEquals(ErrorCode.LIMIT_INCONSISTENT.getCode(), e.getCode());
    }

    @Test
    void submitBlocksWhenDwMemberRelationExpired() {
        CcrApplication app = existingGroupApp("10000");
        CcrPricingItem item = loanItem(11L, "MEMBER_A");
        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(member("MEMBER_A")));
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);
        // 数仓已收录集团(存量),但成员 relation_end 已过 → 不在团,成员存在性校验阻断(新增集团豁免)
        Map<String, Object> group = new HashMap<>();
        group.put("group_no", "GROUP001");
        group.put("group_status", "NORMAL");
        lenient().when(dataWarehouseService.findGroup("GROUP001")).thenReturn(group);
        Map<String, Object> memberRow = new HashMap<>();
        memberRow.put("member_customer_no", "MEMBER_A");
        memberRow.put("relation_end", LocalDate.now().minusDays(1).toString());
        lenient().when(dataWarehouseService.groupMembers("GROUP001")).thenReturn(List.of(memberRow));

        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(1L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("不在集团有效成员快照"));
    }

    // ---------- 新增集团:提交时落表(ccr_group/ccr_group_member,§docs/19 §4.6) ----------

    @Test
    void newGroupSubmitPersistsSupplementThenSucceeds() {
        CcrApplication app = manualGroupApp();
        // 补录成员随申请提交(数仓无该集团/成员,全部按手工补录)
        app.setGroupInfoJson("{\"applyAmount\":\"10000\",\"groupNo\":\"GROUP9001\",\"groupName\":\"新集团9001\","
                + "\"supplementMembers\":[{\"memberCustomerNo\":\"MGROUP1\",\"memberName\":\"手工公司MGROUP1\"}]}");
        CcrPricingItem item = loanItem(11L, "MGROUP1");
        when(dataWarehouseService.findGroup("GROUP9001")).thenReturn(null);
        when(dataWarehouseService.findGroupCredit("GROUP9001")).thenReturn(null);
        when(dataWarehouseService.groupMembers("GROUP9001")).thenReturn(new ArrayList<>());
        when(dataWarehouseService.findGroupMember("GROUP9001", "MGROUP1")).thenReturn(null);
        when(manualGroupService.findGroup("GROUP9001")).thenReturn(null);
        when(manualGroupService.listMembers("GROUP9001")).thenReturn(new ArrayList<>());

        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(member("MGROUP1")));
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);
        when(contractRelMapper.selectList(any()))
                .thenReturn(List.of(new CcrPricingItemContractRel()))
                .thenReturn(List.of())
                .thenReturn(List.of(new CcrPricingItemContractRel()));
        when(ruleEngine.checkHardBoundary(anyString(), anyString(), any())).thenReturn(new BigDecimal("3.00"));

        CcrLprVersion lpr = new CcrLprVersion();
        lpr.setId(9601L);
        lpr.setVersionCode("LPR_V1");
        when(lprVersionMapper.selectOne(any())).thenReturn(lpr);

        when(snapshotGateway.createBundle(1L)).thenReturn(7001L);
        lenient().when(snapshotGateway.addRecord(eq(7001L), any())).thenReturn(8001L);
        when(snapshotGateway.validate(7001L)).thenReturn("PASS");
        SnapshotBundleResult bundle = new SnapshotBundleResult();
        bundle.setBundleId(7001L);
        bundle.setStatus("FROZEN");
        when(snapshotGateway.freeze(7001L)).thenReturn(bundle);

        RouteResult route = new RouteResult();
        route.setStartNodeCode("BRANCH_MANAGER");
        route.setFinalNodeCode("SIX_PEOPLE_GROUP");
        route.setRouteChain(List.of("BRANCH_MANAGER", "SIX_PEOPLE_GROUP"));
        route.setRateDirection("LOWER_BETTER");
        route.setBoundaryRate(new BigDecimal("3.20"));
        route.setMatchedMatrixNo("MX-001");
        route.setLprVersionId(9601L);
        when(rateMatrixRouter.calcRoute(any())).thenReturn(route);

        SubmitResponse response = service.submit(1L);

        assertTrue(response.getSubmitted());
        // 提交时落表:新增集团 saveGroup + 补录成员 upsertMember(申请额度同步至 approved_total_amount 作展示参考)
        ArgumentCaptor<CcrGroup> groupCaptor = ArgumentCaptor.forClass(CcrGroup.class);
        verify(manualGroupService).saveGroup(groupCaptor.capture());
        assertEquals("GROUP9001", groupCaptor.getValue().getGroupNo());
        assertEquals("新集团9001", groupCaptor.getValue().getGroupName());
        assertEquals(new BigDecimal("10000"), groupCaptor.getValue().getApprovedTotalAmount());
        ArgumentCaptor<CcrGroupMember> memberCaptor = ArgumentCaptor.forClass(CcrGroupMember.class);
        verify(manualGroupService).upsertMember(memberCaptor.capture());
        assertEquals("MGROUP1", memberCaptor.getValue().getMemberCustomerNo());
        assertEquals("手工公司MGROUP1", memberCaptor.getValue().getMemberName());
    }

    // ---------- 存量集团补申请额度:数仓优先不落手工表(§4.1/§4.6) ----------

    @Test
    void existingGroupSubmitWithApplyAmountSucceedsAndSkipsManualTables() {
        CcrApplication app = existingGroupApp("3000");
        // 手工补录成员与数仓已有成员同号 → 数仓优先,不落手工表
        app.setGroupInfoJson("{\"applyAmount\":\"3000\",\"groupNo\":\"GROUP001\",\"groupName\":\"集团001\","
                + "\"supplementMembers\":[{\"memberCustomerNo\":\"MEMBER_A\",\"memberName\":\"A公司(数仓已有)\"}]}");
        CcrPricingItem item = loanItem(11L, "MEMBER_A");
        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(member("MEMBER_A"))); // 1500 ≤ 3000
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);
        when(contractRelMapper.selectList(any()))
                .thenReturn(List.of(new CcrPricingItemContractRel()))
                .thenReturn(List.of())
                .thenReturn(List.of(new CcrPricingItemContractRel()));
        when(ruleEngine.checkHardBoundary(anyString(), anyString(), any())).thenReturn(new BigDecimal("3.00"));
        stubGroupDw(new BigDecimal("50000"), List.of(exclusiveLimit("MEMBER_A", "6000")));
        // 数仓已收录集团与成员 → persistGroupSupplement 不落 ccr_group/ccr_group_member
        when(dataWarehouseService.findGroupMember("GROUP001", "MEMBER_A"))
                .thenReturn(Map.of("member_customer_no", "MEMBER_A"));

        CcrLprVersion lpr = new CcrLprVersion();
        lpr.setId(9601L);
        lpr.setVersionCode("LPR_V1");
        when(lprVersionMapper.selectOne(any())).thenReturn(lpr);

        when(snapshotGateway.createBundle(1L)).thenReturn(7001L);
        lenient().when(snapshotGateway.addRecord(eq(7001L), any())).thenReturn(8001L);
        when(snapshotGateway.validate(7001L)).thenReturn("PASS");
        SnapshotBundleResult bundle = new SnapshotBundleResult();
        bundle.setBundleId(7001L);
        bundle.setStatus("FROZEN");
        when(snapshotGateway.freeze(7001L)).thenReturn(bundle);

        RouteResult route = new RouteResult();
        route.setStartNodeCode("BRANCH_MANAGER");
        route.setFinalNodeCode("SIX_PEOPLE_GROUP");
        route.setRouteChain(List.of("BRANCH_MANAGER", "SIX_PEOPLE_GROUP"));
        route.setRateDirection("LOWER_BETTER");
        route.setBoundaryRate(new BigDecimal("3.20"));
        route.setMatchedMatrixNo("MX-001");
        route.setLprVersionId(9601L);
        when(rateMatrixRouter.calcRoute(any())).thenReturn(route);

        SubmitResponse response = service.submit(1L);

        assertTrue(response.getSubmitted());
        // 数仓优先:已收录集团与成员,不落手工表(撞数仓自动归存量,不拒绝不覆盖)
        verify(manualGroupService, never()).saveGroup(any());
        verify(manualGroupService, never()).upsertMember(any());
    }

    // ---------- 申请额度预检:未补录 BLOCK(§docs/19 §4.5) ----------

    @Test
    void submitCheckBlocksWhenApplyAmountMissing() {
        CcrApplication app = groupApp(); // 数仓已收录集团,但未补录本次申请额度
        stubGroupDw(new BigDecimal("50000"), List.of(exclusiveLimit("MEMBER_A", "6000")));
        CcrPricingItem item = loanItem(11L, "MEMBER_A");
        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(member("MEMBER_A")));
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);
        lenient().when(ruleEngine.checkHardBoundary(anyString(), anyString(), any())).thenReturn(new BigDecimal("3.00"));
        when(dataWarehouseService.latestDataDates(any())).thenReturn(new HashMap<>());
        lenient().when(dataWarehouseService.contribution(anyString())).thenReturn(new ArrayList<>());

        SubmitCheckResponse resp = service.submitCheck(1L);

        boolean blockApplyAmount = resp.getQualityPrecheck().stream()
                .anyMatch(p -> "GROUP_APPLY_AMOUNT".equals(p.getRuleCode()) && "BLOCK".equals(p.getLevel()));
        assertTrue(blockApplyAmount);
    }

    // ---------- 一合同一有效分项(跨申请阻断) ----------

    @Test
    void submitBlocksWhenContractOccupiedByNonTerminalItemAcrossApplications() {
        CcrApplication app = existingGroupApp("10000");
        CcrPricingItem item = loanItem(11L, "MEMBER_A");
        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(member("MEMBER_A")));
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);
        stubGroupDw(new BigDecimal("10000"), List.of(exclusiveLimit("MEMBER_A", "6000")));

        CcrPricingItemContractRel ownRel = new CcrPricingItemContractRel();
        ownRel.setApplicationId(1L);
        ownRel.setPricingItemId(11L);
        ownRel.setContractBusinessKey("CONTRACT_A001");
        CcrPricingItemContractRel conflictRel = new CcrPricingItemContractRel();
        conflictRel.setApplicationId(2L);
        conflictRel.setPricingItemId(99L);
        conflictRel.setContractBusinessKey("CONTRACT_A001");
        when(contractRelMapper.selectList(any()))
                .thenReturn(List.of(ownRel))       // 本项合同关系
                .thenReturn(List.of(conflictRel)); // 同合同他项关系
        CcrPricingItem other = loanItem(99L, "MEMBER_B");
        other.setStatus("ROUTING"); // 非终态
        when(pricingItemMapper.selectBatchIds(any())).thenReturn(List.of(other));

        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(1L));
        assertEquals(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), e.getCode());
    }

    // ---------- 硬边界阻断 ----------

    @Test
    void submitBlocksWhenHardBoundaryBreached() {
        CcrApplication app = existingGroupApp("10000");
        CcrPricingItem item = loanItem(11L, "MEMBER_A");
        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(member("MEMBER_A")));
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);
        stubGroupDw(new BigDecimal("10000"), List.of(exclusiveLimit("MEMBER_A", "6000")));
        when(contractRelMapper.selectList(any()))
                .thenReturn(List.of(new CcrPricingItemContractRel()))
                .thenReturn(List.of());
        when(ruleEngine.checkHardBoundary(anyString(), anyString(), any()))
                .thenThrow(new ServiceException(ErrorCode.HARD_BOUNDARY.getCode(), "突破业务硬边界"));

        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(1L));
        assertEquals(ErrorCode.HARD_BOUNDARY.getCode(), e.getCode());
    }

    // ---------- 提交成功:快照绑定 + 版本冻结 + 分项置 ROUTING/BRANCH_MANAGER ----------

    @Test
    void submitFreezesSnapshotAndVersionsThenRoutesItems() {
        CcrApplication app = new CcrApplication();
        app.setId(1L);
        app.setApplicationNo("CCR20260806ABCD");
        app.setBusinessType("LOAN");
        app.setCustomerScope("CORPORATE_SINGLE");
        app.setCustomerNo("CORP001");
        app.setApplicantUserId(1000L);
        app.setApplicantOrgId(1001L);
        app.setStatus("DRAFT");
        app.setVersionNo(1);
        CcrPricingItem item = loanItem(11L, null);
        item.setPricingCustomerNo("CORP001");

        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);
        when(contractRelMapper.selectList(any()))
                .thenReturn(List.of(new CcrPricingItemContractRel())) // 唯一性:本项关系
                .thenReturn(List.of())                                // 唯一性:无冲突
                .thenReturn(List.of(new CcrPricingItemContractRel())); // 快照回填
        when(ruleEngine.checkHardBoundary(anyString(), anyString(), any())).thenReturn(new BigDecimal("3.00"));

        CcrLprVersion lpr = new CcrLprVersion();
        lpr.setId(9601L);
        lpr.setVersionCode("LPR_V1");
        when(lprVersionMapper.selectOne(any())).thenReturn(lpr);

        when(snapshotGateway.createBundle(1L)).thenReturn(7001L);
        lenient().when(snapshotGateway.addRecord(eq(7001L), any())).thenReturn(8001L);
        when(snapshotGateway.validate(7001L)).thenReturn("PASS");
        SnapshotBundleResult bundle = new SnapshotBundleResult();
        bundle.setBundleId(7001L);
        bundle.setStatus("FROZEN");
        when(snapshotGateway.freeze(7001L)).thenReturn(bundle);

        RouteResult route = new RouteResult();
        route.setStartNodeCode("BRANCH_MANAGER");
        route.setFinalNodeCode("SIX_PEOPLE_GROUP");
        route.setRouteChain(List.of("BRANCH_MANAGER", "SIX_PEOPLE_GROUP"));
        route.setRateDirection("LOWER_BETTER");
        route.setBoundaryRate(new BigDecimal("3.20"));
        route.setMatchedMatrixNo("MX-001");
        route.setLprVersionId(9601L);
        when(rateMatrixRouter.calcRoute(any())).thenReturn(route);

        SubmitResponse response = service.submit(1L);

        // 快照:建包→校验→冻结
        verify(snapshotGateway).createBundle(1L);
        verify(snapshotGateway).validate(7001L);
        verify(snapshotGateway).freeze(7001L);

        // 分项:ROUTING + 首节点 BRANCH_MANAGER + 终审岗位 + 冻结边界/矩阵行号(§8.6)
        ArgumentCaptor<CcrPricingItem> itemCaptor = ArgumentCaptor.forClass(CcrPricingItem.class);
        verify(pricingItemMapper, atLeastOnce()).updateById(itemCaptor.capture());
        CcrPricingItem updated = itemCaptor.getAllValues().stream()
                .filter(i -> "ROUTING".equals(i.getStatus())).findFirst().orElseThrow();
        assertEquals("BRANCH_MANAGER", updated.getStartNodeCode());
        assertEquals("BRANCH_MANAGER", updated.getCurrentNodeCode());
        assertEquals("SIX_PEOPLE_GROUP", updated.getRouteCode());
        assertEquals(new BigDecimal("3.20"), updated.getBoundaryRate());
        assertEquals("MX-001", updated.getMatchedMatrixNo());

        // 中间态:主申请先置 SUBMITTED(§7.2 步骤6),再置 ROUTING
        ArgumentCaptor<LambdaUpdateWrapper<CcrApplication>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applicationMapper).update(isNull(), wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSet().contains("status"));
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue("SUBMITTED"));

        // 主单:ROUTING + 冻结 LPR 版本 + 快照包 + 提交时间
        ArgumentCaptor<CcrApplication> appCaptor = ArgumentCaptor.forClass(CcrApplication.class);
        verify(applicationMapper).updateById(appCaptor.capture());
        CcrApplication updatedApp = appCaptor.getValue();
        assertEquals("ROUTING", updatedApp.getStatus());
        assertEquals(9601L, updatedApp.getLprVersionId());
        assertEquals(7001L, updatedApp.getSnapshotBundleId());
        assertNotNull(updatedApp.getSubmitTime());
        assertNotNull(updatedApp.getRouteAsOfDate());

        assertTrue(response.getSubmitted());
        assertEquals(1, response.getItems().size());
        assertEquals(List.of("BRANCH_MANAGER", "SIX_PEOPLE_GROUP"), response.getItems().get(0).getRouteChain());

        // Outbox(§3.5/§7.2 步骤7):逐分项 FLOW_START(payload 含分项+起始节点+流程定义) + 申请人/支行行长 NOTIFY
        verify(outboxService).publish(eq("FLOW_START"), eq("PI-11"),
                argThat((String p) -> p.contains("BRANCH_MANAGER") && p.contains("rate_approval")
                        && p.contains("PI-11")));
        verify(outboxService).publish(eq("NOTIFY"), eq("SUBMIT:APP:1:APPLICANT"), anyString());
        verify(outboxService).publish(eq("NOTIFY"), eq("SUBMIT:APP:1:BRANCH_MANAGER"),
                argThat((String p) -> p.contains("BRANCH_MANAGER") && p.contains("\"orgId\":1001")
                        && !p.contains("\"recipientType\":\"ROLE\"")));
    }

    // ---------- 幂等:重复提交返回既有结果 ----------

    @Test
    void submitIsIdempotentForAlreadySubmittedApplication() {
        CcrApplication app = groupApp();
        app.setStatus("ROUTING");
        app.setSubmitTime(LocalDateTime.now().minusMinutes(5));
        app.setLprVersionId(9601L);
        app.setSnapshotBundleId(7001L);
        when(applicationMapper.selectById(1L)).thenReturn(app);
        CcrPricingItem item = loanItem(11L, "MEMBER_A");
        item.setStatus("ROUTING");
        item.setCurrentNodeCode("BRANCH_MANAGER");
        item.setRouteCode("SIX_PEOPLE_GROUP");
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));

        SubmitResponse response = service.submit(1L);

        assertEquals(Boolean.FALSE, response.getSubmitted());
        assertEquals(7001L, response.getSnapshotBundleId());
        assertEquals("BRANCH_MANAGER", response.getItems().get(0).getCurrentNodeCode());
    }

    // ---------- 关联重提:已批准分项沿用标记 ----------

    @Test
    void reapplyInheritsApprovedItemsAndReroutesRejected() {
        CcrApplication source = groupApp();
        source.setStatus("FINAL");
        // 关联重提跨申请带出补录信息(§4.5):新增集团重提免二次补录
        source.setGroupInfoJson("{\"applyAmount\":\"10000\",\"groupNo\":\"GROUP001\",\"groupName\":\"集团001\"}");
        when(applicationMapper.selectById(1L)).thenReturn(source);
        lenient().when(applicationMapper.insert(any(CcrApplication.class))).thenAnswer(inv -> {
            inv.getArgument(0, CcrApplication.class).setId(2L);
            return 1;
        });
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(member("MEMBER_A")));

        CcrPricingItem approved = loanItem(11L, "MEMBER_A");
        approved.setStatus("FINAL");
        approved.setFinalRate(new BigDecimal("3.50"));
        CcrPricingItem rejected = loanItem(12L, "MEMBER_A");
        rejected.setStatus("REJECTED");
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(approved, rejected));
        lenient().when(contractRelMapper.selectList(any())).thenReturn(List.of());
        lenient().when(depositRelMapper.selectList(any())).thenReturn(List.of());
        lenient().when(guaranteePackageMapper.selectById(any())).thenReturn(null);
        lenient().when(commitmentMapper.selectList(any())).thenReturn(List.of());

        CcrApplication target = service.reapply(1L);

        assertEquals("DRAFT", target.getStatus());
        assertEquals(1L, target.getSourceApplicationId());
        assertEquals("GROUP001", target.getGroupNo());
        // 跨申请带出:reapply 复制 groupInfoJson,新增集团重提无需二次补录
        assertEquals("{\"applyAmount\":\"10000\",\"groupNo\":\"GROUP001\",\"groupName\":\"集团001\"}",
            target.getGroupInfoJson());

        ArgumentCaptor<CcrPricingItem> itemCaptor = ArgumentCaptor.forClass(CcrPricingItem.class);
        verify(pricingItemMapper, org.mockito.Mockito.times(2)).insert(itemCaptor.capture());
        List<CcrPricingItem> copies = itemCaptor.getAllValues();
        CcrPricingItem inherited = copies.get(0);
        assertEquals("Y", inherited.getInheritFlag());
        assertEquals("FINAL", inherited.getStatus());
        assertEquals(11L, inherited.getSourcePricingItemId());
        assertEquals(new BigDecimal("3.50"), inherited.getFinalRate());
        assertEquals(new BigDecimal("3.50"), inherited.getRequestedRate());
        CcrPricingItem rerouted = copies.get(1);
        assertEquals("N", rerouted.getInheritFlag());
        assertEquals("DRAFT", rerouted.getStatus());
        assertEquals(12L, rerouted.getSourcePricingItemId());

        ArgumentCaptor<CcrApplicationRelation> relCaptor = ArgumentCaptor.forClass(CcrApplicationRelation.class);
        verify(applicationRelationMapper).insert(relCaptor.capture());
        assertEquals("REAPPLY", relCaptor.getValue().getRelationType());
        assertEquals("Y", relCaptor.getValue().getInheritFlag());
        assertEquals(11L, relCaptor.getValue().getSourcePricingItemId());
    }

    // ---------- 任务1:REJECTED(全否)可关联重提 + 原申请保持 REJECTED 终态(不置 RETURNED) ----------

    @Test
    void reapplyAllowsRejectedSourceKeepsSourceRejected() {
        CcrApplication source = groupApp();
        source.setStatus("REJECTED");
        when(applicationMapper.selectById(1L)).thenReturn(source);
        lenient().when(applicationMapper.insert(any(CcrApplication.class))).thenAnswer(inv -> {
            inv.getArgument(0, CcrApplication.class).setId(2L);
            return 1;
        });
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(member("MEMBER_A")));
        CcrPricingItem rejected = loanItem(12L, "MEMBER_A");
        rejected.setStatus("REJECTED");
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(rejected));
        lenient().when(contractRelMapper.selectList(any())).thenReturn(List.of());
        lenient().when(depositRelMapper.selectList(any())).thenReturn(List.of());
        lenient().when(guaranteePackageMapper.selectById(any())).thenReturn(null);
        lenient().when(commitmentMapper.selectList(any())).thenReturn(List.of());

        CcrApplication target = service.reapply(1L);

        assertEquals("DRAFT", target.getStatus());
        assertEquals(1L, target.getSourceApplicationId());
        // 原申请保持原终态 REJECTED 供溯源(不置 RETURNED)
        assertEquals("REJECTED", source.getStatus());
    }

    // ---------- 任务7:非信用类分项必须有担保组合且措施非空(§9.3-5) ----------

    @Test
    void submitBlocksNonCreditItemWithoutGuaranteeMeasures() {
        CcrApplication app = new CcrApplication();
        app.setId(1L);
        app.setApplicationNo("CCR20260806ABCD");
        app.setBusinessType("LOAN");
        app.setCustomerScope("CORPORATE_SINGLE");
        app.setCustomerNo("CORP001");
        app.setStatus("DRAFT");
        app.setVersionNo(1);
        CcrPricingItem item = loanItem(11L, null);
        item.setPricingCustomerNo("CORP001");
        item.setGuaranteePackageId(900L);
        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);
        CcrGuaranteePackage pkg = new CcrGuaranteePackage();
        pkg.setId(900L);
        pkg.setMainGuaranteeType("MORTGAGE");
        when(guaranteePackageMapper.selectById(900L)).thenReturn(pkg);
        when(guaranteeMeasureMapper.selectCount(any())).thenReturn(0L);

        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(1L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("担保措施"));
    }

    // ---------- 重提状态守卫 ----------

    @Test
    void reapplyRejectsNonTerminalSource() {
        CcrApplication source = groupApp();
        source.setStatus("ROUTING");
        when(applicationMapper.selectById(1L)).thenReturn(source);

        ServiceException e = assertThrows(ServiceException.class, () -> service.reapply(1L));
        assertEquals(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), e.getCode());
    }
}
