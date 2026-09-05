package com.ccr.application;

import com.ccr.application.dto.CreditResolutionLookupResponse;
import com.ccr.application.integration.CreditResolutionGateway;
import com.ccr.application.integration.CreditResolutionProperties;
import com.ccr.application.mapper.CcrApplicationAttachmentMapper;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.read.SysUserRead;
import com.ccr.application.support.AppLoginUser;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.application.service.ExternalCreditResolutionService;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalCreditResolutionServiceTest {

    @Mock
    private CreditResolutionGateway gateway;
    @Mock
    private ApplicationAccessService applicationAccessService;
    @Mock
    private AppLoginUser appLoginUser;
    @Mock
    private CcrApplicationMapper applicationMapper;
    @Mock
    private CcrApplicationAttachmentMapper attachmentMapper;
    @Mock
    private TransactionTemplate transactionTemplate;

    private ExternalCreditResolutionService service;

    @Test
    void preview_deniedOwner_neverCallsGateway() {
        org.mockito.Mockito.doThrow(new ServiceException(403, "无权维护该申请"))
                .when(applicationAccessService).requireDraftOwner(91L);
        assertThrows(ServiceException.class, () -> service.preview(91L, "R1", "F1"));
        org.mockito.Mockito.verifyNoInteractions(gateway, attachmentMapper);
    }

    @Test
    void preview_checksResolutionAndFileBeforeDownload_withoutImporting() {
        when(gateway.isEnabled()).thenReturn(true);
        var app = new com.ccr.application.domain.CcrApplication();
        app.setCustomerScope("CORPORATE_SINGLE");
        app.setCustomerNo("C001");
        when(applicationMapper.selectById(91L)).thenReturn(app);
        SysUserRead user = new SysUserRead();
        user.setUsername("100001");
        when(appLoginUser.requireCurrentUser()).thenReturn(user);
        var resolution = new com.ccr.application.integration.ExternalCreditResolution();
        resolution.setResolutionId("R1");
        var file = new com.ccr.application.integration.ExternalResolutionFile();
        file.setFileId("F1");
        resolution.setFiles(java.util.List.of(file));
        when(gateway.latest("100001", 2, "C001")).thenReturn(java.util.Optional.of(resolution));
        assertEquals("授信决议已更新，请重新查询后预览", assertThrows(ServiceException.class,
                () -> service.preview(91L, "OLD", "F1")).getMessage());
        assertEquals("文件不属于当前授信决议", assertThrows(ServiceException.class,
                () -> service.preview(91L, "R1", "OTHER")).getMessage());
        verify(gateway, never()).download(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var downloaded = new com.ccr.application.integration.DownloadedResolutionFile("F1", "决议.pdf", "application/pdf", new byte[]{1});
        when(gateway.download("100001", "R1", file)).thenReturn(downloaded);
        assertEquals(downloaded, service.preview(91L, "R1", "F1"));
        org.mockito.Mockito.verifyNoInteractions(attachmentMapper, transactionTemplate);
    }

    @BeforeEach
    void setUp() {
        service = new ExternalCreditResolutionService(gateway, new CreditResolutionProperties(),
                applicationAccessService, appLoginUser, applicationMapper, attachmentMapper, transactionTemplate);
    }

    @Test
    void lookup_integrationDisabled_returnsNormalStateWithoutCallingGateway() {
        when(gateway.isEnabled()).thenReturn(false);

        CreditResolutionLookupResponse result = service.lookup("CORPORATE_SINGLE", "C001", null);

        assertFalse(result.isEnabled());
        assertFalse(result.isFound());
        assertEquals("授信决议集成功能未配置", result.getMessage());
        verify(gateway, never()).latest(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void importLatest_integrationDisabled_rejectsBeforeReadingApplicationOrCallingGateway() {
        when(gateway.isEnabled()).thenReturn(false);

        ServiceException error = assertThrows(ServiceException.class, () -> service.importLatest(91L));

        assertEquals("授信决议集成功能未配置", error.getMessage());
        verify(applicationAccessService).requireDraftOwner(91L);
        verify(applicationMapper, never()).selectById(91L);
        verify(gateway, never()).latest(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lookup_enabled_passesCurrentUsernameAsPerformanceCode() {
        when(gateway.isEnabled()).thenReturn(true);
        SysUserRead user = new SysUserRead();
        user.setUsername(" 100001 ");
        when(appLoginUser.requireCurrentUser()).thenReturn(user);
        when(gateway.latest("100001", 2, "C001")).thenReturn(java.util.Optional.empty());

        CreditResolutionLookupResponse result = service.lookup("CORPORATE_SINGLE", "C001", null);

        assertFalse(result.isFound());
        verify(gateway).latest("100001", 2, "C001");
    }
}
