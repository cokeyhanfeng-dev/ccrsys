package com.ccr.approval.controller;

import com.ccr.approval.service.ApprovalService;
import com.ccr.approval.support.HistoryArchiveExporter;
import com.ccr.approval.support.ResolutionPdfExporter;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.vote.read.SysUserRead;
import com.ccr.vote.support.CurrentLoginUser;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
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
@Slf4j
public class HistoryExportController {

    @Resource
    private ApprovalService approvalService;

    @Resource
    private CurrentLoginUser currentLoginUser;

    @Resource
    private ApplicationAccessService applicationAccessService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 导出申请档案 xlsx(含水印行) */
    @GetMapping("/{applicationId}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long applicationId) {
        // 导出口径(§15):仅系统管理员/审计人员/总行行长
        currentLoginUser.requireAnyRole("admin", "auditor", "president");
        applicationAccessService.requireView(applicationId);

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

        // 导出留痕(§11.10):写 ccr_export_record,失败仅记日志不阻断导出
        recordExport(applicationId, filename, operator);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    /** 决议书下载(§决议):按申请生成只读 PDF 决议书,供客户经理/审批人在历史申请中下载/打印。
     *  权限保护:PDF 加密(256位),禁编辑/复制/提取/组装,仅可打印——下载后不可修改;
     *  数据权限复用 historyDetail(checkHistoryPermission:客户经理本人/审批参与/行长全量);
     *  仅已签发决议(ccr_resolution)的申请可下载,无决议返回 404 提示。 */
    @GetMapping("/{applicationId}/resolution-doc")
    public ResponseEntity<byte[]> resolutionDoc(@PathVariable Long applicationId) throws IOException {
        Map<String, Object> archive = approvalService.historyDetail(applicationId);
        List<?> resolutions = (List<?>) archive.get("resolutions");
        if (resolutions == null || resolutions.isEmpty()) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "该申请暂无已通过的决议,无法生成决议书");
        }
        byte[] bytes = ResolutionPdfExporter.build(archive);
        Object appNo = archive.get("application") instanceof Map<?, ?> app
                ? app.get("application_no") : null;
        String filename = "利率定价决议书_" + (appNo == null ? applicationId : appNo) + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".pdf";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("application/pdf"))
                .body(bytes);
    }

    /** 导出留痕(§11.10):ccr_export_record;表不存在(未执行 03f)时仅记日志,不影响导出 */
    private void recordExport(Long applicationId, String fileName, SysUserRead operator) {
        try {
            long id = IdUtil.getSnowflakeNextId();
            jdbcTemplate.update("""
                            INSERT INTO ccr_export_record
                            (id, export_no, application_id, export_type, file_name,
                             operator_id, operator_name, org_id, export_time)
                            VALUES (?,?,?,?,?,?,?,?,?)
                            """,
                    id, "EXP" + id, applicationId, "ARCHIVE_XLSX", fileName,
                    operator.getId(), operator.getNickName(), operator.getOrgId(), LocalDateTime.now());
        } catch (DataAccessException e) {
            log.warn("导出记录写入失败(不影响导出): {}", e.getMessage());
        }
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
