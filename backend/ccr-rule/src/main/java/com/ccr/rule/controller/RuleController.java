package com.ccr.rule.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.cache.CcrCacheUtil;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrLprVersion;
import com.ccr.rule.domain.CcrRateRuleSet;
import com.ccr.rule.dto.MatrixRouteInput;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.dto.RuleInput;
import com.ccr.rule.engine.RuleEngine;
import com.ccr.rule.mapper.CcrLprVersionMapper;
import com.ccr.rule.mapper.CcrRateRuleSetMapper;
import com.ccr.rule.service.ConfigChangeLogService;
import com.ccr.rule.service.RateMatrixRouter;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 利率规则引擎接口(§8.3 路由预览 / 发布前校验)
 * 规则集版本生命周期(§8.4/§8A.2):草稿(DRAFT)→送审(REVIEW)→复核发布(EFFECTIVE)→停用(INVALID);
 * 复核驳回(REVIEW→DRAFT,必填驳回意见);发布强制双人复核(reviewBy≠createBy);
 * 已生效记录禁止原位修改,只能新建版本;全部变更写 ccr_config_change_log 审计(§8A.2)
 */
@RestController
@RequestMapping("/ccr/rule")
public class RuleController {

    @Resource
    private RuleEngine ruleEngine;

    @Resource
    private RateMatrixRouter rateMatrixRouter;

    @Resource
    private CcrRateRuleSetMapper ruleSetMapper;

    @Resource
    private CcrLprVersionMapper lprVersionMapper;

    @Resource
    private ConfigChangeLogService configChangeLogService;

    @Resource
    private CcrCacheUtil cacheUtil;

    /** 路由试算:给定冻结规则集与业务维度,返回唯一路由 */
    @PostMapping("/route-preview")
    public R<RouteResult> routePreview(@RequestParam Long ruleSetId, @RequestBody RuleInput input) {
        return R.ok(ruleEngine.calcRoute(ruleSetId, input));
    }

    /** 区间连续性校验(发布前自动化测试) */
    @GetMapping("/continuity")
    public R<String> continuity(@RequestParam Long ruleSetId) {
        String issue = ruleEngine.validateContinuity(ruleSetId);
        return R.ok(issue == null ? "校验通过:区间连续、无空档、无重叠" : issue);
    }

    /** PRD V2 §7.2 权限矩阵路由(LPR±BP,逐担保类型;支持 lprVersionId/asOfDate 冻结重算) */
    @PostMapping("/matrix-route")
    public R<RouteResult> matrixRoute(@RequestBody MatrixRouteInput input) {
        return R.ok(rateMatrixRouter.calcRoute(input));
    }

    /**
     * 查询当前生效的 LPR 版本(供 Wave2 提交链路冻结:保存 id + 当前日期,
     * 后续路由重算以 lprVersionId/asOfDate 入参沿用冻结版本)
     */
    @GetMapping("/version/current")
    public R<CcrLprVersion> currentVersion() {
        Object cached = cacheUtil.get(CcrCacheUtil.KEY_LPR_EFFECTIVE);
        if (cached instanceof CcrLprVersion lpr) {
            return R.ok(lpr);
        }
        CcrLprVersion lpr = lprVersionMapper.selectOne(new LambdaQueryWrapper<CcrLprVersion>()
                .eq(CcrLprVersion::getStatus, "EFFECTIVE")
                .le(CcrLprVersion::getEffectiveFrom, LocalDateTime.now())
                .and(w -> w.isNull(CcrLprVersion::getEffectiveTo)
                        .or().gt(CcrLprVersion::getEffectiveTo, LocalDateTime.now()))
                .orderByDesc(CcrLprVersion::getEffectiveFrom)
                .last("limit 1"));
        if (lpr == null) {
            throw new ServiceException(ErrorCode.LPR_NOT_EFFECTIVE.getCode(), "当前无生效的LPR版本");
        }
        cacheUtil.set(CcrCacheUtil.KEY_LPR_EFFECTIVE, lpr);
        return R.ok(lpr);
    }

    /** 规则集列表(含各状态,版本管理用) */
    @GetMapping("/set/list")
    public R<List<CcrRateRuleSet>> setList(@RequestParam(required = false) String status) {
        return R.ok(ruleSetMapper.selectList(new LambdaQueryWrapper<CcrRateRuleSet>()
                .eq(StrUtil.isNotBlank(status), CcrRateRuleSet::getStatus, status)
                .orderByDesc(CcrRateRuleSet::getCreateTime)));
    }

    /** 新增规则集草稿(已生效记录禁止原位修改,只能新建版本) */
    @PostMapping("/set")
    public R<Long> createSet(@RequestBody CcrRateRuleSet ruleSet) {
        if (StrUtil.isBlank(ruleSet.getSetCode()) || StrUtil.isBlank(ruleSet.getSetName())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "规则集编码/名称必填");
        }
        ruleSet.setId(null);
        ruleSet.setStatus("DRAFT");
        ruleSet.setPublishBy(null);
        ruleSet.setReviewBy(null);
        ruleSet.setPublishTime(null);
        ruleSetMapper.insert(ruleSet);
        configChangeLogService.record(ConfigChangeLogService.TYPE_RULE_SET, ruleSet.getId(), ruleSet.getVersionNo(),
                ConfigChangeLogService.ACTION_CREATE, null, ruleSet, null);
        return R.ok(ruleSet.getId());
    }

    /** 送审:DRAFT → REVIEW */
    @PostMapping("/set/{id}/submit")
    public R<Void> submitSet(@PathVariable Long id) {
        CcrRateRuleSet ruleSet = ruleSetMapper.selectById(id);
        if (ruleSet == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "规则集不存在");
        }
        if (!"DRAFT".equals(ruleSet.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅草稿状态可送审(当前:" + ruleSet.getStatus() + ")");
        }
        String oldJson = JSONUtil.toJsonStr(ruleSet);
        ruleSet.setStatus("REVIEW");
        ruleSetMapper.updateById(ruleSet);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_RULE_SET, id, ruleSet.getVersionNo(),
                ConfigChangeLogService.ACTION_SUBMIT, oldJson, JSONUtil.toJsonStr(ruleSet), null);
        return R.ok();
    }

    /** 复核发布:REVIEW → EFFECTIVE;强制双人复核;发布前必须通过连续性校验;同维度旧生效版本自动停用 */
    @PostMapping("/set/{id}/publish")
    public R<Void> publishSet(@PathVariable Long id) {
        CcrRateRuleSet ruleSet = ruleSetMapper.selectById(id);
        if (ruleSet == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "规则集不存在");
        }
        if (!"REVIEW".equals(ruleSet.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅待复核状态可发布(当前:" + ruleSet.getStatus() + ")");
        }
        long currentUser = StpUtil.getLoginIdAsLong();
        if (ruleSet.getCreateBy() != null && ruleSet.getCreateBy() == currentUser) {
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "发布人与创建人不得为同一人(双人复核)");
        }
        String issue = ruleEngine.validateContinuity(id);
        if (issue != null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "连续性校验未通过,禁止发布:" + issue);
        }
        // 全局仅允许一个生效规则集:发布新版本时旧生效版本自动停用
        List<CcrRateRuleSet> oldEffective = ruleSetMapper.selectList(new LambdaQueryWrapper<CcrRateRuleSet>()
                .eq(CcrRateRuleSet::getStatus, "EFFECTIVE")
                .ne(CcrRateRuleSet::getId, id));
        for (CcrRateRuleSet old : oldEffective) {
            String oldJson = JSONUtil.toJsonStr(old);
            old.setStatus("INVALID");
            old.setEffectiveTo(ruleSet.getEffectiveFrom() == null ? LocalDateTime.now() : ruleSet.getEffectiveFrom());
            ruleSetMapper.updateById(old);
            configChangeLogService.recordJson(ConfigChangeLogService.TYPE_RULE_SET, old.getId(), old.getVersionNo(),
                    ConfigChangeLogService.ACTION_DISABLE, oldJson, JSONUtil.toJsonStr(old),
                    "新版本" + ruleSet.getSetCode() + "发布,旧生效版本自动停用");
        }
        String oldJson = JSONUtil.toJsonStr(ruleSet);
        ruleSet.setStatus("EFFECTIVE");
        ruleSet.setPublishBy(currentUser);
        ruleSet.setReviewBy(currentUser);
        ruleSet.setPublishTime(LocalDateTime.now());
        if (ruleSet.getEffectiveFrom() == null) {
            ruleSet.setEffectiveFrom(LocalDateTime.now());
        }
        ruleSetMapper.updateById(ruleSet);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_RULE_SET, id, ruleSet.getVersionNo(),
                ConfigChangeLogService.ACTION_PUBLISH, oldJson, JSONUtil.toJsonStr(ruleSet), null);
        // 配置发布信号(§3.6):规则集无域缓存 key,仅递增全局版本号供前端/下游感知配置变更
        cacheUtil.increment(CcrCacheUtil.GLOBAL_VER_KEY);
        return R.ok();
    }

    /** 复核驳回:REVIEW → DRAFT(必填驳回意见,§8A.2) */
    @PostMapping("/set/{id}/reject")
    public R<Void> rejectSet(@PathVariable Long id, @RequestParam String opinion) {
        CcrRateRuleSet ruleSet = ruleSetMapper.selectById(id);
        if (ruleSet == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "规则集不存在");
        }
        if (!"REVIEW".equals(ruleSet.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅待复核状态可驳回(当前:" + ruleSet.getStatus() + ")");
        }
        if (StrUtil.isBlank(opinion)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "驳回意见必填");
        }
        String oldJson = JSONUtil.toJsonStr(ruleSet);
        ruleSet.setStatus("DRAFT");
        ruleSetMapper.updateById(ruleSet);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_RULE_SET, id, ruleSet.getVersionNo(),
                ConfigChangeLogService.ACTION_REJECT, oldJson, JSONUtil.toJsonStr(ruleSet), opinion);
        return R.ok();
    }

    /** 停用:EFFECTIVE → INVALID */
    @PostMapping("/set/{id}/disable")
    public R<Void> disableSet(@PathVariable Long id) {
        CcrRateRuleSet ruleSet = ruleSetMapper.selectById(id);
        if (ruleSet == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "规则集不存在");
        }
        if (!"EFFECTIVE".equals(ruleSet.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅已生效状态可停用(当前:" + ruleSet.getStatus() + ")");
        }
        String oldJson = JSONUtil.toJsonStr(ruleSet);
        ruleSet.setStatus("INVALID");
        ruleSet.setEffectiveTo(LocalDateTime.now());
        ruleSetMapper.updateById(ruleSet);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_RULE_SET, id, ruleSet.getVersionNo(),
                ConfigChangeLogService.ACTION_DISABLE, oldJson, JSONUtil.toJsonStr(ruleSet), null);
        // 配置发布信号(§3.6):停用同样递增全局版本号,感知配置变更
        cacheUtil.increment(CcrCacheUtil.GLOBAL_VER_KEY);
        return R.ok();
    }
}
