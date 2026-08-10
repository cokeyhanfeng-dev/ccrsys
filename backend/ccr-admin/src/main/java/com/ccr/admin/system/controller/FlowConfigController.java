package com.ccr.admin.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.cache.CcrCacheUtil;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrConfigChangeLog;
import com.ccr.rule.domain.CcrLprVersion;
import com.ccr.rule.domain.CcrProductRateLimit;
import com.ccr.rule.domain.CcrRateMatrix;
import com.ccr.rule.mapper.CcrConfigChangeLogMapper;
import com.ccr.rule.mapper.CcrLprVersionMapper;
import com.ccr.rule.mapper.CcrProductRateLimitMapper;
import com.ccr.rule.mapper.CcrRateMatrixMapper;
import com.ccr.rule.service.ConfigChangeLogService;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 审批流程配置(基础系统功能)
 * 流程定义管理(基于 warm-flow flow_definition)+ 节点审批人 + 阈值配置(LPR/权限矩阵/产品边界)
 * 阈值版本生命周期(§8.4/§8A.2):草稿(DRAFT)→送审(REVIEW)→复核发布(EFFECTIVE)→停用(INVALID);
 * 复核驳回(REVIEW→DRAFT,必填驳回意见);发布强制双人复核(reviewBy≠createBy);
 * 已生效记录禁止原位 UPDATE,只能新建版本;发布新版本时同维度旧生效版本自动停用;
 * 发布前完整性校验(§8A.3/§8A.4),全部变更写 ccr_config_change_log 审计(§8A.2)
 * 角色口径(用户拍板):无 param_admin 角色,参数维护归 admin、复核发布归 config_reviewer
 */
@RestController
@RequestMapping("/system/flow")
public class FlowConfigController {

    /** LPR 合理区间下限(%)(§8A.3) */
    private static final BigDecimal LPR_MIN = new BigDecimal("0.5");

    /** LPR 合理区间上限(%)(§8A.3) */
    private static final BigDecimal LPR_MAX = new BigDecimal("8");

    /** LPR 报价步长:0.05 的整数倍(§8A.3) */
    private static final BigDecimal LPR_STEP = new BigDecimal("0.05");

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private CcrLprVersionMapper lprVersionMapper;

    @Resource
    private CcrRateMatrixMapper rateMatrixMapper;

    @Resource
    private CcrProductRateLimitMapper productRateLimitMapper;

    @Resource
    private CcrConfigChangeLogMapper configChangeLogMapper;

    @Resource
    private ConfigChangeLogService configChangeLogService;

    @Resource
    private CcrCacheUtil cacheUtil;

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

    /** 流程定义查看(节点+跳转关系,供前端只读流程图渲染) */
    @GetMapping("/definitions/{id}/detail")
    public R<Map<String, Object>> definitionDetail(@PathVariable Long id) {
        List<Map<String, Object>> defs = jdbcTemplate.queryForList(
                "SELECT id, flow_code, flow_name, version, is_publish, activity_status FROM flow_definition WHERE id = ? AND del_flag = '0'", id);
        if (defs.isEmpty()) {
            throw new ServiceException(404, "流程定义不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>(defs.get(0));
        result.put("nodes", jdbcTemplate.queryForList(
                "SELECT node_type nodeType, node_code nodeCode, node_name nodeName FROM flow_node WHERE definition_id = ? AND del_flag = '0' ORDER BY id", id));
        result.put("skips", jdbcTemplate.queryForList(
                "SELECT now_node_code nowNodeCode, next_node_code nextNodeCode, skip_name skipName, skip_type skipType FROM flow_skip WHERE definition_id = ? AND del_flag = '0' ORDER BY id", id));
        return R.ok(result);
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

    /** 新增 LPR 草稿(发布前校验 §8A.3:区间/报价步长/生效日) */
    @PostMapping("/thresholds/lpr")
    public R<Long> createLpr(@RequestBody CcrLprVersion lpr) {
        if (StrUtil.isBlank(lpr.getVersionCode()) || lpr.getLpr1y() == null || lpr.getLpr5y() == null
                || lpr.getEffectiveFrom() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "版本号/一年期/五年期LPR/生效时间必填");
        }
        validateLpr(lpr, null);
        lpr.setId(null);
        lpr.setStatus("DRAFT");
        lpr.setPublishBy(null);
        lpr.setReviewBy(null);
        lpr.setPublishTime(null);
        lprVersionMapper.insert(lpr);
        configChangeLogService.record(ConfigChangeLogService.TYPE_LPR, lpr.getId(), lpr.getVersionNo(),
                ConfigChangeLogService.ACTION_CREATE, null, lpr, null);
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
        String oldJson = JSONUtil.toJsonStr(lpr);
        lpr.setStatus("REVIEW");
        lprVersionMapper.updateById(lpr);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_LPR, id, lpr.getVersionNo(),
                ConfigChangeLogService.ACTION_SUBMIT, oldJson, JSONUtil.toJsonStr(lpr), null);
        return R.ok();
    }

    /** LPR 复核发布:REVIEW → EFFECTIVE;双人复核;发布前校验(§8A.3);旧生效版本自动停用,路由自动采用新值 */
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
        validateLpr(lpr, id);
        // 旧生效版本自动停用(全局仅一个生效 LPR)
        List<CcrLprVersion> oldEffective = lprVersionMapper.selectList(new LambdaQueryWrapper<CcrLprVersion>()
                .eq(CcrLprVersion::getStatus, "EFFECTIVE")
                .ne(CcrLprVersion::getId, id));
        for (CcrLprVersion old : oldEffective) {
            String oldJson = JSONUtil.toJsonStr(old);
            old.setStatus("INVALID");
            old.setEffectiveTo(lpr.getEffectiveFrom());
            lprVersionMapper.updateById(old);
            configChangeLogService.recordJson(ConfigChangeLogService.TYPE_LPR, old.getId(), old.getVersionNo(),
                    ConfigChangeLogService.ACTION_DISABLE, oldJson, JSONUtil.toJsonStr(old),
                    "新版本" + lpr.getVersionCode() + "发布,旧生效版本自动停用");
        }
        String oldJson = JSONUtil.toJsonStr(lpr);
        lpr.setStatus("EFFECTIVE");
        lpr.setPublishBy(currentUser);
        lpr.setReviewBy(currentUser);
        lpr.setPublishTime(LocalDateTime.now());
        lprVersionMapper.updateById(lpr);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_LPR, id, lpr.getVersionNo(),
                ConfigChangeLogService.ACTION_PUBLISH, oldJson, JSONUtil.toJsonStr(lpr), null);
        evictConfig(CcrCacheUtil.KEY_LPR_EFFECTIVE);
        return R.ok();
    }

    /** LPR 复核驳回:REVIEW → DRAFT(必填驳回意见,§8A.2) */
    @PostMapping("/thresholds/lpr/{id}/reject")
    public R<Void> rejectLpr(@PathVariable Long id, @RequestParam String opinion) {
        CcrLprVersion lpr = lprVersionMapper.selectById(id);
        if (lpr == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "LPR版本不存在");
        }
        if (!"REVIEW".equals(lpr.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅待复核状态可驳回(当前:" + lpr.getStatus() + ")");
        }
        if (StrUtil.isBlank(opinion)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "驳回意见必填");
        }
        String oldJson = JSONUtil.toJsonStr(lpr);
        lpr.setStatus("DRAFT");
        lprVersionMapper.updateById(lpr);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_LPR, id, lpr.getVersionNo(),
                ConfigChangeLogService.ACTION_REJECT, oldJson, JSONUtil.toJsonStr(lpr), opinion);
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
        String oldJson = JSONUtil.toJsonStr(lpr);
        lpr.setStatus("INVALID");
        lpr.setEffectiveTo(LocalDateTime.now());
        lprVersionMapper.updateById(lpr);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_LPR, id, lpr.getVersionNo(),
                ConfigChangeLogService.ACTION_DISABLE, oldJson, JSONUtil.toJsonStr(lpr), null);
        evictConfig(CcrCacheUtil.KEY_LPR_EFFECTIVE);
        return R.ok();
    }

    // ---------- 权限矩阵版本生命周期(§8.4) ----------

    /** 新增矩阵行草稿(生效行禁止原位修改,调整=新建行发布替换;边界互斥必填校验 §8A.4) */
    @PostMapping("/thresholds/matrix")
    public R<Long> createMatrix(@RequestBody CcrRateMatrix row) {
        if (StrUtil.isBlank(row.getMatrixNo()) || StrUtil.isBlank(row.getBusinessBigType())
                || StrUtil.isBlank(row.getNewOrExisting()) || StrUtil.isBlank(row.getStartNodeCode())
                || row.getEffectiveFrom() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "矩阵行编码/业务大类/存量新增/终审岗位/生效时间必填");
        }
        validateMatrixBoundary(row);
        row.setId(null);
        row.setStatus("DRAFT");
        row.setPublishBy(null);
        row.setReviewBy(null);
        row.setPublishTime(null);
        rateMatrixMapper.insert(row);
        configChangeLogService.record(ConfigChangeLogService.TYPE_MATRIX, row.getId(), row.getVersionNo(),
                ConfigChangeLogService.ACTION_CREATE, null, row, null);
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
        String oldJson = JSONUtil.toJsonStr(row);
        row.setStatus("REVIEW");
        rateMatrixMapper.updateById(row);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_MATRIX, id, row.getVersionNo(),
                ConfigChangeLogService.ACTION_SUBMIT, oldJson, JSONUtil.toJsonStr(row), null);
        return R.ok();
    }

    /** 矩阵行复核发布:REVIEW → EFFECTIVE;双人复核;发布前校验(§8A.4);同维度同岗位旧生效行自动停用 */
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
        validateMatrixForPublish(row);
        // 同维度(业务/存量新增/客户/产品/金额档/期限档/担保/岗位)旧生效行自动停用
        List<CcrRateMatrix> oldEffective = rateMatrixMapper.selectList(new LambdaQueryWrapper<CcrRateMatrix>()
                .eq(CcrRateMatrix::getStatus, "EFFECTIVE")
                .eq(CcrRateMatrix::getBusinessBigType, row.getBusinessBigType())
                .eq(CcrRateMatrix::getNewOrExisting, row.getNewOrExisting())
                .ne(CcrRateMatrix::getId, id));
        for (CcrRateMatrix old : oldEffective) {
            if (sameDimension(old, row)) {
                String oldJson = JSONUtil.toJsonStr(old);
                old.setStatus("INVALID");
                old.setEffectiveTo(row.getEffectiveFrom());
                rateMatrixMapper.updateById(old);
                configChangeLogService.recordJson(ConfigChangeLogService.TYPE_MATRIX, old.getId(), old.getVersionNo(),
                        ConfigChangeLogService.ACTION_DISABLE, oldJson, JSONUtil.toJsonStr(old),
                        "同维度新行" + row.getMatrixNo() + "发布,旧生效行自动停用");
            }
        }
        String oldJson = JSONUtil.toJsonStr(row);
        row.setStatus("EFFECTIVE");
        row.setPublishBy(currentUser);
        row.setReviewBy(currentUser);
        row.setPublishTime(LocalDateTime.now());
        rateMatrixMapper.updateById(row);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_MATRIX, id, row.getVersionNo(),
                ConfigChangeLogService.ACTION_PUBLISH, oldJson, JSONUtil.toJsonStr(row), null);
        evictConfig(CcrCacheUtil.KEY_MATRIX_EFFECTIVE);
        return R.ok();
    }

    /** 矩阵行复核驳回:REVIEW → DRAFT(必填驳回意见,§8A.2) */
    @PostMapping("/thresholds/matrix/{id}/reject")
    public R<Void> rejectMatrix(@PathVariable Long id, @RequestParam String opinion) {
        CcrRateMatrix row = rateMatrixMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "矩阵行不存在");
        }
        if (!"REVIEW".equals(row.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅待复核状态可驳回(当前:" + row.getStatus() + ")");
        }
        if (StrUtil.isBlank(opinion)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "驳回意见必填");
        }
        String oldJson = JSONUtil.toJsonStr(row);
        row.setStatus("DRAFT");
        rateMatrixMapper.updateById(row);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_MATRIX, id, row.getVersionNo(),
                ConfigChangeLogService.ACTION_REJECT, oldJson, JSONUtil.toJsonStr(row), opinion);
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
        String oldJson = JSONUtil.toJsonStr(row);
        row.setStatus("INVALID");
        row.setEffectiveTo(LocalDateTime.now());
        rateMatrixMapper.updateById(row);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_MATRIX, id, row.getVersionNo(),
                ConfigChangeLogService.ACTION_DISABLE, oldJson, JSONUtil.toJsonStr(row), null);
        evictConfig(CcrCacheUtil.KEY_MATRIX_EFFECTIVE);
        return R.ok();
    }

    /** 配置发布/停用后缓存失效(§8A.2/§3.6):删除对应域缓存 key + 递增全局版本号,下次请求重建 */
    private void evictConfig(String... keys) {
        cacheUtil.delete(keys);
        cacheUtil.increment(CcrCacheUtil.GLOBAL_VER_KEY);
    }

    // ---------- 产品硬边界生命周期(§8A.5/§11.9,复用 LPR 双人复核模式) ----------

    /** 产品硬边界列表(status 可过滤,缺省全部) */
    @GetMapping("/thresholds/product-limit")
    public R<List<CcrProductRateLimit>> productLimitList(@RequestParam(required = false) String status) {
        return R.ok(productRateLimitMapper.selectList(new LambdaQueryWrapper<CcrProductRateLimit>()
                .eq(StrUtil.isNotBlank(status), CcrProductRateLimit::getStatus, status)
                .orderByAsc(CcrProductRateLimit::getProductCode)
                .orderByDesc(CcrProductRateLimit::getCreateTime)));
    }

    /** 新增产品硬边界草稿 */
    @PostMapping("/thresholds/product-limit")
    public R<Long> createProductLimit(@RequestBody CcrProductRateLimit limit) {
        if (StrUtil.isBlank(limit.getProductCode()) || StrUtil.isBlank(limit.getBusinessType())
                || limit.getHardBoundaryRate() == null || limit.getEffectiveFrom() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "产品编码/业务类型/硬边界利率/生效时间必填");
        }
        if (!"LOAN".equals(limit.getBusinessType()) && !"DEPOSIT".equals(limit.getBusinessType())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "业务类型仅支持 LOAN/DEPOSIT");
        }
        if (limit.getHardBoundaryRate().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "硬边界利率必须大于0");
        }
        limit.setId(null);
        limit.setStatus("DRAFT");
        limit.setRateDirection("LOAN".equals(limit.getBusinessType()) ? "LOWER_BETTER" : "HIGHER_BETTER");
        limit.setPublishBy(null);
        limit.setReviewBy(null);
        limit.setPublishTime(null);
        productRateLimitMapper.insert(limit);
        configChangeLogService.record(ConfigChangeLogService.TYPE_PRODUCT_LIMIT, limit.getId(), limit.getVersionNo(),
                ConfigChangeLogService.ACTION_CREATE, null, limit, null);
        return R.ok(limit.getId());
    }

    /** 产品硬边界送审:DRAFT → REVIEW */
    @PostMapping("/thresholds/product-limit/{id}/submit")
    public R<Void> submitProductLimit(@PathVariable Long id) {
        CcrProductRateLimit limit = productRateLimitMapper.selectById(id);
        if (limit == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品边界不存在");
        }
        if (!"DRAFT".equals(limit.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅草稿状态可送审(当前:" + limit.getStatus() + ")");
        }
        String oldJson = JSONUtil.toJsonStr(limit);
        limit.setStatus("REVIEW");
        productRateLimitMapper.updateById(limit);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT_LIMIT, id, limit.getVersionNo(),
                ConfigChangeLogService.ACTION_SUBMIT, oldJson, JSONUtil.toJsonStr(limit), null);
        return R.ok();
    }

    /** 产品硬边界复核发布:REVIEW → EFFECTIVE;双人复核;同产品同业务类型旧生效版本自动停用 */
    @PostMapping("/thresholds/product-limit/{id}/publish")
    public R<Void> publishProductLimit(@PathVariable Long id) {
        CcrProductRateLimit limit = productRateLimitMapper.selectById(id);
        if (limit == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品边界不存在");
        }
        if (!"REVIEW".equals(limit.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅待复核状态可发布(当前:" + limit.getStatus() + ")");
        }
        long currentUser = StpUtil.getLoginIdAsLong();
        if (limit.getCreateBy() != null && limit.getCreateBy() == currentUser) {
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "发布人与创建人不得为同一人(双人复核)");
        }
        // 同产品同业务类型旧生效版本自动停用
        List<CcrProductRateLimit> oldEffective = productRateLimitMapper.selectList(
                new LambdaQueryWrapper<CcrProductRateLimit>()
                        .eq(CcrProductRateLimit::getStatus, "EFFECTIVE")
                        .eq(CcrProductRateLimit::getProductCode, limit.getProductCode())
                        .eq(CcrProductRateLimit::getBusinessType, limit.getBusinessType())
                        .ne(CcrProductRateLimit::getId, id));
        for (CcrProductRateLimit old : oldEffective) {
            String oldJson = JSONUtil.toJsonStr(old);
            old.setStatus("INVALID");
            old.setEffectiveTo(limit.getEffectiveFrom());
            productRateLimitMapper.updateById(old);
            configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT_LIMIT, old.getId(),
                    old.getVersionNo(), ConfigChangeLogService.ACTION_DISABLE, oldJson, JSONUtil.toJsonStr(old),
                    "新版本发布,旧生效版本自动停用");
        }
        String oldJson = JSONUtil.toJsonStr(limit);
        limit.setStatus("EFFECTIVE");
        limit.setPublishBy(currentUser);
        limit.setReviewBy(currentUser);
        limit.setPublishTime(LocalDateTime.now());
        productRateLimitMapper.updateById(limit);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT_LIMIT, id, limit.getVersionNo(),
                ConfigChangeLogService.ACTION_PUBLISH, oldJson, JSONUtil.toJsonStr(limit), null);
        return R.ok();
    }

    /** 产品硬边界复核驳回:REVIEW → DRAFT(必填驳回意见,§8A.2) */
    @PostMapping("/thresholds/product-limit/{id}/reject")
    public R<Void> rejectProductLimit(@PathVariable Long id, @RequestParam String opinion) {
        CcrProductRateLimit limit = productRateLimitMapper.selectById(id);
        if (limit == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品边界不存在");
        }
        if (!"REVIEW".equals(limit.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅待复核状态可驳回(当前:" + limit.getStatus() + ")");
        }
        if (StrUtil.isBlank(opinion)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "驳回意见必填");
        }
        String oldJson = JSONUtil.toJsonStr(limit);
        limit.setStatus("DRAFT");
        productRateLimitMapper.updateById(limit);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT_LIMIT, id, limit.getVersionNo(),
                ConfigChangeLogService.ACTION_REJECT, oldJson, JSONUtil.toJsonStr(limit), opinion);
        return R.ok();
    }

    /** 产品硬边界停用:EFFECTIVE → INVALID */
    @PostMapping("/thresholds/product-limit/{id}/disable")
    public R<Void> disableProductLimit(@PathVariable Long id) {
        CcrProductRateLimit limit = productRateLimitMapper.selectById(id);
        if (limit == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品边界不存在");
        }
        if (!"EFFECTIVE".equals(limit.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅已生效状态可停用(当前:" + limit.getStatus() + ")");
        }
        String oldJson = JSONUtil.toJsonStr(limit);
        limit.setStatus("INVALID");
        limit.setEffectiveTo(LocalDateTime.now());
        productRateLimitMapper.updateById(limit);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT_LIMIT, id, limit.getVersionNo(),
                ConfigChangeLogService.ACTION_DISABLE, oldJson, JSONUtil.toJsonStr(limit), null);
        return R.ok();
    }

    // ---------- 配置变更审计(§8A.2) ----------

    /** 配置变更日志查询(审计用;可按配置域/记录主键过滤) */
    @GetMapping("/thresholds/change-log")
    public R<List<CcrConfigChangeLog>> changeLogList(@RequestParam(required = false) String configType,
                                                     @RequestParam(required = false) Long configId) {
        return R.ok(configChangeLogMapper.selectList(new LambdaQueryWrapper<CcrConfigChangeLog>()
                .eq(StrUtil.isNotBlank(configType), CcrConfigChangeLog::getConfigType, configType)
                .eq(configId != null, CcrConfigChangeLog::getConfigId, configId)
                .orderByDesc(CcrConfigChangeLog::getOperateTime)));
    }

    // ---------- 发布前校验(§8A.3/§8A.4) ----------

    /**
     * LPR 发布/新增校验(§8A.3):取值 0.5%–8%;0.05 的整数倍(LPR 报价规则);
     * effective_from ≥ 发布日;同一生效日仅一版(草稿/待复核/生效均占用)
     */
    private void validateLpr(CcrLprVersion lpr, Long excludeId) {
        validateLprValue("一年期LPR", lpr.getLpr1y());
        validateLprValue("五年期以上LPR", lpr.getLpr5y());
        LocalDate effectiveDate = lpr.getEffectiveFrom().toLocalDate();
        if (effectiveDate.isBefore(LocalDate.now())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "生效时间" + effectiveDate + "不得早于发布日" + LocalDate.now());
        }
        List<CcrLprVersion> occupied = lprVersionMapper.selectList(new LambdaQueryWrapper<CcrLprVersion>()
                .in(CcrLprVersion::getStatus, "DRAFT", "REVIEW", "EFFECTIVE"));
        for (CcrLprVersion other : occupied) {
            if (other.getId().equals(lpr.getId()) || other.getId().equals(excludeId)) {
                continue;
            }
            if (other.getEffectiveFrom() != null && other.getEffectiveFrom().toLocalDate().equals(effectiveDate)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "同一生效日仅允许一版:" + effectiveDate + "已被版本" + other.getVersionCode() + "占用");
            }
        }
    }

    /** LPR 单值校验:0.5%–8% 且为 0.05 的整数倍 */
    private void validateLprValue(String name, BigDecimal value) {
        if (value == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), name + "必填");
        }
        if (value.compareTo(LPR_MIN) < 0 || value.compareTo(LPR_MAX) > 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    name + "取值须为0.5%–8%(当前:" + value + "%)");
        }
        if (value.remainder(LPR_STEP).compareTo(BigDecimal.ZERO) != 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    name + "须为0.05的整数倍(LPR报价规则,当前:" + value + "%)");
        }
    }

    /**
     * 矩阵边界互斥必填校验(§8A.4 校验3):
     * boundary_type=RATE 时 boundary_bp(LPR±BP)与 boundary_min_rate(绝对利率)必须且只能填一项,
     * 按 BP 配置时 bp_sign/lpr_term 必填;boundary_type=SPREAD(存量降幅)时 boundary_bp 必填,
     * boundary_min_rate 作绝对下限可选
     */
    private void validateMatrixBoundary(CcrRateMatrix row) {
        if ("RATE".equals(row.getBoundaryType())) {
            boolean hasBp = row.getBoundaryBp() != null;
            boolean hasRate = row.getBoundaryMinRate() != null;
            if (hasBp == hasRate) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "boundary_type=RATE 时 boundary_bp 与 boundary_min_rate 必须且只能填一项(互斥必填)");
            }
            if (hasBp && (StrUtil.isBlank(row.getBpSign()) || StrUtil.isBlank(row.getLprTerm()))) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "按 LPR±BP 配置边界时 bp_sign 与 lpr_term 必填");
            }
            return;
        }
        if ("SPREAD".equals(row.getBoundaryType())) {
            if (row.getBoundaryBp() == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "boundary_type=SPREAD 时 boundary_bp(最大可降BP)必填");
            }
            return;
        }
        throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                "boundary_type 仅支持 RATE/SPREAD(当前:" + row.getBoundaryType() + ")");
    }

    /**
     * 矩阵行发布前校验(§8A.4):边界互斥必填;同条件组优先级唯一(防命中歧义);
     * 贷款场景矩阵下限不得低于产品硬边界(能查到产品边界时校验,硬边界不可被矩阵突破)
     */
    private void validateMatrixForPublish(CcrRateMatrix row) {
        validateMatrixBoundary(row);
        // 同维度同岗位区间/边界不交叠:同条件组内生效行优先级必须唯一(§8A.4 校验1/2)
        List<CcrRateMatrix> effective = rateMatrixMapper.selectList(new LambdaQueryWrapper<CcrRateMatrix>()
                .eq(CcrRateMatrix::getStatus, "EFFECTIVE")
                .eq(CcrRateMatrix::getBusinessBigType, row.getBusinessBigType())
                .eq(CcrRateMatrix::getNewOrExisting, row.getNewOrExisting())
                .ne(CcrRateMatrix::getId, row.getId()));
        for (CcrRateMatrix other : effective) {
            if (!sameConditionGroup(other, row) || sameDimension(other, row)) {
                // 不同条件组不冲突;同维度同岗位旧行发布后自动停用,不构成冲突
                continue;
            }
            if (Objects.equals(other.getPriority() == null ? 0 : other.getPriority(),
                    row.getPriority() == null ? 0 : row.getPriority())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "同条件组优先级" + row.getPriority() + "与生效行" + other.getMatrixNo()
                                + "冲突,发布将导致同优先级多匹配,禁止发布");
            }
        }
        // 矩阵下限不得低于产品硬边界(贷款;能查到产品边界时校验,§8A.4 校验5)
        if (row.getBusinessBigType() != null && row.getBusinessBigType().startsWith("LOAN")
                && StrUtil.isNotBlank(row.getProductCode()) && row.getBoundaryMinRate() != null) {
            LocalDateTime now = LocalDateTime.now();
            CcrProductRateLimit limit = productRateLimitMapper.selectOne(new LambdaQueryWrapper<CcrProductRateLimit>()
                    .eq(CcrProductRateLimit::getStatus, "EFFECTIVE")
                    .eq(CcrProductRateLimit::getProductCode, row.getProductCode())
                    .eq(CcrProductRateLimit::getBusinessType, "LOAN")
                    .le(CcrProductRateLimit::getEffectiveFrom, now)
                    .and(w -> w.isNull(CcrProductRateLimit::getEffectiveTo)
                            .or().gt(CcrProductRateLimit::getEffectiveTo, now))
                    .orderByDesc(CcrProductRateLimit::getEffectiveFrom)
                    .last("limit 1"));
            if (limit != null && row.getBoundaryMinRate().compareTo(limit.getHardBoundaryRate()) < 0) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "矩阵下限" + row.getBoundaryMinRate() + "%低于产品" + row.getProductCode()
                                + "硬边界" + limit.getHardBoundaryRate() + "%(硬边界不可被矩阵突破),禁止发布");
            }
        }
    }

    /** 同条件组判定(不含岗位;§8A.4 命中唯一性校验维度) */
    private boolean sameConditionGroup(CcrRateMatrix a, CcrRateMatrix b) {
        return Objects.equals(a.getCustomerType(), b.getCustomerType())
                && Objects.equals(a.getProductCode(), b.getProductCode())
                && Objects.equals(a.getAmountTier(), b.getAmountTier())
                && Objects.equals(a.getTermTier(), b.getTermTier())
                && Objects.equals(a.getGuaranteeType(), b.getGuaranteeType());
    }

    /** 同维度判定(空值安全;岗位不同不算同维度,链路上各岗位行并存) */
    private boolean sameDimension(CcrRateMatrix a, CcrRateMatrix b) {
        return sameConditionGroup(a, b)
                && Objects.equals(a.getStartNodeCode(), b.getStartNodeCode());
    }
}
