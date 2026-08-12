package com.ccr.application.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.application.domain.CcrApplicationAttachment;
import com.ccr.application.mapper.CcrApplicationAttachmentMapper;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.common.core.domain.R;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 申请附件(§7.1 步骤6 材料附件):上传/列表/下载
 * 演示期内容落库 ccr_application_attachment.content
 */
@RestController
@RequestMapping("/ccr/applications/{applicationId}/attachments")
public class AttachmentController {

    /** 单文件大小上限(演示期 10MB) */
    private static final long MAX_SIZE = 10L * 1024 * 1024;

    @Resource
    private CcrApplicationAttachmentMapper attachmentMapper;

    @Resource
    private ApplicationAccessService applicationAccessService;

    /** 上传(多文件逐个调用) */
    @PostMapping
    public R<Map<String, Object>> upload(@PathVariable Long applicationId, @RequestParam("file") MultipartFile file) {
        applicationAccessService.requireDraftOwner(applicationId);
        if (file == null || file.isEmpty()) {
            throw new ServiceException(400, "请选择附件文件");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ServiceException(400, "附件大小不能超过 10MB");
        }
        try {
            CcrApplicationAttachment att = new CcrApplicationAttachment();
            att.setApplicationId(applicationId);
            att.setFileName(file.getOriginalFilename() == null ? "附件" : file.getOriginalFilename());
            att.setFileSize(file.getSize());
            att.setFileType(file.getContentType() != null && file.getContentType().length() > 128
                    ? file.getContentType().substring(0, 128) : file.getContentType());
            att.setContent(file.getBytes());
            attachmentMapper.insert(att);
            return R.ok(Map.of("id", att.getId(), "fileName", att.getFileName(), "fileSize", att.getFileSize()));
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("附件上传失败:" + e.getMessage());
        }
    }

    /** 附件列表(不含内容) */
    @GetMapping
    public R<List<CcrApplicationAttachment>> list(@PathVariable Long applicationId) {
        applicationAccessService.requireView(applicationId);
        return R.ok(attachmentMapper.selectList(new LambdaQueryWrapper<CcrApplicationAttachment>()
                .eq(CcrApplicationAttachment::getApplicationId, applicationId)
                .eq(CcrApplicationAttachment::getDelFlag, "0")
                .orderByAsc(CcrApplicationAttachment::getId)));
    }

    /** 下载 */
    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long applicationId, @PathVariable Long attachmentId) {
        applicationAccessService.requireView(applicationId);
        CcrApplicationAttachment att = attachmentMapper.selectOne(new LambdaQueryWrapper<CcrApplicationAttachment>()
                .eq(CcrApplicationAttachment::getId, attachmentId)
                .eq(CcrApplicationAttachment::getApplicationId, applicationId)
                .eq(CcrApplicationAttachment::getDelFlag, "0")
                .select(CcrApplicationAttachment::getFileName, CcrApplicationAttachment::getFileType,
                        CcrApplicationAttachment::getContent));
        if (att == null || att.getContent() == null) {
            throw new ServiceException(404, "附件不存在");
        }
        String encoded = URLEncoder.encode(att.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(att.getFileType() != null ? MediaType.parseMediaType(att.getFileType())
                        : MediaType.APPLICATION_OCTET_STREAM)
                .body(att.getContent());
    }
}
