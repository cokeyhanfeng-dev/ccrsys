package com.ccr.application.dto;

import com.ccr.application.domain.CcrApplicationAttachment;
import com.ccr.application.integration.ExternalCreditResolution;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 外部授信决议附件转存结果。 */
@Data
@AllArgsConstructor
public class CreditResolutionImportResponse {
    private ExternalCreditResolution resolution;
    private int importedCount;
    private int skippedCount;
    private List<CcrApplicationAttachment> attachments;
}
