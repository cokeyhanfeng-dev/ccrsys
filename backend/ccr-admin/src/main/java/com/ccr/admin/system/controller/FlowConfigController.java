package com.ccr.admin.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrLprVersion;
import com.ccr.rule.domain.CcrRateMatrix;
import com.ccr.rule.mapper.CcrLprVersionMapper;
import com.ccr.rule.mapper.CcrRateMatrixMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 审批流程配置(基础系统功能)
 * 流程定义管理(基于 warm-flow flow_definition)+ 节点审批人 + 阈值配置(LPR/权限矩阵)
 * 阈值版本生命周期(§8.4):草稿(DRAFT)→送审(REVIEW)→复核发布(EFFECTIVE)→停用(INVALID);
 * 发布强制双人复核(reviewBy≠createBy);已生效记录禁止原位 UPDATE,只能新建版本;
 * 发布新版本时同维度旧生效版本自动停用,LPR 发布新值后路由自动采用(路由按生效窗口取现行版本)
 */
@RestController
@RequestMapping("/system/flow")
public class FlowConfigController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private CcrLprVersionMapper lprVersionMapper;

    @Resource
    private CcrRateMatrixMapper rateMatrixMapper;

    /** 流程定义列表(flow_definition) */
    @GetMapping("/definitions")
    public R<List<Map<String, Object>>> definitions() {
        String sql = """
                SELECT id, flow_code, flow_name, version, is_publish, activity_status,
                       create_time
                FROM flow_definition
                WHERE del_flag = '0'
                ORDER BY create_time DESC
                """;
        return R.ok(jdbcTemplate.queryForList(sql));
    }

    /** 发布流程 */
    @PostMapping("/definitions/{id}/publish")
    public R<Void> publish(@PathVariable Long id) {
        int updated = jdbcTemplate.update(
                "UPDATE flow_definition SET is_publish = 1, update_time = ? WHERE id = ?",
                LocalDateTime.now(), id);
        if (updated == 0) {
            throw new ServiceException(404, "流程不存在");
        }
        return R.ok();
    }

    /** 停用流程 */
    @PostMapping("/definitions/{id}/unpublish")
    public R<Void> unpublish(@PathVariable Long id) {
        jdbcTemplate.update(
                "UPDATE flow_definition SET is_publish = 0, update_time = ? WHERE id = ?",
                LocalDateTime.now(), id);
        return R.ok();
    }

    /** LPR 阈值配置列表(计划财务部人工维护,PRD D12;status 可过滤,缺省全部) */
    @GetMapping("/thresholds/lpr")
    public R<List<CcrLprVersion>> lprList(@RequestParam(required = false) String status) {
        return R.ok(lprVersionMapper.selectList(new LambdaQueryWrapper<CcrLprVersion>()
                .eq(StrUtil.isNotBlank(status), CcrLprVersion::getStatus, status)
                .orderByDesc(CcrLprVersion::getCreateTime)));
    }

    /** 权限矩阵阈值配置列表(PRD §7.2 LPR±BP;status 可过滤,缺省仅生效) */
    @GetMapping("/thresholds/matrix")
    public R<List<CcrRateMatrix>> matrixList(@RequestParam(required = false) String status) {
        return R.ok(rateMatrixMapper.selectList(new LambdaQueryWrapper<CcrRateMatrix>()
                .eq(CcrRateMatrix::getStatus, StrUtil.isBlank(status) ? "EFFECTIVE" : status)
                .orderByAsc(CcrRateMatrix::getBusinessBigType, CcrRateMatrix::getPriority)));
    }

    // ---------- LPR 版本生命周期(§8.4) ----------

    /** 新增 LPR 草稿 */
    @PostMapping("/thresholds/lpr")
    public R<Long> createLpr(@RequestBody CcrLprVersion lpr) {
        if (StrUtil.isBlank(lpr.getVersionCode()) || lpr.getLpr1y() == null || lpr.getLpr5y() == null
                || lpr.getEffectiveFrom() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "版本号/一年期/五年期LPR/生效时间必填");
        }
        lpr.setId(null);
        lpr.setStatus("DRAFT");
        lpr.setPublishBy(null);
        lpr.setReviewBy(null);
        lpr.setPublishTime(null);
        lprVersionMapper.insert(lpr);
        return R.ok(lpr.getId());
    }

    /** LPR 送审:DRAFT → REVIEW */
    @PostMapping("/thresholds/lpr/{id}/submit")
    public R<Void> submitLpr(@PathVariable Long id) {
        CcrLprVersion lpr = lprVersionMapper.selectById(id);
        if (lpr == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "LPR版本不存在");
        }
        if (!"DRAFT".equals(lpr.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅草稿状态可送审(当前:" + lpr.getStatus() + ")");
        }
        lpr.setStatus("REVIEW");
        lprVersionMapper.updateById(lpr);
        return R.ok();
    }

    /** LPR 复核发布:REVIEW → EFFECTIVE;双人复核;旧生效版本自动停用,路由自动采用新值 */
    @PostMapping("/thresholds/lpr/{id}/publish")
    public R<Void> publishLpr(@PathVariable Long id) {
        CcrLprVersion lpr = lprVersionMapper.selectById(id);
        if (lpr == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "LPR版本不存在");
        }
        if (!"REVIEW".equals(lpr.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅待复核状态可发布(当前:" + lpr.getStatus() + ")");
        }
        long currentUser = StpUtil.getLoginIdAsLong();
        if (lpr.getCreateBy() != null && lpr.getCreateBy() == currentUser) {
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "发布人与创建人不得为同一人(双人复核)");
        }
        // 旧生效版本自动停用(全局仅一个生效 LPR)
        List<CcrLprVersion> oldEffective = lprVersionMapper.selectList(new LambdaQueryWrapper<CcrLprVersion>()
                .eq(CcrLprVersion::getStatus, "EFFECTIVE")
                .ne(CcrLprVersion::getId, id));
        for (CcrLprVersion old : oldEffective) {
            old.setStatus("INVALID");
            old.setEffectiveTo(lpr.getEffectiveFrom());
            lprVersionMapper.updateById(old);
        }
        lpr.setStatus("EFFECTIVE");
        lpr.setPublishBy(currentUser);
        lpr.setReviewBy(currentUser);
        lpr.setPublishTime(LocalDateTime.now());
        lprVersionMapper.updateById(lpr);
        return R.ok();
    }

    /** LPR 停用:EFFECTIVE → INVALID */
    @PostMapping("/thresholds/lpr/{id}/disable")
    public R<Void> disableLpr(@PathVariable Long id) {
        CcrLprVersion lpr = lprVersionMapper.selectById(id);
        if (lpr == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "LPR版本不存在");
        }
        if (!"EFFECTIVE".equals(lpr.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅已生效状态可停用(当前:" + lpr.getStatus() + ")");
        }
        lpr.setStatus("INVALID");
        lpr.setEffectiveTo(LocalDateTime.now());
        lprVersionMapper.updateById(lpr);
        return R.ok();
    }

    // ---------- 权限矩阵版本生命周期(§8.4) ----------

    /** 新增矩阵行草稿(生效行禁止原位修改,调整=新建行发布替换) */
    @PostMapping("/thresholds/matrix")
    public R<Long> createMatrix(@RequestBody CcrRateMatrix row) {
        if (StrUtil.isBlank(row.getMatrixNo()) || StrUtil.isBlank(row.getBusinessBigType())
                || StrUtil.isBlank(row.getNewOrExisting()) || StrUtil.isBlank(row.getStartNodeCode())
                || row.getEffectiveFrom() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "矩阵行编码/业务大类/存量新增/终审岗位/生效时间必填");
        }
        row.setId(null);
        row.setStatus("DRAFT");
        row.setPublishBy(null);
        row.setReviewBy(null);
        row.setPublishTime(null);
        rateMatrixMapper.insert(row);
        return R.ok(row.getId());
    }

    /** 矩阵行送审:DRAFT → REVIEW */
    @PostMapping("/thresholds/matrix/{id}/submit")
    public R<Void> submitMatrix(@PathVariable Long id) {
        CcrRateMatrix row = rateMatrixMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "矩阵行不存在");
        }
        if (!"DRAFT".equals(row.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅草稿状态可送审(当前:" + row.getStatus() + ")");
        }
        row.setStatus("REVIEW");
        rateMatrixMapper.updateById(row);
        return R.ok();
    }

    /** 矩阵行复核发布:REVIEW → EFFECTIVE;双人复核;同维度同岗位旧生效行自动停用 */
    @PostMapping("/thresholds/matrix/{id}/publish")
    public R<Void> publishMatrix(@PathVariable Long id) {
        CcrRateMatrix row = rateMatrixMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "矩阵行不存在");
        }
        if (!"REVIEW".equals(row.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅待复核状态可发布(当前:" + row.getStatus() + ")");
        }
        long currentUser = StpUtil.getLoginIdAsLong();
        if (row.getCreateBy() != null && row.getCreateBy() == currentUser) {
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "发布人与创建人不得为同一人(双人复核)");
        }
        // 同维度(业务/存量新增/客户/产品/金额档/期限档/担保/岗位)旧生效行自动停用
        List<CcrRateMatrix> oldEffective = rateMatrixMapper.selectList(new LambdaQueryWrapper<CcrRateMatrix>()
                .eq(CcrRateMatrix::getStatus, "EFFECTIVE")
                .eq(CcrRateMatrix::getBusinessBigType, row.getBusinessBigType())
                .eq(CcrRateMatrix::getNewOrExisting, row.getNewOrExisting())
                .ne(CcrRateMatrix::getId, id));
        for (CcrRateMatrix old : oldEffective) {
            if (sameDimension(old, row)) {
                old.setStatus("INVALID");
                old.setEffectiveTo(row.getEffectiveFrom());
                rateMatrixMapper.updateById(old);
            }
        }
        row.setStatus("EFFECTIVE");
        row.setPublishBy(currentUser);
        row.setReviewBy(currentUser);
        row.setPublishTime(LocalDateTime.now());
        rateMatrixMapper.updateById(row);
        return R.ok();
    }

    /** 矩阵行停用:EFFECTIVE → INVALID */
    @PostMapping("/thresholds/matrix/{id}/disable")
    public R<Void> disableMatrix(@PathVariable Long id) {
        CcrRateMatrix row = rateMatrixMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "矩阵行不存在");
        }
        if (!"EFFECTIVE".equals(row.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅已生效状态可停用(当前:" + row.getStatus() + ")");
        }
        row.setStatus("INVALID");
        row.setEffectiveTo(LocalDateTime.now());
        rateMatrixMapper.updateById(row);
        return R.ok();
    }

    /** 同维度判定(空值安全;岗位不同不算同维度,链路上各岗位行并存) */
    private boolean sameDimension(CcrRateMatrix a, CcrRateMatrix b) {
        return Objects.equals(a.getCustomerType(), b.getCustomerType())
                && Objects.equals(a.getProductCode(), b.getProductCode())
                && Objects.equals(a.getAmountTier(), b.getAmountTier())
                && Objects.equals(a.getTermTier(), b.getTermTier())
                && Objects.equals(a.getGuaranteeType(), b.getGuaranteeType())
                && Objects.equals(a.getStartNodeCode(), b.getStartNodeCode());
    }
}
