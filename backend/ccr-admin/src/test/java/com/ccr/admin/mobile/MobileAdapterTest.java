package com.ccr.admin.mobile;

import com.ccr.approval.controller.ApprovalController;
import com.ccr.application.controller.AttachmentController;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.common.core.domain.R;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MobileAdapterTest {
    @Test void approvalPreservesVersionAndSameIdempotencyKeyOnRetry() {
        var api=new MobileApprovalController();var approval=mock(ApprovalController.class);var objects=mock(ApplicationAccessService.class);
        ReflectionTestUtils.setField(api,"approval",approval);ReflectionTestUtils.setField(api,"objects",objects);
        var body=new MobileApprovalController.Approval(91L,"BRANCH_MANAGER",7,"同意",null);
        when(approval.approve(eq("same-key"),any())).thenReturn(R.ok());
        api.approve("same-key",body);api.approve("same-key",body);
        verify(approval,times(2)).approve(eq("same-key"),argThat(m->m.get("applicationId").equals(91L)&&m.get("versionNo").equals(7)));
        doThrow(new ServiceException(403,"无权查看")).when(objects).requireView(92L);
        assertThrows(ServiceException.class,()->api.approve("denied",new MobileApprovalController.Approval(92L,"BRANCH_MANAGER",7,"",Map.of())));
        verify(approval,never()).approve(eq("denied"),any());
        when(approval.approve(eq("conflict"),any())).thenThrow(new ServiceException(1010,"版本冲突"));
        assertThrows(ServiceException.class,()->api.approve("conflict",body));
    }
    @Test void attachmentsPreserveObjectAuthorizationAndNoStore() {
        var api=new MobileApprovalController();var files=mock(AttachmentController.class);ReflectionTestUtils.setField(api,"attachments",files);
        when(files.download(1L,2L)).thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(new byte[]{1,2}));
        var response=api.file(1L,2L);assertArrayEquals(new byte[]{1,2},response.getBody());assertEquals("no-store",response.getHeaders().getFirst("Cache-Control"));
        when(files.download(9L,2L)).thenThrow(new ServiceException(403,"无权查看申请"));
        assertThrows(ServiceException.class,()->api.file(9L,2L));
    }
    @Test void rejectRequiresReasonBeforeCallingBusinessService() {
        var api=new MobileApprovalController();var approval=mock(ApprovalController.class);ReflectionTestUtils.setField(api,"approval",approval);
        assertThrows(ServiceException.class,()->api.reject("key",new MobileApprovalController.Approval(1L,"SECRETARY",1," ",null)));
        verifyNoInteractions(approval);
    }
}
