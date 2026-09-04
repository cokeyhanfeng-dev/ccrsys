package com.ccr.application.integration;

import java.util.Optional;

/** 外部授信决议只读网关。 */
public interface CreditResolutionGateway {

    boolean isEnabled();

    Optional<ExternalCreditResolution> latest(Integer customerType, String customerId);

    DownloadedResolutionFile download(String resolutionId, ExternalResolutionFile file);
}
