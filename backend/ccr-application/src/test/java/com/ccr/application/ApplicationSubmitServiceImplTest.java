package com.ccr.application;

import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrApplicationMember;
import com.ccr.application.domain.CcrApplicationRelation;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.domain.CcrPricingItemContractRel;
import com.ccr.application.dto.SnapshotBundleResult;
import com.ccr.application.dto.SubmitResponse;
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
import com.ccr.application.service.SnapshotGateway;
import com.ccr.application.service.impl.ApplicationSubmitServiceImpl;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrLprVersion;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.engine.RuleEngine;
import com.ccr.rule.mapper.CcrLprVersionMapper;
import com.ccr.rule.mapper.CcrRateRuleSetMapper;
import com.ccr.rule.service.RateMatrixRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
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

    @InjectMocks
    private ApplicationSubmitServiceImpl service;

    // ---------- 测试数据构造 ----------

    private CcrApplication groupApp() {
        CcrApplication app = new CcrApplication();
        app.setId(1L);
        app.setApplicationNo("CCR20260806ABCD");
        app.setBusinessType("LOAN");
        app.setCustomerScope("GROUP");
        app.setGroupNo("GROUP001");
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

    // ---------- 集团额度超总额阻断 ----------

    @Test
    void submitBlocksWhenMemberAllocatedExceedsGroupApprovedTotal() {
        CcrApplication app = groupApp();
        CcrPricingItem item = loanItem(11L, "MEMBER_A");
        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(item));
        when(applicationMemberMapper.selectList(any())).thenReturn(List.of(member("MEMBER_A")));
        lenient().when(contractRelMapper.selectCount(any())).thenReturn(1L);
        // EXCLUSIVE 6000+5000=11000 > 批复 10000
        stubGroupDw(new BigDecimal("10000"),
                List.of(exclusiveLimit("MEMBER_A", "6000"), exclusiveLimit("MEMBER_B", "5000")));

        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(1L));
        assertEquals(ErrorCode.LIMIT_INCONSISTENT.getCode(), e.getCode());
    }

    // ---------- 一合同一有效分项(跨申请阻断) ----------

    @Test
    void submitBlocksWhenContractOccupiedByNonTerminalItemAcrossApplications() {
        CcrApplication app = groupApp();
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
        CcrApplication app = groupApp();
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
        route.setLprVersionId(9601L);
        when(rateMatrixRouter.calcRoute(any())).thenReturn(route);

        SubmitResponse response = service.submit(1L);

        // 快照:建包→校验→冻结
        verify(snapshotGateway).createBundle(1L);
        verify(snapshotGateway).validate(7001L);
        verify(snapshotGateway).freeze(7001L);

        // 分项:ROUTING + 首节点 BRANCH_MANAGER + 终审岗位
        ArgumentCaptor<CcrPricingItem> itemCaptor = ArgumentCaptor.forClass(CcrPricingItem.class);
        verify(pricingItemMapper, atLeastOnce()).updateById(itemCaptor.capture());
        CcrPricingItem updated = itemCaptor.getAllValues().stream()
                .filter(i -> "ROUTING".equals(i.getStatus())).findFirst().orElseThrow();
        assertEquals("BRANCH_MANAGER", updated.getCurrentNodeCode());
        assertEquals("SIX_PEOPLE_GROUP", updated.getRouteCode());

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
