package com.ccr.resolution;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.ccr.resolution.service.impl.ResolutionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 决议与执行核验单元测试(§7.7 两级核验/回填校验/状态守卫/幂等/异常通知)
 */
@ExtendWith(MockitoExtension.class)
class ResolutionServiceImplTest {

    @Mock
    private CcrResolutionMapper resolutionMapper;
    @Mock
    private CcrResolutionExecutionMapper executionMapper;
    @Mock
    private CcrPricingItemReadMapper pricingItemReadMapper;
    @Mock
    private CcrGuaranteePackageReadMapper guaranteePackageReadMapper;
    @Mock
    private CcrApplicationReadMapper applicationReadMapper;
    @Mock
    private DwLoanNoteSnapshotMapper loanNoteSnapshotMapper;
    @Mock
    private CcrNotificationLogWriteMapper notificationLogMapper;

    @InjectMocks
    private ResolutionServiceImpl resolutionService;

    private CcrResolution resolution;
    private CcrResolutionExecution exec;
    private CcrPricingItem item;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 环境无 SqlSessionFactory,需手动初始化实体 TableInfo(Lambda 包装器列解析依赖)
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrResolution.class);
        TableInfoHelper.initTableInfo(assistant, CcrResolutionExecution.class);
        TableInfoHelper.initTableInfo(assistant, DwLoanNoteSnapshot.class);
        TableInfoHelper.initTableInfo(assistant, CcrNotificationLog.class);

        resolution = new CcrResolution();
        resolution.setId(1L);
        resolution.setResolutionNo("RES2026080600000001");
        resolution.setPricingItemId(10L);
        resolution.setFinalRate(new BigDecimal("3.850000"));
        resolution.setEffectiveFrom(LocalDate.of(2026, 8, 1));
        resolution.setEffectiveTo(LocalDate.of(2026, 12, 31));

        exec = new CcrResolutionExecution();
        exec.setId(100L);
        exec.setResolutionId(1L);
        exec.setLoanContractNo("LC2026001");
        exec.setExecutionRate(new BigDecimal("3.850000"));
        exec.setExecutionStatus("CONTRACT_BOUND");

        item = new CcrPricingItem();
        item.setId(10L);
        item.setApplicationId(30L);
        item.setPricingCustomerNo("C001");
        item.setProductCode("P001");
        item.setPricingAmount(new BigDecimal("1000.00"));
        item.setTermValue(12);
        item.setTermUnit("月");
        item.setGuaranteePackageId(20L);
    }

    private ContractBindDTO matchingBindDTO() {
        ContractBindDTO dto = new ContractBindDTO();
        dto.setLoanContractNo("LC2026001");
        dto.setExecutionRate(new BigDecimal("3.85"));
        dto.setCustomerNo("C001");
        dto.setProductCode("P001");
        dto.setContractAmount(new BigDecimal("1000"));
        dto.setTermValue(12);
        dto.setTermUnit("月");
        dto.setGuaranteeType("MORTGAGE");
        dto.setSignDate(LocalDate.of(2026, 8, 6));
        return dto;
    }

    private void stubResolutionAndExec(String executionStatus) {
        exec.setExecutionStatus(executionStatus);
        when(resolutionMapper.selectById(1L)).thenReturn(resolution);
        when(executionMapper.selectOne(any(Wrapper.class))).thenReturn(exec);
    }

    private void stubPricingItem() {
        when(pricingItemReadMapper.selectById(10L)).thenReturn(item);
        CcrGuaranteePackage pkg = new CcrGuaranteePackage();
        pkg.setId(20L);
        pkg.setMainGuaranteeType("MORTGAGE");
        when(guaranteePackageReadMapper.selectById(20L)).thenReturn(pkg);
    }

    @Test
    void executeCheck_pass_whenContractAndNotesConsistent() {
        stubResolutionAndExec("CONTRACT_BOUND");
        DwLoanNoteSnapshot note = new DwLoanNoteSnapshot();
        note.setLoanNoteNo("LN001");
        note.setContractNo("LC2026001");
        note.setNoteStatus("ACTIVE");
        note.setDataDt(LocalDate.of(2026, 8, 5));
        note.setExecutionRate(new BigDecimal("3.8500"));
        when(loanNoteSnapshotMapper.selectOne(any(Wrapper.class))).thenReturn(note);
        when(loanNoteSnapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of(note));

        CcrResolutionExecution result = resolutionService.executeCheck(1L);

        assertEquals("PASS", result.getReconcileResult());
        assertEquals("EXECUTED", result.getExecutionStatus());
        assertEquals("2026-08-05", result.getSourceBatchId());
        verify(executionMapper).update(any(), any(Wrapper.class));
        verify(notificationLogMapper, never()).insert(any(CcrNotificationLog.class));
    }

    @Test
    void executeCheck_warn_whenNoNoteSnapshot() {
        stubResolutionAndExec("CONTRACT_BOUND");
        when(loanNoteSnapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        CcrResolutionExecution result = resolutionService.executeCheck(1L);

        assertEquals("WARN", result.getReconcileResult());
        assertEquals("EXECUTED", result.getExecutionStatus());
        assertNotNull(result.getDifferenceJson());
        assertEquals("WARN", result.getDifferenceJson().get("level2_note_vs_contract"));
        verify(notificationLogMapper, never()).insert(any(CcrNotificationLog.class));
    }

    @Test
    void executeCheck_reconcileException_whenNoteRateMismatch() {
        stubResolutionAndExec("CONTRACT_BOUND");
        DwLoanNoteSnapshot note = new DwLoanNoteSnapshot();
        note.setLoanNoteNo("LN001");
        note.setContractNo("LC2026001");
        note.setNoteStatus("ACTIVE");
        note.setDataDt(LocalDate.of(2026, 8, 5));
        note.setExecutionRate(new BigDecimal("3.6000"));
        when(loanNoteSnapshotMapper.selectOne(any(Wrapper.class))).thenReturn(note);
        when(loanNoteSnapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of(note));
        when(pricingItemReadMapper.selectById(10L)).thenReturn(item);
        CcrApplication application = new CcrApplication();
        application.setId(30L);
        application.setApplicantUserId(99L);
        when(applicationReadMapper.selectById(30L)).thenReturn(application);
        when(notificationLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        CcrResolutionExecution result = resolutionService.executeCheck(1L);

        assertEquals("FAILED", result.getReconcileResult());
        assertEquals("RECONCILE_EXCEPTION", result.getExecutionStatus());
        assertEquals("FAIL", result.getDifferenceJson().get("level2_note_vs_contract"));
        verify(executionMapper).updateById(any(CcrResolutionExecution.class));
        // 客户经理 + 预留合同经办岗两条通知
        verify(notificationLogMapper, times(2)).insert(any(CcrNotificationLog.class));
    }

    @Test
    void executeCheck_notificationNotDuplicated_whenMessageKeyExists() {
        stubResolutionAndExec("CONTRACT_BOUND");
        DwLoanNoteSnapshot note = new DwLoanNoteSnapshot();
        note.setLoanNoteNo("LN001");
        note.setNoteStatus("ACTIVE");
        note.setDataDt(LocalDate.of(2026, 8, 5));
        note.setExecutionRate(new BigDecimal("3.6000"));
        when(loanNoteSnapshotMapper.selectOne(any(Wrapper.class))).thenReturn(note);
        when(loanNoteSnapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of(note));
        when(pricingItemReadMapper.selectById(10L)).thenReturn(item);
        CcrApplication application = new CcrApplication();
        application.setId(30L);
        application.setApplicantUserId(99L);
        when(applicationReadMapper.selectById(30L)).thenReturn(application);
        when(notificationLogMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        resolutionService.executeCheck(1L);

        verify(notificationLogMapper, never()).insert(any(CcrNotificationLog.class));
    }

    @Test
    void bindContract_success_whenAllFieldsConsistent() {
        stubResolutionAndExec("CONTRACT_PENDING");
        stubPricingItem();

        CcrResolutionExecution result = resolutionService.bindContract(1L, matchingBindDTO());

        assertEquals("CONTRACT_BOUND", result.getExecutionStatus());
        assertEquals("LC2026001", result.getLoanContractNo());
        verify(executionMapper).updateById(any(CcrResolutionExecution.class));
    }

    @Test
    void bindContract_idempotent_whenSameContractAndRate() {
        stubResolutionAndExec("CONTRACT_BOUND");

        CcrResolutionExecution result = resolutionService.bindContract(1L, matchingBindDTO());

        assertSame(exec, result);
        verify(executionMapper, never()).updateById(any(CcrResolutionExecution.class));
    }

    @Test
    void bindContract_reject_whenExecutedOrClosed() {
        stubResolutionAndExec("EXECUTED");

        ServiceException e = assertThrows(ServiceException.class,
                () -> resolutionService.bindContract(1L, matchingBindDTO()));
        assertEquals(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), e.getCode());
        verify(executionMapper, never()).updateById(any(CcrResolutionExecution.class));
    }

    @Test
    void bindContract_reject_whenFieldMismatch() {
        stubResolutionAndExec("CONTRACT_PENDING");
        stubPricingItem();
        ContractBindDTO dto = matchingBindDTO();
        dto.setCustomerNo("C999");

        ServiceException e = assertThrows(ServiceException.class,
                () -> resolutionService.bindContract(1L, dto));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("客户不一致"));
        verify(executionMapper, never()).updateById(any(CcrResolutionExecution.class));
    }

    @Test
    void bindContract_reject_whenSignDateOutOfEffectiveRange() {
        stubResolutionAndExec("CONTRACT_PENDING");
        stubPricingItem();
        ContractBindDTO dto = matchingBindDTO();
        dto.setSignDate(LocalDate.of(2027, 1, 1));

        ServiceException e = assertThrows(ServiceException.class,
                () -> resolutionService.bindContract(1L, dto));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("决议失效日"));
        verify(executionMapper, never()).updateById(any(CcrResolutionExecution.class));
    }

    @Test
    void bindContract_requireSupplementAgreement_whenOriginalRateExists() {
        stubResolutionAndExec("RECONCILE_EXCEPTION");
        item.setOriginalRate(new BigDecimal("4.100000"));
        when(pricingItemReadMapper.selectById(10L)).thenReturn(item);
        ContractBindDTO dto = matchingBindDTO();
        dto.setSupplementAgreementNo(null);

        ServiceException e = assertThrows(ServiceException.class,
                () -> resolutionService.bindContract(1L, dto));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("补充协议编号"));
        verify(executionMapper, never()).updateById(any(CcrResolutionExecution.class));
    }

    @Test
    void bindContract_saveSupplementAgreement_whenRebindAfterException() {
        stubResolutionAndExec("RECONCILE_EXCEPTION");
        item.setOriginalRate(new BigDecimal("4.100000"));
        stubPricingItem();
        ContractBindDTO dto = matchingBindDTO();
        dto.setSupplementAgreementNo("SA2026001");

        CcrResolutionExecution result = resolutionService.bindContract(1L, dto);

        assertEquals("SA2026001", result.getSupplementAgreementNo());
        assertEquals("CONTRACT_BOUND", result.getExecutionStatus());
        verify(executionMapper).updateById(any(CcrResolutionExecution.class));
    }
}
