package com.ccr.application.integration;

import java.util.Optional;

/** 外部授信决议只读网关。 */
public interface CreditResolutionGateway {

    boolean isEnabled();

    Optional<ExternalCreditResolution> latest(String performanceCode, Integer customerType, String customerId);

    DownloadedResolutionFile download(String performanceCode, String resolutionId, ExternalResolutionFile file);
}
