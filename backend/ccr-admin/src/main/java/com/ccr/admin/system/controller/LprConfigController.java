package com.ccr.admin.system.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrLprConfig;
import com.ccr.rule.domain.CcrLprVersion;
import com.ccr.rule.domain.CcrProduct;
import com.ccr.rule.mapper.CcrLprConfigMapper;
import com.ccr.rule.mapper.CcrLprVersionMapper;
import com.ccr.rule.mapper.CcrProductMapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LPR 明细管理(§8A.3/§10.3.17):按指标(1Y/5Y+)×产品逐行维护,替换单行两列模型
 * 同一版本内 (lpr_term, product_type) 唯一;lpr_value 0.5%–8% 且 0.05 整数倍(报价规则);
 * product_type 对应产品编码(ccr_product.product_code,必须启用);
 * 保存为按版本全量替换(矩阵式编辑),发布时随 LPR 版本冻结,路由按 (lpr_term, product_type) 精确取值。
 */
@RestController
@RequestMapping("/system/lpr-configs")
public class LprConfigController {

    private static final BigDecimal LPR_MIN = new BigDecimal("0.5");
    private static final BigDecimal LPR_MAX = new BigDecimal("8");
    private static final Set<String> LPR_TERMS = Set.of("1Y", "5Y+");

    @Resource
    private CcrLprConfigMapper lprConfigMapper;

    @Resource
    private CcrLprVersionMapper lprVersionMapper;

    @Resource
    private CcrProductMapper productMapper;

    /** 查询版本明细(按期限/产品排序,矩阵式编辑回显) */
    @GetMapping
    public R<List<CcrLprConfig>> list(@RequestParam Long versionId) {
        return R.ok(lprConfigMapper.selectList(new LambdaQueryWrapper<CcrLprConfig>()
                .eq(CcrLprConfig::getVersionId, versionId)
                .orderByAsc(CcrLprConfig::getLprTerm, CcrLprConfig::getProductType)));
    }

    /** 批量保存明细:按版本全量替换;校验产品启用/(lpr_term,product_type)唯一/LPR 取值区间与 0.05 整数倍 */
    @PostMapping
    public R<Void> save(@RequestBody List<CcrLprConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "明细不能为空");
        }
        Long versionId = configs.get(0).getVersionId();
        if (versionId == null || lprVersionMapper.selectById(versionId) == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "LPR版本不存在");
        }
        Set<String> seen = new HashSet<>();
        for (CcrLprConfig cfg : configs) {
            if (cfg.getVersionId() == null || !versionId.equals(cfg.getVersionId())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "明细版本不一致");
            }
            if (StrUtil.isBlank(cfg.getLprTerm()) || !LPR_TERMS.contains(cfg.getLprTerm())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "LPR期限仅支持 1Y/5Y+");
            }
            if (StrUtil.isBlank(cfg.getProductType())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "产品类型必填");
            }
            String key = cfg.getLprTerm() + "|" + cfg.getProductType();
            if (!seen.add(key)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "同一版本内 (lpr_term, product_type) 重复:" + key);
            }
            if (cfg.getLprValue() == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "LPR值必填");
            }
            validateLprValue(cfg.getLprValue());
            // 产品必须存在且启用(权威来源 §8A.5①)
            CcrProduct product = productMapper.selectOne(new LambdaQueryWrapper<CcrProduct>()
                    .eq(CcrProduct::getProductCode, cfg.getProductType()));
            if (product == null || !"ENABLED".equals(product.getStatus())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "产品未启用或不存在:" + cfg.getProductType());
            }
            cfg.setId(null);
        }
        // 全量替换(物理删除旧明细,规避逻辑删除占用唯一键)
        lprConfigMapper.physicalDeleteByVersionId(versionId);
        for (CcrLprConfig cfg : configs) {
            lprConfigMapper.insert(cfg);
        }
        return R.ok();
    }

    /** LPR 取值校验(§8A.3):0.5%–8% 区间且 0.05 整数倍(LPR 报价规则) */
    private void validateLprValue(BigDecimal value) {
        if (value.compareTo(LPR_MIN) < 0 || value.compareTo(LPR_MAX) > 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "LPR取值需在 0.5%–8% 区间");
        }
        // 0.05 整数倍:value*100 为 5 的整数倍
        if (value.multiply(new BigDecimal("100")).remainder(new BigDecimal("5")).compareTo(BigDecimal.ZERO) != 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "LPR取值须为 0.05 的整数倍");
        }
    }
}
