package com.ccr.rule;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.common.cache.CcrCacheUtil;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrProductRateLimit;
import com.ccr.rule.domain.CcrRateRule;
import com.ccr.rule.domain.CcrRateRuleSet;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.dto.RuleInput;
import com.ccr.rule.engine.impl.RuleEngineImpl;
import com.ccr.rule.mapper.CcrProductRateLimitMapper;
import com.ccr.rule.mapper.CcrRateRuleMapper;
import com.ccr.rule.mapper.CcrRateRuleSetMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 利率规则引擎单元测试(§8.4 规则匹配/优先级/连续性/硬边界)
 * 覆盖:8+ 维度匹配、优先级排序、同优先级多匹配检测、区间连续性、硬边界校验
 */
@ExtendWith(MockitoExtension.class)
class RuleEngineImplTest {

    @Mock
    private CcrRateRuleMapper ruleMapper;
    @Mock
    private CcrRateRuleSetMapper ruleSetMapper;
    @Mock
    private CcrProductRateLimitMapper productRateLimitMapper;
    @Mock
    private CcrCacheUtil cacheUtil;

    @InjectMocks
    private RuleEngineImpl engine;

    @BeforeAll
    static void initTableInfo() {
        // 纯 Mockito 环境无 SqlSessionFactory,需手动初始化实体 TableInfo(Lambda 包装器列解析依赖)
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrRateRule.class);
        TableInfoHelper.initTableInfo(assistant, CcrProductRateLimit.class);
    }

    private CcrRateRuleSet effectiveRuleSet() {
        CcrRateRuleSet set = new CcrRateRuleSet();
        set.setId(1L);
        set.setSetCode("RS001");
        set.setSetName("测试规则集");
        set.setStatus("EFFECTIVE");
        return set;
    }

    private CcrRateRule rule(String code, String startNode, int priority, String businessType) {
        CcrRateRule r = new CcrRateRule();
        r.setId(System.nanoTime());
        r.setSetId(1L);
        r.setRuleCode(code);
        r.setRuleName("规则-" + code);
        r.setBusinessType(businessType);
        r.setStartNodeCode(startNode);
        r.setRateDirection("LOWER_BETTER");
        r.setPriority(priority);
        r.setStatus("ACTIVE");
        return r;
    }

    private RuleInput loanInput() {
        RuleInput input = new RuleInput();
        input.setBusinessType("LOAN");
        input.setProductCode("P001");
        input.setApplyAmount(new BigDecimal("500"));
        return input;
    }

    // ---------- calcRoute:入参校验 ----------

    @Test
    void calcRoute_input为空抛BAD_REQUEST() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.calcRoute(1L, null));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    @Test
    void calcRoute_businessType为空抛BAD_REQUEST() {
        RuleInput input = new RuleInput();
        input.setBusinessType("");

        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.calcRoute(1L, input));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    @Test
    void calcRoute_规则集不存在抛NOT_FOUND() {
        when(ruleSetMapper.selectById(999L)).thenReturn(null);

        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.calcRoute(999L, loanInput()));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void calcRoute_规则集未生效抛RULE_NO_MATCH() {
        CcrRateRuleSet set = effectiveRuleSet();
        set.setStatus("DRAFT");
        when(ruleSetMapper.selectById(1L)).thenReturn(set);

        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.calcRoute(1L, loanInput()));
        assertEquals(ErrorCode.RULE_NO_MATCH.getCode(), e.getCode());
    }

    @Test
    void calcRoute_无匹配规则抛RULE_NO_MATCH() {
        when(ruleSetMapper.selectById(1L)).thenReturn(effectiveRuleSet());
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.calcRoute(1L, loanInput()));
        assertEquals(ErrorCode.RULE_NO_MATCH.getCode(), e.getCode());
    }

    // ---------- calcRoute:正常匹配 ----------

    @Test
    void calcRoute_单条匹配返回正确路由结果() {
        CcrRateRule r = rule("R001", "BRANCH_MANAGER", 10, "LOAN");
        when(ruleSetMapper.selectById(1L)).thenReturn(effectiveRuleSet());
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r));

        RouteResult result = engine.calcRoute(1L, loanInput());

        assertNotNull(result);
        assertEquals("BRANCH_MANAGER", result.getStartNodeCode());
        assertEquals("BRANCH_MANAGER", result.getFinalNodeCode());
        assertEquals(List.of("BRANCH_MANAGER"), result.getRouteChain());
        assertEquals("LOWER_BETTER", result.getRateDirection());
        assertEquals("R001", result.getMatchedRuleCode());
        assertEquals("规则-R001", result.getMatchedRuleName());
        assertNotNull(result.getMessage());
    }

    @Test
    void calcRoute_多条不同优先级取低值优先() {
        CcrRateRule low = rule("R_LOW", "VICE_PRESIDENT", 5, "LOAN");
        CcrRateRule high = rule("R_HIGH", "BRANCH_MANAGER", 20, "LOAN");
        when(ruleSetMapper.selectById(1L)).thenReturn(effectiveRuleSet());
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(high, low));

        RouteResult result = engine.calcRoute(1L, loanInput());

        assertEquals("R_LOW", result.getMatchedRuleCode());
        assertEquals("VICE_PRESIDENT", result.getStartNodeCode());
    }

    @Test
    void calcRoute_同优先级多匹配抛RULE_MULTI_MATCH() {
        CcrRateRule r1 = rule("R001", "BRANCH_MANAGER", 10, "LOAN");
        CcrRateRule r2 = rule("R002", "VICE_PRESIDENT", 10, "LOAN");
        when(ruleSetMapper.selectById(1L)).thenReturn(effectiveRuleSet());
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r1, r2));

        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.calcRoute(1L, loanInput()));
        assertEquals(ErrorCode.RULE_MULTI_MATCH.getCode(), e.getCode());
    }

    @Test
    void calcRoute_priority为null视为0参与排序() {
        CcrRateRule nullPri = rule("R_NULL", "BRANCH_MANAGER", 0, "LOAN");
        nullPri.setPriority(null);
        CcrRateRule normal = rule("R_NORMAL", "VICE_PRESIDENT", 5, "LOAN");
        when(ruleSetMapper.selectById(1L)).thenReturn(effectiveRuleSet());
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(normal, nullPri));

        RouteResult result = engine.calcRoute(1L, loanInput());

        // null priority → 0,低于5 → 优先匹配
        assertEquals("R_NULL", result.getMatchedRuleCode());
    }

    // ---------- calcRoute:维度匹配 ----------

    @Test
    void calcRoute_产品编码不匹配被过滤() {
        CcrRateRule r = rule("R001", "BRANCH_MANAGER", 10, "LOAN");
        r.setProductCode("P999"); // 规则限 P999,输入是 P001 → 不匹配
        when(ruleSetMapper.selectById(1L)).thenReturn(effectiveRuleSet());
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r));

        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.calcRoute(1L, loanInput()));
        assertEquals(ErrorCode.RULE_NO_MATCH.getCode(), e.getCode());
    }

    @Test
    void calcRoute_产品编码为空通配() {
        CcrRateRule r = rule("R001", "BRANCH_MANAGER", 10, "LOAN");
        // productCode=null → 通配
        when(ruleSetMapper.selectById(1L)).thenReturn(effectiveRuleSet());
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r));

        RouteResult result = engine.calcRoute(1L, loanInput());

        assertEquals("R001", result.getMatchedRuleCode());
    }

    @Test
    void calcRoute_金额区间含下界不含上界() {
        CcrRateRule r = rule("R001", "BRANCH_MANAGER", 10, "LOAN");
        r.setAmountMin(new BigDecimal("500"));
        r.setAmountMax(new BigDecimal("1000"));
        when(ruleSetMapper.selectById(1L)).thenReturn(effectiveRuleSet());
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r));

        // 金额=500 → 命中(含下界)
        RuleInput input = loanInput();
        input.setApplyAmount(new BigDecimal("500"));
        assertNotNull(engine.calcRoute(1L, input));

        // 金额=1000 → 不命中(不含上界)
        input.setApplyAmount(new BigDecimal("1000"));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r));
        assertThrows(ServiceException.class, () -> engine.calcRoute(1L, input));
    }

    @Test
    void calcRoute_集团综合授信总额优先于申请金额() {
        CcrRateRule r = rule("R001", "BRANCH_MANAGER", 10, "LOAN");
        r.setAmountMin(new BigDecimal("5000"));
        r.setAmountMax(new BigDecimal("10000"));
        when(ruleSetMapper.selectById(1L)).thenReturn(effectiveRuleSet());

        // 申请金额=100(不匹配),但集团总额=6000(匹配)
        RuleInput input = loanInput();
        input.setApplyAmount(new BigDecimal("100"));
        input.setGroupCreditTotal(new BigDecimal("6000"));

        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r));
        RouteResult result = engine.calcRoute(1L, input);
        assertEquals("R001", result.getMatchedRuleCode());
    }

    @Test
    void calcRoute_amountBasis为APPLY_AMOUNT时按申请金额匹配() {
        CcrRateRule r = rule("R001", "BRANCH_MANAGER", 10, "LOAN");
        r.setAmountMin(new BigDecimal("5000"));
        r.setAmountMax(new BigDecimal("10000"));
        when(ruleSetMapper.selectById(1L)).thenReturn(effectiveRuleSet());

        RuleInput input = loanInput();
        input.setApplyAmount(new BigDecimal("6000")); // 申请金额匹配
        input.setGroupCreditTotal(new BigDecimal("100")); // 集团总额不匹配
        input.setAmountBasis("APPLY_AMOUNT"); // 显式按申请金额

        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r));
        RouteResult result = engine.calcRoute(1L, input);
        assertEquals("R001", result.getMatchedRuleCode());
    }

    // ---------- validateContinuity ----------

    @Test
    void validateContinuity_规则集为空返回提示() {
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        String result = engine.validateContinuity(1L);

        assertEquals("规则集为空,无需校验", result);
    }

    @Test
    void validateContinuity_连续区间返回null() {
        CcrRateRule r1 = rule("R001", "N1", 10, "LOAN");
        r1.setAmountMin(new BigDecimal("0"));
        r1.setAmountMax(new BigDecimal("500"));
        CcrRateRule r2 = rule("R002", "N2", 20, "LOAN");
        r2.setAmountMin(new BigDecimal("500"));
        r2.setAmountMax(new BigDecimal("1000"));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r1, r2));

        assertNull(engine.validateContinuity(1L));
    }

    @Test
    void validateContinuity_区间重叠返回错误() {
        CcrRateRule r1 = rule("R001", "N1", 10, "LOAN");
        r1.setAmountMin(new BigDecimal("0"));
        r1.setAmountMax(new BigDecimal("500"));
        CcrRateRule r2 = rule("R002", "N2", 20, "LOAN");
        r2.setAmountMin(new BigDecimal("400")); // 与 r1 重叠
        r2.setAmountMax(new BigDecimal("1000"));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r1, r2));

        String issue = engine.validateContinuity(1L);
        assertNotNull(issue);
        assertEquals("规则 [R002] 与同维度上一规则金额区间重叠", issue);
    }

    @Test
    void validateContinuity_区间空档返回错误() {
        CcrRateRule r1 = rule("R001", "N1", 10, "LOAN");
        r1.setAmountMin(new BigDecimal("0"));
        r1.setAmountMax(new BigDecimal("500"));
        CcrRateRule r2 = rule("R002", "N2", 20, "LOAN");
        r2.setAmountMin(new BigDecimal("800")); // 500~800 有空档
        r2.setAmountMax(new BigDecimal("1000"));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r1, r2));

        String issue = engine.validateContinuity(1L);
        assertEquals("规则 [R002] 与同维度上一规则金额区间存在空档", issue);
    }

    @Test
    void validateContinuity_min大于等于max返回非法() {
        CcrRateRule r1 = rule("R001", "N1", 10, "LOAN");
        r1.setAmountMin(new BigDecimal("1000"));
        r1.setAmountMax(new BigDecimal("500")); // min > max
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r1));

        String issue = engine.validateContinuity(1L);
        assertEquals("规则 [R001] 金额区间非法:min>=max", issue);
    }

    @Test
    void validateContinuity_不同维度分组独立校验() {
        // 两个不同产品的区间不连续但各自独立,不应报错
        CcrRateRule r1 = rule("R001", "N1", 10, "LOAN");
        r1.setProductCode("P001");
        r1.setAmountMin(new BigDecimal("0"));
        r1.setAmountMax(new BigDecimal("500"));
        CcrRateRule r2 = rule("R002", "N2", 20, "LOAN");
        r2.setProductCode("P002"); // 不同产品 → 不同分组
        r2.setAmountMin(new BigDecimal("1000")); // 与 r1 有空档,但不在同一组
        r2.setAmountMax(new BigDecimal("2000"));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r1, r2));

        assertNull(engine.validateContinuity(1L));
    }

    // ---------- checkHardBoundary ----------

    @Test
    void checkHardBoundary_businessType为空抛BAD_REQUEST() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.checkHardBoundary("", "P001", new BigDecimal("5.0")));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    @Test
    void checkHardBoundary_无硬边界配置返回null() {
        when(cacheUtil.get(anyString())).thenReturn(null);
        when(productRateLimitMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BigDecimal boundary = engine.checkHardBoundary("LOAN_PUBLIC", "P001", new BigDecimal("5.0"));

        assertNull(boundary);
    }

    @Test
    void checkHardBoundary_缓存命中不查库() {
        CcrProductRateLimit limit = new CcrProductRateLimit();
        limit.setProductCode("P001");
        limit.setBusinessType("LOAN");
        limit.setHardBoundaryRate(new BigDecimal("3.0"));
        when(cacheUtil.get(anyString())).thenReturn(limit);

        BigDecimal boundary = engine.checkHardBoundary("LOAN_PUBLIC", "P001", new BigDecimal("5.0"));

        assertEquals(new BigDecimal("3.0"), boundary);
        verify(productRateLimitMapper, never()).selectOne(any());
    }

    @Test
    void checkHardBoundary_贷款低于硬边界不抛异常仅返回边界() {
        // §用户要求:取消硬边界限制——突破边界不再阻断,仅返回边界值供展示
        CcrProductRateLimit limit = new CcrProductRateLimit();
        limit.setProductCode("P001");
        limit.setBusinessType("LOAN");
        limit.setHardBoundaryRate(new BigDecimal("3.0"));
        when(cacheUtil.get(anyString())).thenReturn(limit);

        BigDecimal boundary = engine.checkHardBoundary("LOAN_PUBLIC", "P001", new BigDecimal("2.9"));

        assertEquals(new BigDecimal("3.0"), boundary);
    }

    @Test
    void checkHardBoundary_贷款等于硬边界通过() {
        CcrProductRateLimit limit = new CcrProductRateLimit();
        limit.setProductCode("P001");
        limit.setBusinessType("LOAN");
        limit.setHardBoundaryRate(new BigDecimal("3.0"));
        when(cacheUtil.get(anyString())).thenReturn(limit);

        BigDecimal boundary = engine.checkHardBoundary("LOAN_PUBLIC", "P001", new BigDecimal("3.0"));

        assertEquals(new BigDecimal("3.0"), boundary);
    }

    @Test
    void checkHardBoundary_存款高于硬边界不抛异常仅返回边界() {
        // §用户要求:取消硬边界限制——突破边界不再阻断,仅返回边界值供展示
        CcrProductRateLimit limit = new CcrProductRateLimit();
        limit.setProductCode("D001");
        limit.setBusinessType("DEPOSIT");
        limit.setHardBoundaryRate(new BigDecimal("2.0"));
        when(cacheUtil.get(anyString())).thenReturn(limit);

        BigDecimal boundary = engine.checkHardBoundary("DEPOSIT", "D001", new BigDecimal("2.5"));

        assertEquals(new BigDecimal("2.0"), boundary);
    }

    @Test
    void checkHardBoundary_存款等于硬边界通过() {
        CcrProductRateLimit limit = new CcrProductRateLimit();
        limit.setProductCode("D001");
        limit.setBusinessType("DEPOSIT");
        limit.setHardBoundaryRate(new BigDecimal("2.0"));
        when(cacheUtil.get(anyString())).thenReturn(limit);

        BigDecimal boundary = engine.checkHardBoundary("DEPOSIT", "D001", new BigDecimal("2.0"));

        assertEquals(new BigDecimal("2.0"), boundary);
    }

    @Test
    void checkHardBoundary_rate为null返回null() {
        CcrProductRateLimit limit = new CcrProductRateLimit();
        limit.setProductCode("P001");
        limit.setBusinessType("LOAN");
        limit.setHardBoundaryRate(new BigDecimal("3.0"));
        when(cacheUtil.get(anyString())).thenReturn(limit);

        assertNull(engine.checkHardBoundary("LOAN_PUBLIC", "P001", null));
    }

    @Test
    void checkHardBoundary_缓存未命中查库后回写缓存() {
        CcrProductRateLimit limit = new CcrProductRateLimit();
        limit.setProductCode("P001");
        limit.setBusinessType("LOAN");
        limit.setHardBoundaryRate(new BigDecimal("3.0"));
        when(cacheUtil.get(anyString())).thenReturn(null); // 缓存未命中
        when(productRateLimitMapper.selectOne(any(Wrapper.class))).thenReturn(limit);

        BigDecimal boundary = engine.checkHardBoundary("LOAN_PUBLIC", "P001", new BigDecimal("5.0"));

        assertEquals(new BigDecimal("3.0"), boundary);
        verify(cacheUtil).set(anyString(), any());
    }

    @Test
    void checkHardBoundary_业务类型归一_LOAN_PERSONAL归LOAN() {
        // LOAN_PERSONAL → LOAN,走贷款硬边界(不得低于)
        CcrProductRateLimit limit = new CcrProductRateLimit();
        limit.setProductCode("P002");
        limit.setBusinessType("LOAN");
        limit.setHardBoundaryRate(new BigDecimal("3.8"));
        when(cacheUtil.get(anyString())).thenReturn(limit);

        BigDecimal boundary = engine.checkHardBoundary("LOAN_PERSONAL", "P002", new BigDecimal("4.0"));

        assertEquals(new BigDecimal("3.8"), boundary);
    }
}
