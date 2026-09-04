package com.ccr.application.integration;

import lombok.Data;

/** 外部授信决议文件元数据；不包含私有桶地址和凭证。 */
@Data
public class ExternalResolutionFile {
    private String fileId;
    private String fileName;
    private Long fileSize;
    private String contentType;
}
