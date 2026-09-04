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
