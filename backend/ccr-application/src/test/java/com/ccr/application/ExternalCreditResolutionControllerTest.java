package com.ccr.application;

import com.ccr.application.controller.ExternalCreditResolutionController;
import com.ccr.application.integration.DownloadedResolutionFile;
import com.ccr.application.service.ExternalCreditResolutionService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalCreditResolutionControllerTest {
    @Test
    void preview_sniffsBytesAndPreventsActiveContentInline() {
        var service = mock(ExternalCreditResolutionService.class);
        var controller = new ExternalCreditResolutionController(service);
        byte[][] contents = {"%PDF-1.7".getBytes(), {(byte)137,80,78,71,13,10,26,10},
                {(byte)255,(byte)216,(byte)255}, "<svg onload='alert(1)'/>".getBytes()};
        String[] types = {"application/pdf", "image/png", "image/jpeg", "application/octet-stream"};
        for (int i = 0; i < contents.length; i++) {
            when(service.preview(91L, "R1", "F1")).thenReturn(
                    new DownloadedResolutionFile("F1", "决议.pdf", "application/pdf", contents[i]));
            var response = controller.preview(91L, "F1", "R1");
            assertEquals(types[i], response.getHeaders().getContentType().toString());
            assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
            assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
            assertEquals(i == 3 ? "attachment" : "inline", response.getHeaders().getContentDisposition().getType());
            assertArrayEquals(contents[i], response.getBody());
        }
    }
}
