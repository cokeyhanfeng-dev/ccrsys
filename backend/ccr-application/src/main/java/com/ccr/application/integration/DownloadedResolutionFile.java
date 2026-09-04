package com.ccr.application.integration;

/** 已通过短期地址取回、等待转存至申请附件的文件。 */
public record DownloadedResolutionFile(
        String fileId,
        String fileName,
        String contentType,
        byte[] content) {
}
