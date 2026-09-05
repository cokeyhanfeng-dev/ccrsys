package com.ccr.application.controller;

import com.ccr.application.dto.CreditResolutionImportResponse;
import com.ccr.application.dto.CreditResolutionLookupResponse;
import com.ccr.application.service.ExternalCreditResolutionService;
import com.ccr.common.core.domain.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 贷款申请页外部授信决议查询与自动附件转存。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/ccr/external-credit-resolutions")
public class ExternalCreditResolutionController {

    private final ExternalCreditResolutionService service;

    @GetMapping("/applications/{applicationId}/files/{fileId}/preview")
    public org.springframework.http.ResponseEntity<byte[]> preview(@PathVariable Long applicationId,
            @PathVariable String fileId, @RequestParam String resolutionId) {
        var file = service.preview(applicationId, resolutionId, fileId);
        // 只允许已知的非脚本文件内联展示，其余作为下载；不信任远端文件名/MIME。
        byte[] bytes = file.content();
        String type = "application/octet-stream";
        if (startsWith(bytes, new byte[]{37, 80, 68, 70, 45})) type = "application/pdf";
        else if (startsWith(bytes, new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10})) type = "image/png";
        else if (startsWith(bytes, new byte[]{(byte)255, (byte)216, (byte)255})) type = "image/jpeg";
        String disposition = "application/octet-stream".equals(type) ? "attachment" : "inline";
        return org.springframework.http.ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", org.springframework.http.ContentDisposition.builder(disposition)
                        .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentType(org.springframework.http.MediaType.parseMediaType(type))
                .body(bytes);
    }

    private static boolean startsWith(byte[] data, byte[] signature) {
        if (data == null || data.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (data[i] != signature[i]) return false;
        return true;
    }

    @GetMapping("/latest")
    public R<CreditResolutionLookupResponse> latest(
            @RequestParam String customerScope,
            @RequestParam(required = false) String customerNo,
            @RequestParam(required = false) String groupNo) {
        return R.ok(service.lookup(customerScope, customerNo, groupNo));
    }

    @PostMapping("/applications/{applicationId}/import-latest")
    public R<CreditResolutionImportResponse> importLatest(@PathVariable Long applicationId) {
        return R.ok(service.importLatest(applicationId));
    }
}
