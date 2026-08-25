package com.ccr.application;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrApplicationCommitment;
import com.ccr.application.domain.CcrApplicationMember;
import com.ccr.application.domain.CcrGuaranteeMeasure;
import com.ccr.application.domain.CcrGuaranteePackage;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.domain.CcrPricingItemDepositRel;
import com.ccr.application.dto.CommitmentInput;
import com.ccr.application.dto.DepositItemInput;
import com.ccr.application.dto.MemberInput;
import com.ccr.common.enums.ErrorCode;
import com.ccr.application.mapper.CcrApplicationCommitmentMapper;
import com.ccr.application.mapper.CcrApplicationCreditSummaryMapper;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrApplicationMemberMapper;
import com.ccr.application.mapper.CcrGuaranteeMeasureMapper;
import com.ccr.application.mapper.CcrGuaranteePackageMapper;
import com.ccr.application.mapper.CcrPricingItemContractRelMapper;
import com.ccr.application.mapper.CcrPricingItemDepositRelMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.application.service.DataWarehouseService;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.application.service.impl.CcrApplicationServiceImpl;
import com.ccr.common.exception.ServiceException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 申请域服务单元测试(createDraft 修复:存款分项生成/担保组合/集团守卫;saveDraft 乐观锁)
 */
@ExtendWith(MockitoExtension.class)
class CcrApplicationServiceImplTest {

    @Mock
    private CcrApplicationCreditSummaryMapper creditSummaryMapper;
    @Mock
    private CcrApplicationMapper applicationMapper;
    @Mock
    private CcrApplicationMemberMapper applicationMemberMapper;
    @Mock
    private CcrPricingItemMapper pricingItemMapper;
    @Mock
    private CcrPricingItemContractRelMapper contractRelMapper;
    @Mock
    private CcrPricingItemDepositRelMapper depositRelMapper;
    @Mock
    private CcrGuaranteePackageMapper guaranteePackageMapper;
    @Mock
    private CcrGuaranteeMeasureMapper guaranteeMeasureMapper;
    @Mock
    private CcrApplicationCommitmentMapper commitmentMapper;
    @Mock
    private DataWarehouseService dataWarehouseService;
    @Mock
    private ApplicationAccessService applicationAccessService;
    @Mock
    private com.ccr.application.mapper.CcrApplicationOtherLoanMapper otherLoanMapper;
    @Mock
    private com.ccr.application.mapper.CcrApplicationRelatedPersonMapper relatedPersonMapper;
    @Mock
    private com.ccr.application.support.AppLoginUser currentLoginUser;
    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @InjectMocks
    private CcrApplicationServiceImpl service;

    /** 纯 Mockito 单测无 MyBatis 启动过程,需手工初始化实体 TableInfo(Lambda 包装器列解析依赖) */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrApplication.class);
        TableInfoHelper.initTableInfo(assistant, CcrPricingItem.class);
    }

    /** creditSummary 授信汇总(近期合入):单测补 mock,避免 create/saveDraft 路径 NPE */
    @BeforeEach
    void stubCreditSummary() {
        lenient().when(creditSummaryMapper.selectList(any())).thenReturn(List.of());
    }

    private void stubInsertIds() {
        // createDraft 现在从登录上下文取申请人(§5.4);lenient 兼容仅 createDraft 用例使用
        lenient().when(currentLoginUser.requireCurrentUser()).thenReturn(loginUser(7L, "customer_manager", 1001L));
        lenient().when(applicationMapper.insert(any(CcrApplication.class))).thenAnswer(inv -> {
            inv.getArgument(0, CcrApplication.class).setId(100L);
            return 1;
        });
        long[] seq = {1000L};
        lenient().when(pricingItemMapper.insert(any(CcrPricingItem.class))).thenAnswer(inv -> {
            inv.getArgument(0, CcrPricingItem.class).setId(++seq[0]);
            return 1;
        });
        lenient().when(guaranteePackageMapper.insert(any(CcrGuaranteePackage.class))).thenAnswer(inv -> {
            inv.getArgument(0, CcrGuaranteePackage.class).setId(5000L);
            return 1;
        });
        lenient().when(dataWarehouseService.latestDataDates(any())).thenReturn(Map.of());
    }

    // ---------- 存款分项生成(修复"存款申请 0 分项") ----------

    @Test
    void createDraftDepositGeneratesItemsFromStructuredDepositFields() {
        stubInsertIds();
        CcrApplication request = new CcrApplication();
        request.setBusinessType("DEPOSIT");
        request.setCustomerScope("CORPORATE_SINGLE");
        request.setCustomerNo("CORP001");
        request.setApplicantUserId(999L);
        request.setApplicantOrgId(999L);
        request.setOrgId(999L);
        request.setSourceApplicationId(999L);

        DepositItemInput deposit = new DepositItemInput();
        deposit.setProductCode("CORP_TIME_DEPOSIT");
        deposit.setTermValue(1);
        deposit.setTermUnit("YEAR");
        deposit.setAmount(new BigDecimal("800"));
        deposit.setRequestedRate(new BigDecimal("1.85"));
        deposit.setCalculatedRate(new BigDecimal("1.75"));
        deposit.setDepositAccountNo("ACCT001");
        request.setDepositItems(List.of(deposit));

        CcrApplication created = service.createDraft(request);

        assertEquals(7L, created.getApplicantUserId());
        assertEquals(1001L, created.getApplicantOrgId());
        assertEquals(null, created.getOrgId());
        assertEquals(null, created.getSourceApplicationId());

        ArgumentCaptor<CcrPricingItem> itemCaptor = ArgumentCaptor.forClass(CcrPricingItem.class);
        verify(pricingItemMapper).insert(itemCaptor.capture());
        CcrPricingItem item = itemCaptor.getValue();
        assertEquals("DEPOSIT_ACCOUNT", item.getPricingCarrierType());
        assertEquals("HIGHER_BETTER", item.getRateDirection());
        assertEquals("CORP_TIME_DEPOSIT", item.getProductCode());
        assertEquals(1, item.getTermValue());
        assertEquals("YEAR", item.getTermUnit());
        assertEquals(new BigDecimal("800"), item.getPricingAmount());
        assertEquals(new BigDecimal("1.85"), item.getRequestedRate());
        assertEquals("CORP001", item.getPricingCustomerNo());

        ArgumentCaptor<CcrPricingItemDepositRel> relCaptor = ArgumentCaptor.forClass(CcrPricingItemDepositRel.class);
        verify(depositRelMapper).insert(relCaptor.capture());
        CcrPricingItemDepositRel rel = relCaptor.getValue();
        assertEquals("ACCT001", rel.getDepositAccountNo());
        assertEquals("N", rel.getPlannedAccountFlag());
    }

    // ---------- 贷款分项:产品码/期限从请求取值 + 担保组合 ----------

    @Test
    void createDraftLoanUsesRequestProductAndCreatesGuaranteePackage() {
        stubInsertIds();
        CcrApplication request = new CcrApplication();
        request.setBusinessType("LOAN");
        request.setCustomerScope("CORPORATE_SINGLE");
        request.setCustomerNo("CORP001");

        Map<String, Object> measure = new HashMap<>();
        measure.put("measureType", "MORTGAGE");
        measure.put("collateralNo", "COL001");
        measure.put("guaranteeAmount", new BigDecimal("2000"));
        Map<String, Object> g = new HashMap<>();
        g.put("requestedRate", new BigDecimal("3.60"));
        g.put("calculatedRate", new BigDecimal("3.55"));
        g.put("amount", new BigDecimal("2000"));
        g.put("productCode", "LOAN_GENERAL");
        g.put("termValue", 24);
        g.put("termUnit", "MONTH");
        g.put("contractBusinessKey", "CONTRACT_A001");
        g.put("guaranteeType", "MORTGAGE");
        g.put("measures", List.of(measure));
        request.setGuarantees(List.of(g));

        service.createDraft(request);

        ArgumentCaptor<CcrPricingItem> itemCaptor = ArgumentCaptor.forClass(CcrPricingItem.class);
        verify(pricingItemMapper).insert(itemCaptor.capture());
        CcrPricingItem item = itemCaptor.getValue();
        assertEquals("LOAN_CONTRACT", item.getPricingCarrierType());
        assertEquals("LOWER_BETTER", item.getRateDirection());
        assertEquals("LOAN_GENERAL", item.getProductCode());
        assertEquals(24, item.getTermValue());
        assertEquals("MONTH", item.getTermUnit());

        ArgumentCaptor<CcrGuaranteePackage> pkgCaptor = ArgumentCaptor.forClass(CcrGuaranteePackage.class);
        verify(guaranteePackageMapper).insert(pkgCaptor.capture());
        assertEquals("MORTGAGE", pkgCaptor.getValue().getMainGuaranteeType());
        assertNotNull(pkgCaptor.getValue().getPricingItemId());

        ArgumentCaptor<CcrGuaranteeMeasure> measureCaptor = ArgumentCaptor.forClass(CcrGuaranteeMeasure.class);
        verify(guaranteeMeasureMapper).insert(measureCaptor.capture());
        assertEquals("MORTGAGE", measureCaptor.getValue().getMeasureType());
        assertEquals("COL001", measureCaptor.getValue().getCollateralNo());
    }

    // ---------- customerScope 守卫 ----------

    @Test
    void createDraftRejectsMembersForNonGroupScope() {
        CcrApplication request = new CcrApplication();
        request.setBusinessType("LOAN");
        request.setCustomerScope("CORPORATE_SINGLE");
        request.setCustomerNo("CORP001");
        MemberInput member = new MemberInput();
        member.setMemberCustomerNo("MEMBER_A");
        member.setRequestAmount(new BigDecimal("100"));
        request.setMembers(List.of(member));

        ServiceException e = assertThrows(ServiceException.class, () -> service.createDraft(request));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    @Test
    void createDraftRejectsGroupScopeWithoutGroupNo() {
        CcrApplication request = new CcrApplication();
        request.setBusinessType("LOAN");
        request.setCustomerScope("GROUP");

        ServiceException e = assertThrows(ServiceException.class, () -> service.createDraft(request));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    // ---------- 集团成员真实值 + 额度回填 ----------

    @Test
    void createDraftGroupBackfillsMemberLimitFromDataWarehouse() {
        stubInsertIds();
        Map<String, Object> credit = new HashMap<>();
        credit.put("group_credit_no", "GCREDIT001");
        credit.put("approved_total_amount", new BigDecimal("10000"));
        when(dataWarehouseService.findGroupCredit("GROUP001")).thenReturn(credit);
        Map<String, Object> limit = new HashMap<>();
        limit.put("member_limit_no", "MLIMIT001");
        limit.put("allocated_amount", new BigDecimal("6000"));
        when(dataWarehouseService.findMemberLimit("GCREDIT001", "MEMBER_A")).thenReturn(limit);

        CcrApplication request = new CcrApplication();
        request.setBusinessType("LOAN");
        request.setCustomerScope("GROUP");
        request.setGroupNo("GROUP001");
        MemberInput member = new MemberInput();
        member.setMemberCustomerNo("MEMBER_A");
        member.setRequestAmount(new BigDecimal("1500"));
        member.setCurrency("CNY");
        member.setMemberRole("CORE");
        request.setMembers(List.of(member));

        service.createDraft(request);

        ArgumentCaptor<CcrApplicationMember> memberCaptor = ArgumentCaptor.forClass(CcrApplicationMember.class);
        verify(applicationMemberMapper).insert(memberCaptor.capture());
        CcrApplicationMember saved = memberCaptor.getValue();
        assertEquals("MEMBER_A", saved.getMemberCustomerNo());
        assertEquals(new BigDecimal("1500"), saved.getRequestAmount());
        assertEquals("CNY", saved.getCurrency());
        assertEquals("CORE", saved.getMemberRole());
        assertEquals("MLIMIT001", saved.getMemberLimitRef());
        assertEquals(new BigDecimal("6000"), saved.getMemberLimitAmount());
    }

    // ---------- 乐观锁 ----------

    @Test
    void saveDraftRejectsStaleVersion() {
        CcrApplication exist = new CcrApplication();
        exist.setId(100L);
        exist.setStatus("DRAFT");
        exist.setVersionNo(3);
        when(applicationMapper.selectById(100L)).thenReturn(exist);

        CcrApplication request = new CcrApplication();
        request.setVersionNo(2);

        ServiceException e = assertThrows(ServiceException.class, () -> service.saveDraft(100L, request));
        assertEquals(ErrorCode.DATA_VERSION_CONFLICT.getCode(), e.getCode());
    }

    @Test
    void saveDraftRejectsMissingVersion() {
        CcrApplication exist = new CcrApplication();
        exist.setId(100L);
        exist.setStatus("DRAFT");
        exist.setVersionNo(3);
        when(applicationMapper.selectById(100L)).thenReturn(exist);

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.saveDraft(100L, new CcrApplication()));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    @Test
    void saveDraftRejectsConcurrentUpdate() {
        CcrApplication exist = new CcrApplication();
        exist.setId(100L);
        exist.setStatus("DRAFT");
        exist.setVersionNo(3);
        when(applicationMapper.selectById(100L)).thenReturn(exist);
        when(applicationMapper.updateById(any(CcrApplication.class))).thenReturn(0);

        CcrApplication request = new CcrApplication();
        request.setVersionNo(3);

        ServiceException e = assertThrows(ServiceException.class, () -> service.saveDraft(100L, request));
        assertEquals(ErrorCode.DATA_VERSION_CONFLICT.getCode(), e.getCode());
    }

    // ---------- saveDraft 子表重建(修改分项后保存草稿,提交读取到的即最新内容) ----------

    @Test
    void saveDraftRebuildsChildrenFromRequestSoSubmitSeesLatest() {
        stubInsertIds();
        CcrApplication exist = new CcrApplication();
        exist.setId(100L);
        exist.setStatus("DRAFT");
        exist.setVersionNo(3);
        exist.setBusinessType("DEPOSIT");
        exist.setCustomerScope("CORPORATE_SINGLE");
        exist.setCustomerNo("CORP001");
        exist.setApplicantUserId(7L);
        exist.setApplicantOrgId(1001L);
        when(applicationMapper.selectById(100L)).thenReturn(exist);
        when(applicationMapper.updateById(any(CcrApplication.class))).thenReturn(1);

        // 修改后的分项:申请利率从 1.85 调整为 1.95
        DepositItemInput deposit = new DepositItemInput();
        deposit.setProductCode("CORP_TIME_DEPOSIT");
        deposit.setTermValue(1);
        deposit.setTermUnit("YEAR");
        deposit.setAmount(new BigDecimal("800"));
        deposit.setRequestedRate(new BigDecimal("1.95"));
        deposit.setCalculatedRate(new BigDecimal("1.80"));
        deposit.setDepositAccountNo("ACCT001");
        CommitmentInput commitment = new CommitmentInput();
        commitment.setMetricCode("DEPOSIT_BALANCE");
        commitment.setTargetType("TARGET_BALANCE");
        commitment.setTargetValue(new BigDecimal("500"));
        CcrApplication request = new CcrApplication();
        request.setVersionNo(3);
        request.setApplicantUserId(9999L);
        request.setApplicantOrgId(9999L);
        request.setDepositItems(List.of(deposit));
        request.setCommitments(List.of(commitment));

        service.saveDraft(100L, request);

        // 前端即使伪造申请人/机构字段，服务端也保持原归属不变。
        assertEquals(7L, exist.getApplicantUserId());
        assertEquals(1001L, exist.getApplicantOrgId());

        // 旧子表(分项/承诺/成员/账户关系)按申请全量删除(成员改为物理删除)
        verify(pricingItemMapper).delete(any());
        verify(commitmentMapper).delete(any());
        verify(applicationMemberMapper).deletePhysical(any());
        verify(depositRelMapper).deletePhysical(any(), any());

        // 修改后的分项按请求体重新插入,提交时读取到的即为最新利率
        ArgumentCaptor<CcrPricingItem> itemCaptor = ArgumentCaptor.forClass(CcrPricingItem.class);
        verify(pricingItemMapper).insert(itemCaptor.capture());
        CcrPricingItem item = itemCaptor.getValue();
        assertEquals(100L, item.getApplicationId());
        assertEquals("DEPOSIT_ACCOUNT", item.getPricingCarrierType());
        assertEquals("CORP_TIME_DEPOSIT", item.getProductCode());
        assertEquals(new BigDecimal("1.95"), item.getRequestedRate());
        assertEquals(new BigDecimal("1.95"), item.getCurrentApprovalRate());
        assertEquals(new BigDecimal("800"), item.getPricingAmount());
        assertNotNull(item.getPricingItemNo());

        ArgumentCaptor<CcrPricingItemDepositRel> relCaptor = ArgumentCaptor.forClass(CcrPricingItemDepositRel.class);
        verify(depositRelMapper).insert(relCaptor.capture());
        CcrPricingItemDepositRel rel = relCaptor.getValue();
        assertEquals(item.getId(), rel.getPricingItemId());
        assertEquals("ACCT001", rel.getDepositAccountNo());

        ArgumentCaptor<CcrApplicationCommitment> commitmentCaptor =
                ArgumentCaptor.forClass(CcrApplicationCommitment.class);
        verify(commitmentMapper).insert(commitmentCaptor.capture());
        CcrApplicationCommitment saved = commitmentCaptor.getValue();
        assertEquals(100L, saved.getApplicationId());
        assertEquals("DEPOSIT_BALANCE", saved.getMetricCode());
        assertEquals(new BigDecimal("500"), saved.getTargetValue());
    }

    @Test
    void saveDraftDeletesOldGuaranteePackageAndMeasures() {
        CcrApplication exist = new CcrApplication();
        exist.setId(100L);
        exist.setStatus("DRAFT");
        exist.setVersionNo(1);
        exist.setBusinessType("LOAN");
        exist.setCustomerScope("CORPORATE_SINGLE");
        exist.setCustomerNo("CORP001");
        when(applicationMapper.selectById(100L)).thenReturn(exist);
        when(applicationMapper.updateById(any(CcrApplication.class))).thenReturn(1);
        // 旧分项及其担保组合
        CcrPricingItem oldItem = new CcrPricingItem();
        oldItem.setId(900L);
        oldItem.setApplicationId(100L);
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(oldItem));
        CcrGuaranteePackage oldPkg = new CcrGuaranteePackage();
        oldPkg.setId(5000L);
        oldPkg.setPricingItemId(900L);
        when(guaranteePackageMapper.selectList(any())).thenReturn(List.of(oldPkg));

        // 请求体清空分项:重建后不应残留旧担保组合/担保措施
        CcrApplication request = new CcrApplication();
        request.setVersionNo(1);

        service.saveDraft(100L, request);

        verify(guaranteeMeasureMapper).delete(any());
        verify(guaranteePackageMapper).delete(any());
        verify(pricingItemMapper).delete(any());
    }

    // ---------- 任务2:saveDraft 保留 inherit_flag='Y' 的沿用分项(D18b) ----------

    @Test
    void saveDraftPreservesInheritedItems() {
        CcrApplication exist = new CcrApplication();
        exist.setId(100L);
        exist.setStatus("DRAFT");
        exist.setVersionNo(1);
        exist.setBusinessType("LOAN");
        exist.setCustomerScope("CORPORATE_SINGLE");
        exist.setCustomerNo("CORP001");
        when(applicationMapper.selectById(100L)).thenReturn(exist);
        when(applicationMapper.updateById(any(CcrApplication.class))).thenReturn(1);
        // 重提带来的沿用分项:不在前端编辑载荷中,保存草稿不得删除
        CcrPricingItem inherited = new CcrPricingItem();
        inherited.setId(900L);
        inherited.setApplicationId(100L);
        inherited.setInheritFlag("Y");
        when(pricingItemMapper.selectList(any())).thenReturn(List.of(inherited));

        CcrApplication request = new CcrApplication();
        request.setVersionNo(1);

        service.saveDraft(100L, request);

        // 删除分项的条件必须排除 inherit_flag='Y'
        ArgumentCaptor<LambdaQueryWrapper<CcrPricingItem>> deleteCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(pricingItemMapper).delete(deleteCaptor.capture());
        assertTrue(deleteCaptor.getValue().getSqlSegment().contains("inherit_flag"),
                "删除分项必须排除沿用分项:" + deleteCaptor.getValue().getSqlSegment());
        assertTrue(deleteCaptor.getValue().getParamNameValuePairs().containsValue("Y"));
        // 沿用分项为唯一旧分项:无待删分项,不触碰担保组合/措施,也不新增分项
        verify(guaranteePackageMapper, never()).selectList(any());
        verify(guaranteePackageMapper, never()).delete(any());
        verify(pricingItemMapper, never()).insert(any(CcrPricingItem.class));
    }

    // ---------- 任务4:列表数据权限(§5.4,申请人/机构取服务端登录人) ----------

    private com.ccr.application.read.SysUserRead loginUser(Long id, String role, Long orgId) {
        com.ccr.application.read.SysUserRead user = new com.ccr.application.read.SysUserRead();
        user.setId(id);
        user.setRoleCode(role);
        user.setOrgId(orgId);
        return user;
    }

    @Test
    void listApplicationsCustomerManagerSeesOnlyOwn() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(loginUser(7L, "customer_manager", 1001L));

        service.listApplications(null);

        ArgumentCaptor<LambdaQueryWrapper<CcrApplication>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(applicationMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("applicant_user_id"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(7L));
    }

    @Test
    void listApplicationsBranchManagerFiltersByBranchCode() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(loginUser(8L, "branch_manager", 1001L));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(1001L)))
                .thenReturn(List.of("3202233050"));

        service.listApplications("ROUTING");

        ArgumentCaptor<LambdaQueryWrapper<CcrApplication>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(applicationMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("apply_branch_code"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("3202233050"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("ROUTING"));
        assertFalse(captor.getValue().getSqlSegment().contains("applicant_user_id"));
    }

    @Test
    void listApplicationsPresidentSeesAll() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(loginUser(9L, "president", 1L));

        service.listApplications(null);

        ArgumentCaptor<LambdaQueryWrapper<CcrApplication>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(applicationMapper).selectList(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().contains("applicant_user_id"));
        assertFalse(captor.getValue().getSqlSegment().contains("org_id"));
    }
}
