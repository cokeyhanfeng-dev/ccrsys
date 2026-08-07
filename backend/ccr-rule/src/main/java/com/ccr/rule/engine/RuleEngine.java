package com.ccr.rule.engine;

import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.dto.RuleInput;

import java.math.BigDecimal;

/**
 * 利率规则引擎(§8)
 * - 唯一路由计算
 * - 区间连续性校验(发布前自动化测试)
 * - 业务硬边界校验
 */
public interface RuleEngine {

    /**
     * 计算唯一路由(§8.3)
     *
     * @param ruleSetId 冻结的规则集版本
     * @param input     业务维度输入
     * @return 唯一路由(起始节点/比较方向/命中规则)
     */
    RouteResult calcRoute(Long ruleSetId, RuleInput input);

    /**
     * 校验规则集金额/期限区间连续、无空档、无重叠(§8.3 步骤4;发布前必须通过)
     *
     * @param ruleSetId 规则集版本
     * @return 连续性问题说明;通过返回 null
     */
    String validateContinuity(Long ruleSetId);

    /**
     * 校验业务硬边界(§8.2):贷款不得低于、存款不得高于
     *
     * @param businessType LOAN / DEPOSIT
     * @param productCode  产品编码
     * @param rate         待校验利率
     * @return 硬边界利率;未配置返回 null
     */
    BigDecimal checkHardBoundary(String businessType, String productCode, BigDecimal rate);
}
