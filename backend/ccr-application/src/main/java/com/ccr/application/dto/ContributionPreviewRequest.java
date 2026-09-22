package com.ccr.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 预览当前表单关联人的贡献度；主体始终从已授权申请读取。 */
public record ContributionPreviewRequest(
        @NotNull @Size(max = 200) List<@NotNull @Valid RelatedPerson> relatedPersons) {
    public record RelatedPerson(
            @Size(max = 100) String personName,
            @Size(max = 32) String certType,
            @Size(max = 100) String certNo,
            @Size(max = 64) String relatedCustomerNo) {}
}
