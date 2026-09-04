package com.ccr.application;

import com.ccr.application.dto.CreditResolutionLookupResponse;
import com.ccr.application.integration.CreditResolutionGateway;
import com.ccr.application.integration.CreditResolutionProperties;
import com.ccr.application.mapper.CcrApplicationAttachmentMapper;
import com.ccr.application.mapper.CcrApplicationMapper;
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
    private CcrApplicationMapper applicationMapper;
    @Mock
    private CcrApplicationAttachmentMapper attachmentMapper;
    @Mock
    private TransactionTemplate transactionTemplate;

    private ExternalCreditResolutionService service;

    @BeforeEach
    void setUp() {
        service = new ExternalCreditResolutionService(gateway, new CreditResolutionProperties(),
                applicationAccessService, applicationMapper, attachmentMapper, transactionTemplate);
    }

    @Test
    void lookup_integrationDisabled_returnsNormalStateWithoutCallingGateway() {
        when(gateway.isEnabled()).thenReturn(false);

        CreditResolutionLookupResponse result = service.lookup("CORPORATE_SINGLE", "C001", null);

        assertFalse(result.isEnabled());
        assertFalse(result.isFound());
        assertEquals("授信决议集成功能未配置", result.getMessage());
        verify(gateway, never()).latest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void importLatest_integrationDisabled_rejectsBeforeReadingApplicationOrCallingGateway() {
        when(gateway.isEnabled()).thenReturn(false);

        ServiceException error = assertThrows(ServiceException.class, () -> service.importLatest(91L));

        assertEquals("授信决议集成功能未配置", error.getMessage());
        verify(applicationAccessService).requireDraftOwner(91L);
        verify(applicationMapper, never()).selectById(91L);
        verify(gateway, never()).latest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
