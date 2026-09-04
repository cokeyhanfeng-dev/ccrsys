package com.ccr.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrApplicationAttachment;
import com.ccr.application.dto.CreditResolutionImportResponse;
import com.ccr.application.dto.CreditResolutionLookupResponse;
import com.ccr.application.integration.CreditResolutionGateway;
import com.ccr.application.integration.CreditResolutionProperties;
import com.ccr.application.integration.DownloadedResolutionFile;
import com.ccr.application.integration.ExternalCreditResolution;
import com.ccr.application.integration.ExternalResolutionFile;
import com.ccr.application.mapper.CcrApplicationAttachmentMapper;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.read.SysUserRead;
import com.ccr.application.support.AppLoginUser;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 外部授信决议查询及申请附件转存编排。 */
@Service
@RequiredArgsConstructor
public class ExternalCreditResolutionService {

    public static final String SOURCE_TYPE = "MINIAPP_CREDIT_RESOLUTION";

    private final CreditResolutionGateway gateway;
    private final CreditResolutionProperties properties;
    private final ApplicationAccessService applicationAccessService;
    private final AppLoginUser appLoginUser;
    private final CcrApplicationMapper applicationMapper;
    private final CcrApplicationAttachmentMapper attachmentMapper;
    private final TransactionTemplate transactionTemplate;

    public CreditResolutionLookupResponse lookup(String customerScope, String customerNo, String groupNo) {
        applicationAccessService.requireCustomerManager();
        if (!gateway.isEnabled()) {
            return new CreditResolutionLookupResponse(false, false, "授信决议集成功能未配置", null);
        }
        String performanceCode = currentPerformanceCode();
        Subject subject = subject(customerScope, customerNo, groupNo);
        Optional<ExternalCreditResolution> latest = gateway.latest(performanceCode, subject.customerType(), subject.customerId());
        return latest
                .map(resolution -> new CreditResolutionLookupResponse(true, true, "已查询到最新有效授信决议", resolution))
                .orElseGet(() -> new CreditResolutionLookupResponse(true, false, "未查询到有效授信决议", null));
    }

    public CreditResolutionImportResponse importLatest(Long applicationId) {
        applicationAccessService.requireDraftOwner(applicationId);
        if (!gateway.isEnabled()) {
            throw new ServiceException(503, "授信决议集成功能未配置");
        }
        String performanceCode = currentPerformanceCode();
        CcrApplication application = applicationMapper.selectById(applicationId);
        if (application == null || "1".equals(application.getDelFlag())) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "申请不存在");
        }
        Subject subject = subject(application.getCustomerScope(), application.getCustomerNo(), application.getGroupNo());
        ExternalCreditResolution resolution = gateway.latest(performanceCode, subject.customerType(), subject.customerId())
                .orElseThrow(() -> new ServiceException(404, "未查询到有效授信决议"));
        List<ExternalResolutionFile> files = resolution.getFiles() == null ? List.of() : resolution.getFiles();
        if (files.size() > properties.getMaxFilesPerResolution()) {
            throw new ServiceException(400, "单份授信决议附件数量不能超过 " + properties.getMaxFilesPerResolution() + " 个");
        }

        List<ExternalResolutionFile> pending = new ArrayList<>();
        int skipped = 0;
        for (ExternalResolutionFile file : files) {
            if (file == null || !StringUtils.hasText(file.getFileId())) {
                throw new ServiceException(502, "授信决议包含无效文件标识");
            }
            if (exists(applicationId, resolution.getResolutionId(), file.getFileId())) {
                skipped++;
            } else {
                pending.add(file);
            }
        }

        List<DownloadedResolutionFile> downloaded = new ArrayList<>();
        long totalSize = 0L;
        for (ExternalResolutionFile file : pending) {
            DownloadedResolutionFile data = gateway.download(performanceCode, resolution.getResolutionId(), file);
            totalSize += data.content().length;
            if (totalSize > properties.getMaxTotalSizeBytes()) {
                throw new ServiceException(400, "单份授信决议附件总大小不能超过 30MB");
            }
            downloaded.add(data);
        }

        Integer imported = transactionTemplate.execute(status -> {
            int count = 0;
            for (DownloadedResolutionFile data : downloaded) {
                if (exists(applicationId, resolution.getResolutionId(), data.fileId())) {
                    continue;
                }
                CcrApplicationAttachment attachment = new CcrApplicationAttachment();
                attachment.setApplicationId(applicationId);
                attachment.setFileName(data.fileName());
                attachment.setFileSize((long) data.content().length);
                attachment.setFileType(limit(data.contentType(), 128));
                attachment.setContent(data.content());
                attachment.setSourceType(SOURCE_TYPE);
                attachment.setSourceBusinessId(resolution.getResolutionId());
                attachment.setSourceFileId(data.fileId());
                attachment.setSourceResolutionNo(limit(resolution.getResolutionNo(), 64));
                try {
                    attachmentMapper.insert(attachment);
                    count++;
                } catch (DuplicateKeyException ignored) {
                    // 并发重复点击由唯一键兜底，保留已落库记录。
                }
            }
            return count;
        });
        List<CcrApplicationAttachment> attachments = listMetadata(applicationId);
        int importedCount = imported == null ? 0 : imported;
        return new CreditResolutionImportResponse(resolution, importedCount,
                skipped + downloaded.size() - importedCount, attachments);
    }

    private boolean exists(Long applicationId, String resolutionId, String fileId) {
        return attachmentMapper.selectCount(new LambdaQueryWrapper<CcrApplicationAttachment>()
                .eq(CcrApplicationAttachment::getApplicationId, applicationId)
                .eq(CcrApplicationAttachment::getSourceType, SOURCE_TYPE)
                .eq(CcrApplicationAttachment::getSourceBusinessId, resolutionId)
                .eq(CcrApplicationAttachment::getSourceFileId, fileId)
                .eq(CcrApplicationAttachment::getDelFlag, "0")) > 0;
    }

    private List<CcrApplicationAttachment> listMetadata(Long applicationId) {
        return attachmentMapper.selectList(new LambdaQueryWrapper<CcrApplicationAttachment>()
                .eq(CcrApplicationAttachment::getApplicationId, applicationId)
                .eq(CcrApplicationAttachment::getDelFlag, "0")
                .orderByAsc(CcrApplicationAttachment::getId));
    }

    private Subject subject(String customerScope, String customerNo, String groupNo) {
        if ("GROUP".equals(customerScope)) {
            if (!StringUtils.hasText(groupNo)) {
                throw new ServiceException(400, "集团客户编号不能为空");
            }
            return new Subject(3, groupNo.trim());
        }
        if (!StringUtils.hasText(customerNo)) {
            throw new ServiceException(400, "客户编号不能为空");
        }
        return new Subject("INDIVIDUAL".equals(customerScope) ? 1 : 2, customerNo.trim());
    }

    private String currentPerformanceCode() {
        SysUserRead user = appLoginUser.requireCurrentUser();
        if (user == null || !StringUtils.hasText(user.getUsername())) {
            throw new ServiceException(ErrorCode.UNAUTHORIZED.getCode(), "当前登录人绩效码不能为空");
        }
        return user.getUsername().trim();
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record Subject(Integer customerType, String customerId) {
    }
}
