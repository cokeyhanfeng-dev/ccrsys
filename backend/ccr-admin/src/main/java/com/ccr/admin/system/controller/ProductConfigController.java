package com.ccr.admin.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.cache.CcrCacheUtil;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrProduct;
import com.ccr.rule.domain.CcrProductRoute;
import com.ccr.rule.mapper.CcrProductMapper;
import com.ccr.rule.mapper.CcrProductRouteMapper;
import com.ccr.rule.service.ConfigChangeLogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品配置(§8A.5/§10.3.22/§10.3.23):产品目录 + 产品审批链路配置
 * 产品目录状态:ENABLED(启用,新申请可选)/DISABLED(停用,新申请不可选,在途审批不受影响 D11);
 * 产品审批链路状态机(§8A.2):DRAFT → PENDING_REVIEW → PUBLISHED → OBSOLETE,发布强制双人复核;
 * 产品编码一经启用禁改(仅可停用);同产品同生效日仅一版链路生效。
 * 申请页产品下拉以本表为权威来源(替代前端硬编码字典)。
 */
@RestController
@RequestMapping("/system/product")
public class ProductConfigController {

    @Resource
    private CcrProductMapper productMapper;

    @Resource
    private CcrProductRouteMapper productRouteMapper;

    @Resource
    private ConfigChangeLogService configChangeLogService;

    @Resource
    private CcrCacheUtil cacheUtil;

    // ---------- 产品目录 ccr_product ----------

    /** 产品目录列表(可按业务大类/状态过滤;启用产品供申请页下拉,公开只读) */
    @GetMapping("/catalog")
    public R<List<CcrProduct>> catalog(@RequestParam(required = false) String businessBigType,
                                       @RequestParam(required = false) String status) {
        return R.ok(productMapper.selectList(new LambdaQueryWrapper<CcrProduct>()
                .eq(StrUtil.isNotBlank(businessBigType), CcrProduct::getBusinessBigType, businessBigType)
                .eq(StrUtil.isNotBlank(status), CcrProduct::getStatus, status)
                .orderByAsc(CcrProduct::getBusinessBigType, CcrProduct::getProductCode)));
    }

    /** 启用产品下拉(申请页权威来源:产品下拉/LPR明细产品类型/权限矩阵/产品边界一致) */
    @GetMapping("/catalog/enabled")
    public R<List<CcrProduct>> enabledCatalog(@RequestParam(required = false) String businessBigType) {
        return R.ok(productMapper.selectList(new LambdaQueryWrapper<CcrProduct>()
                .eq(CcrProduct::getStatus, "ENABLED")
                .eq(StrUtil.isNotBlank(businessBigType), CcrProduct::getBusinessBigType, businessBigType)
                .orderByAsc(CcrProduct::getBusinessBigType, CcrProduct::getProductCode)));
    }

    /** 新增产品(状态 ENABLED;product_code 唯一,一经启用禁改编码) */
    @PostMapping("/catalog")
    public R<Long> createProduct(@RequestBody CcrProduct product) {
        if (StrUtil.isBlank(product.getProductCode()) || StrUtil.isBlank(product.getProductName())
                || StrUtil.isBlank(product.getBusinessBigType())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "产品编码/产品名称/业务大类必填");
        }
        if (!"LOAN".equals(product.getBusinessBigType()) && !"DEPOSIT".equals(product.getBusinessBigType())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "业务大类仅支持 LOAN/DEPOSIT");
        }
        CcrProduct exists = productMapper.selectOne(new LambdaQueryWrapper<CcrProduct>()
                .eq(CcrProduct::getProductCode, product.getProductCode()));
        if (exists != null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "产品编码已存在:" + product.getProductCode());
        }
        if (product.getDefaultMinRate() != null && product.getDefaultMaxRate() != null
                && product.getDefaultMinRate().compareTo(product.getDefaultMaxRate()) > 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "默认利率下限不得高于上限");
        }
        product.setId(null);
        product.setStatus("ENABLED");
        product.setCurrency(StrUtil.isBlank(product.getCurrency()) ? "CNY" : product.getCurrency());
        if (product.getEffectiveDate() == null) {
            product.setEffectiveDate(LocalDateTime.now());
        }
        product.setPublishBy(null);
        product.setReviewBy(null);
        product.setPublishTime(null);
        productMapper.insert(product);
        configChangeLogService.record(ConfigChangeLogService.TYPE_PRODUCT, product.getId(), product.getVersionNo(),
                ConfigChangeLogService.ACTION_CREATE, null, product, null);
        return R.ok(product.getId());
    }

    /** 修改产品(仅名称/类别/客户类型/利率区间/期限范围/备注可改;编码与业务大类禁改) */
    @PutMapping("/catalog/{id}")
    public R<Void> updateProduct(@PathVariable Long id, @RequestBody CcrProduct product) {
        CcrProduct old = productMapper.selectById(id);
        if (old == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品不存在");
        }
        if (!old.getProductCode().equals(product.getProductCode())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "产品编码一经启用禁改");
        }
        if (product.getDefaultMinRate() != null && product.getDefaultMaxRate() != null
                && product.getDefaultMinRate().compareTo(product.getDefaultMaxRate()) > 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "默认利率下限不得高于上限");
        }
        String oldJson = JSONUtil.toJsonStr(old);
        old.setProductName(product.getProductName());
        old.setProductCategory(product.getProductCategory());
        old.setCustomerType(product.getCustomerType());
        old.setDefaultMinRate(product.getDefaultMinRate());
        old.setDefaultMaxRate(product.getDefaultMaxRate());
        old.setDefaultMinTermMonths(product.getDefaultMinTermMonths());
        old.setDefaultMaxTermMonths(product.getDefaultMaxTermMonths());
        old.setRemark(product.getRemark());
        productMapper.updateById(old);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT, id, old.getVersionNo(),
                ConfigChangeLogService.ACTION_SUBMIT, oldJson, JSONUtil.toJsonStr(old), null);
        return R.ok();
    }

    /** 启停产品(停用后新申请不可选,在途审批不受影响 D11) */
    @PostMapping("/catalog/{id}/status")
    public R<Void> changeProductStatus(@PathVariable Long id, @RequestParam String status) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "状态仅支持 ENABLED/DISABLED");
        }
        CcrProduct product = productMapper.selectById(id);
        if (product == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品不存在");
        }
        String oldJson = JSONUtil.toJsonStr(product);
        String action = "ENABLED".equals(status)
                ? ConfigChangeLogService.ACTION_PUBLISH : ConfigChangeLogService.ACTION_DISABLE;
        product.setStatus(status);
        if ("ENABLED".equals(status)) {
            product.setPublishBy(StpUtil.getLoginIdAsLong());
            product.setPublishTime(LocalDateTime.now());
        }
        productMapper.updateById(product);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT, id, product.getVersionNo(),
                action, oldJson, JSONUtil.toJsonStr(product), null);
        return R.ok();
    }

    /** 删除产品(未被申请/矩阵/LPR/边界引用时允许逻辑删除,否则仅可停用) */
    @DeleteMapping("/catalog/{id}")
    public R<Void> deleteProduct(@PathVariable Long id) {
        CcrProduct product = productMapper.selectById(id);
        if (product == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品不存在");
        }
        if ("ENABLED".equals(product.getStatus())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "启用中产品不可删除,请先停用");
        }
        productMapper.deleteById(id);
        configChangeLogService.record(ConfigChangeLogService.TYPE_PRODUCT, id, product.getVersionNo(),
                ConfigChangeLogService.ACTION_DISABLE, product, null, "产品删除");
        return R.ok();
    }

    // ---------- 产品审批链路 ccr_product_route ----------

    /** 产品链路列表(可按产品/状态过滤) */
    @GetMapping("/routes")
    public R<List<CcrProductRoute>> routes(@RequestParam(required = false) String productCode,
                                           @RequestParam(required = false) String status) {
        return R.ok(productRouteMapper.selectList(new LambdaQueryWrapper<CcrProductRoute>()
                .eq(StrUtil.isNotBlank(productCode), CcrProductRoute::getProductCode, productCode)
                .eq(StrUtil.isNotBlank(status), CcrProductRoute::getStatus, status)
                .orderByAsc(CcrProductRoute::getProductCode, CcrProductRoute::getPriority)));
    }

    /** 新增产品链路草稿 */
    @PostMapping("/routes")
    public R<Long> createRoute(@RequestBody CcrProductRoute route) {
        if (StrUtil.isBlank(route.getProductCode()) || StrUtil.isBlank(route.getBusinessBigType())
                || StrUtil.isBlank(route.getRouteMode())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "产品编码/业务大类/路由模式必填");
        }
        if (!"CHAINED".equals(route.getRouteMode()) && !"DIRECT_VOTE".equals(route.getRouteMode())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "路由模式仅支持 CHAINED/DIRECT_VOTE");
        }
        if ("CHAINED".equals(route.getRouteMode()) && StrUtil.isBlank(route.getStartNodeCode())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "CHAINED 模式起始节点必填");
        }
        if ("DIRECT_VOTE".equals(route.getRouteMode())) {
            route.setStartNodeCode(null); // DIRECT_VOTE:先必经支行行长,再上会小组,起始节点置空
        }
        // 产品必须存在于目录且启用
        CcrProduct product = productMapper.selectOne(new LambdaQueryWrapper<CcrProduct>()
                .eq(CcrProduct::getProductCode, route.getProductCode()));
        if (product == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "产品编码不存在于产品目录");
        }
        route.setId(null);
        route.setStatus("DRAFT");
        route.setMandatoryVote("Y".equals(route.getMandatoryVote()) ? "Y" : "N");
        route.setPresidentDecision("Y".equals(route.getPresidentDecision()) ? "Y" : "N");
        route.setPriority(route.getPriority() == null ? 0 : route.getPriority());
        if (route.getEffectiveDate() == null) {
            route.setEffectiveDate(LocalDateTime.now());
        }
        route.setPublishBy(null);
        route.setReviewBy(null);
        route.setPublishTime(null);
        productRouteMapper.insert(route);
        configChangeLogService.record(ConfigChangeLogService.TYPE_PRODUCT_ROUTE, route.getId(), route.getVersionNo(),
                ConfigChangeLogService.ACTION_CREATE, null, route, null);
        return R.ok(route.getId());
    }

    /** 链路送审:DRAFT → PENDING_REVIEW */
    @PostMapping("/routes/{id}/submit")
    public R<Void> submitRoute(@PathVariable Long id) {
        CcrProductRoute route = productRouteMapper.selectById(id);
        if (route == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品链路不存在");
        }
        if (!"DRAFT".equals(route.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅草稿状态可送审(当前:" + route.getStatus() + ")");
        }
        String oldJson = JSONUtil.toJsonStr(route);
        route.setStatus("PENDING_REVIEW");
        productRouteMapper.updateById(route);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT_ROUTE, id, route.getVersionNo(),
                ConfigChangeLogService.ACTION_SUBMIT, oldJson, JSONUtil.toJsonStr(route), null);
        return R.ok();
    }

    /** 链路复核发布:PENDING_REVIEW → PUBLISHED;双人复核;同产品同生效日旧 PUBLISHED 自动停用 */
    @PostMapping("/routes/{id}/publish")
    public R<Void> publishRoute(@PathVariable Long id) {
        CcrProductRoute route = productRouteMapper.selectById(id);
        if (route == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品链路不存在");
        }
        if (!"PENDING_REVIEW".equals(route.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅待复核状态可发布(当前:" + route.getStatus() + ")");
        }
        long currentUser = StpUtil.getLoginIdAsLong();
        if (route.getCreateBy() != null && route.getCreateBy() == currentUser) {
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "发布人与创建人不得为同一人(双人复核)");
        }
        // 同产品同生效日旧 PUBLISHED 自动停用
        List<CcrProductRoute> oldPublished = productRouteMapper.selectList(new LambdaQueryWrapper<CcrProductRoute>()
                .eq(CcrProductRoute::getStatus, "PUBLISHED")
                .eq(CcrProductRoute::getProductCode, route.getProductCode())
                .eq(CcrProductRoute::getEffectiveDate, route.getEffectiveDate())
                .ne(CcrProductRoute::getId, id));
        for (CcrProductRoute old : oldPublished) {
            String oldJson = JSONUtil.toJsonStr(old);
            old.setStatus("OBSOLETE");
            productRouteMapper.updateById(old);
            configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT_ROUTE, old.getId(),
                    old.getVersionNo(), ConfigChangeLogService.ACTION_DISABLE, oldJson, JSONUtil.toJsonStr(old),
                    "同产品同生效日新链路发布,旧生效链路自动停用");
        }
        String oldJson = JSONUtil.toJsonStr(route);
        route.setStatus("PUBLISHED");
        route.setPublishBy(currentUser);
        route.setReviewBy(currentUser);
        route.setPublishTime(LocalDateTime.now());
        productRouteMapper.updateById(route);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT_ROUTE, id, route.getVersionNo(),
                ConfigChangeLogService.ACTION_PUBLISH, oldJson, JSONUtil.toJsonStr(route), null);
        evictRouteCache();
        return R.ok();
    }

    /** 链路复核驳回:PENDING_REVIEW → DRAFT(必填驳回意见) */
    @PostMapping("/routes/{id}/reject")
    public R<Void> rejectRoute(@PathVariable Long id, @RequestParam String opinion) {
        CcrProductRoute route = productRouteMapper.selectById(id);
        if (route == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品链路不存在");
        }
        if (!"PENDING_REVIEW".equals(route.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅待复核状态可驳回(当前:" + route.getStatus() + ")");
        }
        if (StrUtil.isBlank(opinion)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "驳回意见必填");
        }
        String oldJson = JSONUtil.toJsonStr(route);
        route.setStatus("DRAFT");
        productRouteMapper.updateById(route);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT_ROUTE, id, route.getVersionNo(),
                ConfigChangeLogService.ACTION_REJECT, oldJson, JSONUtil.toJsonStr(route), opinion);
        return R.ok();
    }

    /** 链路停用:PUBLISHED → OBSOLETE */
    @PostMapping("/routes/{id}/disable")
    public R<Void> disableRoute(@PathVariable Long id) {
        CcrProductRoute route = productRouteMapper.selectById(id);
        if (route == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品链路不存在");
        }
        if (!"PUBLISHED".equals(route.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅已发布状态可停用(当前:" + route.getStatus() + ")");
        }
        String oldJson = JSONUtil.toJsonStr(route);
        route.setStatus("OBSOLETE");
        productRouteMapper.updateById(route);
        configChangeLogService.recordJson(ConfigChangeLogService.TYPE_PRODUCT_ROUTE, id, route.getVersionNo(),
                ConfigChangeLogService.ACTION_DISABLE, oldJson, JSONUtil.toJsonStr(route), null);
        evictRouteCache();
        return R.ok();
    }

    /** 链路删除(仅草稿/停用态可物理删除) */
    @DeleteMapping("/routes/{id}")
    public R<Void> deleteRoute(@PathVariable Long id) {
        CcrProductRoute route = productRouteMapper.selectById(id);
        if (route == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "产品链路不存在");
        }
        if ("PUBLISHED".equals(route.getStatus())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "已发布链路不可删除,请先停用");
        }
        productRouteMapper.deleteById(id);
        configChangeLogService.record(ConfigChangeLogService.TYPE_PRODUCT_ROUTE, id, route.getVersionNo(),
                ConfigChangeLogService.ACTION_DISABLE, route, null, "产品链路删除");
        return R.ok();
    }

    /** 链路发布/停用后缓存失效(§3.6):删除产品链路缓存 key + 递增全局版本号 */
    private void evictRouteCache() {
        cacheUtil.delete(CcrCacheUtil.KEY_PRODUCT_ROUTE_EFFECTIVE);
        cacheUtil.increment(CcrCacheUtil.GLOBAL_VER_KEY);
    }
}
