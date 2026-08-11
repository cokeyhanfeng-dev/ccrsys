package com.ccr.admin.system.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.core.domain.R;
import com.ccr.rule.domain.CcrProduct;
import com.ccr.rule.domain.CcrProductRateLimit;
import com.ccr.rule.mapper.CcrProductMapper;
import com.ccr.rule.mapper.CcrProductRateLimitMapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 产品目录公开只读接口(P2-4)
 * 申请页产品下拉/LPR 明细产品类型以 ccr_product 为权威来源,
 * 但 /system/product/** 仅 admin 可访问,客户经理无法读取;
 * 故在 /ccr/** 下暴露"启用产品"只读端点(登录即可,任意角色),替代前端硬编码字典。
 */
@RestController
@RequestMapping("/ccr/products")
public class ProductCatalogPublicController {

    @Resource
    private CcrProductMapper productMapper;

    @Resource
    private CcrProductRateLimitMapper productRateLimitMapper;

    /** 启用产品列表(可按业务大类过滤;申请页下拉/明细产品类型的权威来源) */
    @GetMapping("/enabled")
    public R<List<CcrProduct>> enabled(@RequestParam(required = false) String businessBigType) {
        return R.ok(productMapper.selectList(new LambdaQueryWrapper<CcrProduct>()
                .eq(CcrProduct::getStatus, "ENABLED")
                .eq(StrUtil.isNotBlank(businessBigType), CcrProduct::getBusinessBigType, businessBigType)
                .orderByAsc(CcrProduct::getBusinessBigType, CcrProduct::getProductCode)));
    }

    /**
     * 产品硬边界公开只读(登录即可,任意角色)
     * 申请页产品标准上限/硬边界展示需要,但 /system/flow/thresholds/** 仅 admin/config_reviewer 可访问,
     * 客户经理访问会 403「无权限」(P2-4 产品目录同类问题的延续);故在 /ccr/** 下暴露只读端点。
     */
    @GetMapping("/rate-limits")
    public R<List<CcrProductRateLimit>> rateLimits(@RequestParam(required = false) String status) {
        return R.ok(productRateLimitMapper.selectList(new LambdaQueryWrapper<CcrProductRateLimit>()
                .eq(StrUtil.isNotBlank(status), CcrProductRateLimit::getStatus, status)
                .orderByAsc(CcrProductRateLimit::getProductCode)
                .orderByDesc(CcrProductRateLimit::getCreateTime)));
    }
}
