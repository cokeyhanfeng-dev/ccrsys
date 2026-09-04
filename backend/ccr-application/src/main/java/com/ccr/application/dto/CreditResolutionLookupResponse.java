package com.ccr.application.dto;

import com.ccr.application.integration.ExternalCreditResolution;
import lombok.AllArgsConstructor;
import lombok.Data;

/** 申请页查询外部授信决议的响应。 */
@Data
@AllArgsConstructor
public class CreditResolutionLookupResponse {
    private boolean enabled;
    private boolean found;
    private String message;
    private ExternalCreditResolution resolution;
}
