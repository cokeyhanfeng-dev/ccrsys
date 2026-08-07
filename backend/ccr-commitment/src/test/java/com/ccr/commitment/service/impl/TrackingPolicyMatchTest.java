package com.ccr.commitment.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略匹配优先级单测(§11.5):指标+业务+机构 > 指标+业务 > 指标默认 > 全行默认
 */
class TrackingPolicyMatchTest {

    @Test
    void specificityPriority() {
        // 指标+业务+机构
        int full = TrackingPolicyServiceImpl.specificity("M1", "LOAN", "CDZH", "M1", "LOAN", "CDZH");
        // 指标+业务
        int metricBiz = TrackingPolicyServiceImpl.specificity("M1", "LOAN", null, "M1", "LOAN", "CDZH");
        // 指标默认
        int metricOnly = TrackingPolicyServiceImpl.specificity("M1", null, null, "M1", "LOAN", "CDZH");
        // 全行默认
        int global = TrackingPolicyServiceImpl.specificity("*", null, null, "M1", "LOAN", "CDZH");
        assertTrue(full > metricBiz && metricBiz > metricOnly && metricOnly > global);
        assertEquals(7, full);
        assertEquals(6, metricBiz);
        assertEquals(4, metricOnly);
        assertEquals(0, global);
    }

    @Test
    void noMatchCases() {
        // 指标不匹配
        assertEquals(-1, TrackingPolicyServiceImpl.specificity("M2", null, null, "M1", null, null));
        // 业务限定但请求业务不同
        assertEquals(-1, TrackingPolicyServiceImpl.specificity("M1", "LOAN", null, "M1", "DEPOSIT", null));
        // 机构限定但请求机构不同
        assertEquals(-1, TrackingPolicyServiceImpl.specificity("M1", null, "CDZH", "M1", null, "CXZH"));
        // 请求业务为空时,限定业务的策略不匹配
        assertEquals(-1, TrackingPolicyServiceImpl.specificity("M1", "LOAN", null, "M1", null, null));
    }
}
