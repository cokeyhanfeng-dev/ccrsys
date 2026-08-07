package com.ccr.approval.controller;

import com.ccr.approval.service.ApprovalService;
import com.ccr.approval.support.HistoryArchiveExporter;
import com.ccr.vote.read.SysUserRead;
import com.ccr.vote.support.CurrentLoginUser;
import jakarta.annotation.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 历史档案导出(PRD F7 / §15 审计)
 * 复用档案查询组装 xlsx(Apache POI);文件名带申请号+导出时间;
 * 内容含水印行(操作人/机构/导出时间);仅 admin/auditor/president 可导出
 */
@RestController
@RequestMapping("/ccr/approval/history")
public class HistoryExportController {

    @Resource
    private ApprovalService approvalService;

    @Resource
    private CurrentLoginUser currentLoginUser;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 导出申请档案 xlsx(含水印行) */
    @GetMapping("/{applicationId}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long applicationId) {
        // 导出口径(§15):仅系统管理员/审计人员/总行行长
        currentLoginUser.requireAnyRole("admin", "auditor", "president");

        Map<String, Object> archive = approvalService.historyDetail(applicationId);

        SysUserRead operator = currentLoginUser.requireCurrentUser();
        String exportTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String watermark = "导出操作人:" + operator.getNickName() + "(" + operator.getUsername() + ")"
                + " | 机构:" + orgName(operator.getOrgId())
                + " | 导出时间:" + exportTime;

        byte[] bytes = HistoryArchiveExporter.build(archive, watermark);

        Object appNo = archive.get("application") instanceof Map<?, ?> app
                ? app.get("application_no") : null;
        String filename = "档案_" + (appNo == null ? applicationId : appNo) + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    /** 机构名称(ccr_sys_dept);查不到回退机构id */
    private String orgName(Long orgId) {
        if (orgId == null) {
            return "-";
        }
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT dept_name FROM ccr_sys_dept WHERE id = ? AND del_flag = '0'", String.class, orgId);
            return names.isEmpty() ? String.valueOf(orgId) : names.get(0);
        } catch (Exception e) {
            return String.valueOf(orgId);
        }
    }
}
