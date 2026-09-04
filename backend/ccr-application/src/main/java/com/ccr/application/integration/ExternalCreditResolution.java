package com.ccr.application.integration;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** API 网关返回的最新有效授信决议。 */
@Data
public class ExternalCreditResolution {
    private String resolutionId;
    private String resolutionNo;
    private Integer customerType;
    private String customerId;
    private String customerName;
    private Integer versionNo;
    /** 保留 Mini-App-Plus 原始时间文本，兼容 yyyy-MM-dd HH:mm:ss 与 ISO-8601。 */
    private String uploadTime;
    private List<ExternalResolutionFile> files = new ArrayList<>();
}
