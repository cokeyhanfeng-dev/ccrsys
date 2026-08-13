package com.ccr.rule;

import com.ccr.common.cache.CcrCacheUtil;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrLprConfig;
import com.ccr.rule.domain.CcrLprVersion;
import com.ccr.rule.domain.CcrProductRateLimit;
import com.ccr.rule.domain.CcrProductRoute;
import com.ccr.rule.domain.CcrRateMatrix;
import com.ccr.rule.dto.MatrixRouteInput;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.mapper.CcrLprConfigMapper;
import com.ccr.rule.mapper.CcrLprVersionMapper;
import com.ccr.rule.mapper.CcrProductRateLimitMapper;
import com.ccr.rule.mapper.CcrProductRouteMapper;
import com.ccr.rule.mapper.CcrRateMatrixMapper;
import com.ccr.rule.service.impl.RateMatrixRouterImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 权限矩阵路由引擎单元测试(PRD V2 §7.2 LPR±BP,用户拍板路由口径)
 * 现行 LPR:1Y=3.0% / 5Y+=3.5%
 * 链式语义:按优先级从低到高,首个满足 rate≥boundary 的节点终审,小组兜底;
 * 所有贷款/存款必经支行行长首节点,支行行长仅在"权限内"单元格有终审权(边界=部门总经理线)
 */
@ExtendWith(MockitoExtension.class)
class RateMatrixRouterImplTest {

    @Mock
    private CcrRateMatrixMapper matrixMapper;

    @Mock
    private CcrLprVersionMapper lprVersionMapper;

    @Mock
    private CcrLprConfigMapper lprConfigMapper;

    @Mock
    private CcrProductRateLimitMapper productRateLimitMapper;

    @Mock
    private CcrProductRouteMapper productRouteMapper;

    @Mock
    private CcrCacheUtil cacheUtil;

    @InjectMocks
    private RateMatrixRouterImpl router;

    // ---------- 测试数据构造 ----------

    private void stubCurrentLpr(String lpr1y, String lpr5y) {
        CcrLprVersion lpr = new CcrLprVersion();
        lpr.setId(9601L);
        lpr.setVersionCode("LPR_V1");
        lpr.setLpr1y(new BigDecimal(lpr1y));
        lpr.setLpr5y(new BigDecimal(lpr5y));
        when(lprVersionMapper.selectOne(any())).thenReturn(lpr);
    }

    private CcrRateMatrix loanRow(String no, String bigType, String newOrExisting, String custType, String amtTier,
                                  String termTier, String node, String boundaryType, String minRate,
                                  Integer bp, String sign, String lprTerm, int priority) {
        CcrRateMatrix r = new CcrRateMatrix();
        r.setMatrixNo(no);
        r.setBusinessBigType(bigType);
        r.setNewOrExisting(newOrExisting);
        r.setCustomerType(custType);
        r.setAmountTier(amtTier);
        r.setTermTier(termTier);
        r.setStartNodeCode(node);
        r.setBoundaryType(boundaryType);
        r.setBoundaryMinRate(minRate == null ? null : new BigDecimal(minRate));
        r.setBoundaryBp(bp);
        r.setBpSign(sign);
        r.setLprTerm(lprTerm);
        r.setPriority(priority);
        r.setEffectiveFrom(LocalDateTime.now().minusDays(1));
        return r;
    }

    /** 非国企新增<5000万一年期链:BM/GM=LPR+40(3.4) → VP=LPR+20(3.2) → 小组 */
    private List<CcrRateMatrix> nonSoeNew1yChain() {
        return List.of(
                loanRow("M-LT-NSOE-1Y-BM", "LOAN_PUBLIC", "NEW", "NON_SOE", "LT_5000", "1Y",
                        "BRANCH_MANAGER", "RATE", null, 40, "+", "1Y", 1),
                loanRow("M-LT-NSOE-1Y-GM", "LOAN_PUBLIC", "NEW", "NON_SOE", "LT_5000", "1Y",
                        "DEPT_GENERAL_MANAGER", "RATE", null, 40, "+", "1Y", 2),
                loanRow("M-LT-NSOE-1Y-VP", "LOAN_PUBLIC", "NEW", "NON_SOE", "LT_5000", "1Y",
                        "VICE_PRESIDENT", "RATE", null, 20, "+", "1Y", 3),
                loanRow("M-LT-NSOE-1Y-GROUP", "LOAN_PUBLIC", "NEW", "NON_SOE", "LT_5000", "1Y",
                        "SIX_PEOPLE_GROUP", "RATE", null, 0, "+", "1Y", 4));
    }

    /** 国企新增一年期链(支行行长无权限,仅过手):GM=LPR+0(3.0) → VP=LPR-10(2.9) → 小组 */
    private List<CcrRateMatrix> soeNew1yChain(String amtTier) {
        return List.of(
                loanRow("M-" + amtTier + "-SOE-1Y-GM", "LOAN_PUBLIC", "NEW", "SOE", amtTier, "1Y",
                        "DEPT_GENERAL_MANAGER", "RATE", null, 0, "+", "1Y", 2),
                loanRow("M-" + amtTier + "-SOE-1Y-VP", "LOAN_PUBLIC", "NEW", "SOE", amtTier, "1Y",
                        "VICE_PRESIDENT", "RATE", null, 10, "-", "1Y", 3),
                loanRow("M-" + amtTier + "-SOE-1Y-GROUP", "LOAN_PUBLIC", "NEW", "SOE", amtTier, "1Y",
                        "SIX_PEOPLE_GROUP", "RATE", null, 20, "-", "1Y", 4));
    }

    /** 个人经营贷新增一年期链:BM/GM=LPR+100(4.0) → VP=LPR+80(3.8) → 小组 */
    private List<CcrRateMatrix> personalNew1yChain() {
        return List.of(
                loanRow("M-PER-NEW-1Y-BM", "LOAN_PERSONAL", "NEW", "PERSONAL", null, "1Y",
                        "BRANCH_MANAGER", "RATE", null, 100, "+", "1Y", 1),
                loanRow("M-PER-NEW-1Y-GM", "LOAN_PERSONAL", "NEW", "PERSONAL", null, "1Y",
                        "DEPT_GENERAL_MANAGER", "RATE", null, 100, "+", "1Y", 2),
                loanRow("M-PER-NEW-1Y-VP", "LOAN_PERSONAL", "NEW", "PERSONAL", null, "1Y",
                        "VICE_PRESIDENT", "RATE", null, 80, "+", "1Y", 3),
                loanRow("M-PER-NEW-1Y-GROUP", "LOAN_PERSONAL", "NEW", "PERSONAL", null, "1Y",
                        "SIX_PEOPLE_GROUP", "RATE", null, 80, "+", "1Y", 4));
    }

    /** 非国企存量<5000万链:GM=orig-20BP floor3.0 → VP=floor3.0 → 小组(<3.0) */
    private List<CcrRateMatrix> nonSoeExistingChain() {
        return List.of(
                loanRow("M-EX-GM", "LOAN_PUBLIC", "EXISTING", "NON_SOE", "LT_5000", null,
                        "DEPT_GENERAL_MANAGER", "SPREAD", "3.0", 20, null, null, 2),
                loanRow("M-EX-VP", "LOAN_PUBLIC", "EXISTING", "NON_SOE", "LT_5000", null,
                        "VICE_PRESIDENT", "RATE", "3.0", null, null, null, 3),
                loanRow("M-EX-GROUP", "LOAN_PUBLIC", "EXISTING", "NON_SOE", "LT_5000", null,
                        "SIX_PEOPLE_GROUP", "RATE", "3.0", null, null, null, 4));
    }

    /** 国企存量链(按金额档):GM=orig-30BP floor3.0 → VP=floor3.0 → 小组(<3.0) */
    private List<CcrRateMatrix> soeExistingChain(String amtTier) {
        return List.of(
                loanRow("M-EX-SOE-GM", "LOAN_PUBLIC", "EXISTING", "SOE", amtTier, null,
                        "DEPT_GENERAL_MANAGER", "SPREAD", "3.0", 30, null, null, 2),
                loanRow("M-EX-SOE-VP", "LOAN_PUBLIC", "EXISTING", "SOE", amtTier, null,
                        "VICE_PRESIDENT", "RATE", "3.0", null, null, null, 3),
                loanRow("M-EX-SOE-GROUP", "LOAN_PUBLIC", "EXISTING", "SOE", amtTier, null,
                        "SIX_PEOPLE_GROUP", "RATE", "3.0", null, null, null, 4));
    }

    private CcrRateMatrix depositRow(String no, String productCode, String termTier, String upper) {
        CcrRateMatrix r = new CcrRateMatrix();
        r.setMatrixNo(no);
        r.setBusinessBigType("DEPOSIT");
        r.setNewOrExisting("NEW");
        r.setProductCode(productCode);
        r.setTermTier(termTier);
        r.setStartNodeCode("SIX_PEOPLE_GROUP");
        r.setBoundaryType("RATE");
        r.setBoundaryMinRate(new BigDecimal(upper));
        r.setPriority(1);
        r.setEffectiveFrom(LocalDateTime.now().minusDays(1));
        return r;
    }

    private MatrixRouteInput loanInput(String bigType, String newOrExisting, String custType,
                                       String amount, int termMonths, String rate) {
        MatrixRouteInput in = new MatrixRouteInput();
        in.setBusinessBigType(bigType);
        in.setNewOrExisting(newOrExisting);
        in.setCustomerType(custType);
        in.setAmount(amount == null ? null : new BigDecimal(amount));
        in.setTermValue(termMonths);
        in.setTermUnit("MONTH");
        in.setRequestedRate(new BigDecimal(rate));
        return in;
    }

    // ---------- 用例 ----------

    @Test
    void 金额档_5000万整归GE_5000() {
        stubCurrentLpr("3.0", "3.5");
        List<CcrRateMatrix> rows = new java.util.ArrayList<>(soeNew1yChain("LT_5000"));
        rows.addAll(soeNew1yChain("GE_5000"));
        when(matrixMapper.selectList(any())).thenReturn(rows);
        // 5000万整 → GE_5000 档(国企新增,GM线=LPR+0=3.0)
        RouteResult result = router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "SOE", "5000", 12, "3.0"));
        assertEquals("DEPT_GENERAL_MANAGER", result.getFinalNodeCode());
        assertEquals("M-GE_5000-SOE-1Y-GM", result.getMatchedRuleCode());
    }

    @Test
    void LPR临界点_利率等于边界_权限内终审() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        // rate == 支行行长终审边界(LPR+40BP=3.4) → 权限内,支行行长终审
        RouteResult result = router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.4"));
        assertEquals("BRANCH_MANAGER", result.getFinalNodeCode());
        assertEquals(List.of("BRANCH_MANAGER"), result.getRouteChain());
    }

    @Test
    void 方向_贷款LOWER_BETTER() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        RouteResult result = router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.5"));
        assertEquals("LOWER_BETTER", result.getRateDirection());
    }

    @Test
    void 方向_存款HIGHER_BETTER() {
        when(matrixMapper.selectList(any())).thenReturn(List.of(depositRow("M-DEP-TIME-3M", null, "3M", "0.85")));
        MatrixRouteInput in = new MatrixRouteInput();
        in.setBusinessBigType("DEPOSIT");
        in.setNewOrExisting("NEW");
        in.setTermValue(3);
        in.setTermUnit("MONTH");
        in.setRequestedRate(new BigDecimal("0.9"));
        RouteResult result = router.calcRoute(in);
        assertEquals("HIGHER_BETTER", result.getRateDirection());
    }

    @Test
    void SPREAD存量行_降幅内GM终审() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeExistingChain());
        // 原利率4.0,申请3.85 → 降幅15BP ≤ 20BP → GM终审(边界=4.0-0.2=3.8)
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "EXISTING", "NON_SOE", "2000", 12, "3.85");
        in.setOriginalRate(new BigDecimal("4.0"));
        RouteResult result = router.calcRoute(in);
        assertEquals("DEPT_GENERAL_MANAGER", result.getFinalNodeCode());
    }

    @Test
    void SPREAD存量行_降幅超GM上送VP() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeExistingChain());
        // 原利率4.0,申请3.75 → 降幅25BP > 20BP,但不低于3.0 → VP终审
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "EXISTING", "NON_SOE", "2000", 12, "3.75");
        in.setOriginalRate(new BigDecimal("4.0"));
        RouteResult result = router.calcRoute(in);
        assertEquals("VICE_PRESIDENT", result.getFinalNodeCode());
    }

    @Test
    void SPREAD存量行_突破绝对下限_上会小组() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeExistingChain());
        // 原利率4.0,申请2.9 → 低于绝对下限3.0 → 上会
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "EXISTING", "NON_SOE", "2000", 12, "2.9");
        in.setOriginalRate(new BigDecimal("4.0"));
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
    }

    @Test
    void SPREAD存量行_绝对下限收紧GM边界() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeExistingChain());
        // 原利率3.1 → GM边界=max(3.1-0.2, 3.0)=3.0;申请3.05 ≥ 3.0 → GM终审
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "EXISTING", "NON_SOE", "2000", 12, "3.05");
        in.setOriginalRate(new BigDecimal("3.1"));
        RouteResult result = router.calcRoute(in);
        assertEquals("DEPT_GENERAL_MANAGER", result.getFinalNodeCode());
    }

    @Test
    void 国企新增链_支行无权限_GM终审且支行必经() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(soeNew1yChain("LT_5000"));
        RouteResult result = router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "SOE", "2000", 12, "3.0"));
        assertEquals("DEPT_GENERAL_MANAGER", result.getFinalNodeCode());
        assertEquals("BRANCH_MANAGER", result.getStartNodeCode());
        assertEquals(List.of("BRANCH_MANAGER", "DEPT_GENERAL_MANAGER"), result.getRouteChain());
    }

    @Test
    void 国企新增链_等于VP边界_VP终审() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(soeNew1yChain("LT_5000"));
        RouteResult result = router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "SOE", "2000", 12, "2.9"));
        assertEquals("VICE_PRESIDENT", result.getFinalNodeCode());
    }

    @Test
    void 国企新增链_低于全部下限_上会小组() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(soeNew1yChain("LT_5000"));
        RouteResult result = router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "SOE", "2000", 12, "2.85"));
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertEquals(List.of("BRANCH_MANAGER", "DEPT_GENERAL_MANAGER", "VICE_PRESIDENT", "SIX_PEOPLE_GROUP"),
                result.getRouteChain());
    }

    @Test
    void 个人新增链_达GM线_支行行长终审() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(personalNew1yChain());
        RouteResult result = router.calcRoute(loanInput("LOAN_PERSONAL", "NEW", "PERSONAL", "500", 12, "4.0"));
        assertEquals("BRANCH_MANAGER", result.getFinalNodeCode());
    }

    @Test
    void 个人新增链_突破GM线_VP终审() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(personalNew1yChain());
        // 3.85 < 4.0(GM线) 且 ≥ 3.8(VP线) → VP终审
        RouteResult result = router.calcRoute(loanInput("LOAN_PERSONAL", "NEW", "PERSONAL", "500", 12, "3.85"));
        assertEquals("VICE_PRESIDENT", result.getFinalNodeCode());
        assertEquals("BRANCH_MANAGER", result.getStartNodeCode());
    }

    @Test
    void 个人新增链_低于VP线_上会小组() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(personalNew1yChain());
        RouteResult result = router.calcRoute(loanInput("LOAN_PERSONAL", "NEW", "PERSONAL", "500", 12, "3.7"));
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
    }

    @Test
    void 存款_3M期限档_超上限上会() {
        when(matrixMapper.selectList(any())).thenReturn(List.of(depositRow("M-DEP-TIME-3M", null, "3M", "0.85")));
        MatrixRouteInput in = new MatrixRouteInput();
        in.setBusinessBigType("DEPOSIT");
        in.setNewOrExisting("NEW");
        in.setTermValue(3);
        in.setTermUnit("MONTH");
        in.setRequestedRate(new BigDecimal("0.9"));
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertEquals("M-DEP-TIME-3M", result.getMatchedRuleCode());
        assertEquals(List.of("BRANCH_MANAGER", "SIX_PEOPLE_GROUP"), result.getRouteChain());
    }

    @Test
    void 存款_3M期限档_等于上限_支行行长终审() {
        when(matrixMapper.selectList(any())).thenReturn(List.of(depositRow("M-DEP-TIME-3M", null, "3M", "0.85")));
        MatrixRouteInput in = new MatrixRouteInput();
        in.setBusinessBigType("DEPOSIT");
        in.setNewOrExisting("NEW");
        in.setTermValue(3);
        in.setTermUnit("MONTH");
        in.setRequestedRate(new BigDecimal("0.85"));
        RouteResult result = router.calcRoute(in);
        // 上限语义:高于上限才需小组批;0.85 未高于 0.85 → 支行行长权限内终审
        assertEquals("BRANCH_MANAGER", result.getFinalNodeCode());
        assertEquals(List.of("BRANCH_MANAGER"), result.getRouteChain());
    }

    @Test
    void 存款_6M期限档命中() {
        when(matrixMapper.selectList(any())).thenReturn(List.of(
                depositRow("M-DEP-TIME-3M", null, "3M", "0.85"),
                depositRow("M-DEP-TIME-6M", null, "6M", "1.05"),
                depositRow("M-DEP-TIME-1Y", null, "1Y", "1.25")));
        MatrixRouteInput in = new MatrixRouteInput();
        in.setBusinessBigType("DEPOSIT");
        in.setNewOrExisting("NEW");
        in.setTermValue(6);
        in.setTermUnit("MONTH");
        in.setRequestedRate(new BigDecimal("1.2"));
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertEquals("M-DEP-TIME-6M", result.getMatchedRuleCode());
    }

    @Test
    void 存款_通知存款7D期限档命中() {
        when(matrixMapper.selectList(any())).thenReturn(List.of(
                depositRow("M-DEP-NOTICE-1D", "NOTICE_DEPOSIT", "1D", "0.20"),
                depositRow("M-DEP-NOTICE-7D", "NOTICE_DEPOSIT", "7D", "0.35")));
        MatrixRouteInput in = new MatrixRouteInput();
        in.setBusinessBigType("DEPOSIT");
        in.setNewOrExisting("NEW");
        in.setProductCode("NOTICE_DEPOSIT");
        in.setTermValue(7);
        in.setTermUnit("DAY");
        in.setRequestedRate(new BigDecimal("0.4"));
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertEquals("M-DEP-NOTICE-7D", result.getMatchedRuleCode());
    }

    @Test
    void 支行行长必经_突破挂牌价上送VP() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        // 3.3 < 支行行长终审边界3.4(部门总经理线/挂牌价) 且 ≥ VP线3.2 → 上送至分管行领导
        RouteResult result = router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.3"));
        assertEquals("BRANCH_MANAGER", result.getStartNodeCode());
        assertEquals("VICE_PRESIDENT", result.getFinalNodeCode());
        assertEquals(List.of("BRANCH_MANAGER", "DEPT_GENERAL_MANAGER", "VICE_PRESIDENT"), result.getRouteChain());
    }

    @Test
    void 支行行长必经_突破全部岗位线_上会小组() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        RouteResult result = router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.1"));
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertEquals("BRANCH_MANAGER", result.getStartNodeCode());
    }

    @Test
    void 同优先级多命中_抛RULE_MULTI_MATCH() {
        when(matrixMapper.selectList(any())).thenReturn(List.of(
                loanRow("M-DUP-1", "LOAN_PUBLIC", "NEW", "NON_SOE", "LT_5000", "1Y",
                        "BRANCH_MANAGER", "RATE", null, 40, "+", "1Y", 1),
                loanRow("M-DUP-2", "LOAN_PUBLIC", "NEW", "NON_SOE", "LT_5000", "1Y",
                        "DEPT_GENERAL_MANAGER", "RATE", null, 40, "+", "1Y", 1)));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.5")));
        assertEquals(ErrorCode.RULE_MULTI_MATCH.getCode(), ex.getCode());
    }

    @Test
    void 无生效LPR_抛LPR_NOT_EFFECTIVE() {
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        when(lprVersionMapper.selectOne(any())).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.5")));
        assertEquals(ErrorCode.LPR_NOT_EFFECTIVE.getCode(), ex.getCode());
    }

    @Test
    void 集团场景_金额档按集团综合授信批复总额度() {
        stubCurrentLpr("3.0", "3.5");
        // 同时配置 LT/GE 两档链:GE 档非国企新增无 BM 行(支行无权限)
        List<CcrRateMatrix> rows = new java.util.ArrayList<>(nonSoeNew1yChain());
        rows.addAll(List.of(
                loanRow("M-GE-NSOE-1Y-GM", "LOAN_PUBLIC", "NEW", "NON_SOE", "GE_5000", "1Y",
                        "DEPT_GENERAL_MANAGER", "RATE", null, 40, "+", "1Y", 2),
                loanRow("M-GE-NSOE-1Y-VP", "LOAN_PUBLIC", "NEW", "NON_SOE", "GE_5000", "1Y",
                        "VICE_PRESIDENT", "RATE", null, 20, "+", "1Y", 3),
                loanRow("M-GE-NSOE-1Y-GROUP", "LOAN_PUBLIC", "NEW", "NON_SOE", "GE_5000", "1Y",
                        "SIX_PEOPLE_GROUP", "RATE", null, 0, "+", "1Y", 4)));
        when(matrixMapper.selectList(any())).thenReturn(rows);
        // 申请金额2000万,集团综合授信批复总额度6000万 → GE_5000 档(无 BM 行,3.5≥GM线3.4 → GM终审)
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.5");
        in.setGroupCreditTotal(new BigDecimal("6000"));
        RouteResult result = router.calcRoute(in);
        assertEquals("DEPT_GENERAL_MANAGER", result.getFinalNodeCode());
        assertEquals("M-GE-NSOE-1Y-GM", result.getMatchedRuleCode());
    }

    @Test
    void 集团场景_显式APPLY_AMOUNT按申请金额定档() {
        stubCurrentLpr("3.0", "3.5");
        List<CcrRateMatrix> rows = new java.util.ArrayList<>(nonSoeNew1yChain());
        rows.addAll(List.of(
                loanRow("M-GE-NSOE-1Y-GM", "LOAN_PUBLIC", "NEW", "NON_SOE", "GE_5000", "1Y",
                        "DEPT_GENERAL_MANAGER", "RATE", null, 40, "+", "1Y", 2),
                loanRow("M-GE-NSOE-1Y-GROUP", "LOAN_PUBLIC", "NEW", "NON_SOE", "GE_5000", "1Y",
                        "SIX_PEOPLE_GROUP", "RATE", null, 0, "+", "1Y", 4)));
        when(matrixMapper.selectList(any())).thenReturn(rows);
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.5");
        in.setGroupCreditTotal(new BigDecimal("6000"));
        in.setAmountBasis(MatrixRouteInput.AMOUNT_BASIS_APPLY_AMOUNT);
        RouteResult result = router.calcRoute(in);
        assertEquals("BRANCH_MANAGER", result.getFinalNodeCode());
    }

    @Test
    void 冻结LPR版本_按冻结值换算() {
        // 冻结版本 LPR 1Y=4.0:GM线=4.0,VP线=3.9;申请3.5 → 均不满足 → 上会
        CcrLprVersion frozen = new CcrLprVersion();
        frozen.setId(99L);
        frozen.setVersionCode("LPR_V0");
        frozen.setLpr1y(new BigDecimal("4.0"));
        frozen.setLpr5y(new BigDecimal("4.5"));
        when(lprVersionMapper.selectById(99L)).thenReturn(frozen);
        when(matrixMapper.selectList(any())).thenReturn(soeNew1yChain("LT_5000"));
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "SOE", "2000", 12, "3.5");
        in.setLprVersionId(99L);
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertEquals(99L, result.getLprVersionId());
        assertEquals("LPR_V0", result.getLprVersionCode());
    }

    // ---------- §8.2 D3 边界交集(矩阵边界 ∩ 产品硬边界) ----------

    private void stubProductLimit(String productCode, String businessType, String hardRate) {
        CcrProductRateLimit limit = new CcrProductRateLimit();
        limit.setProductCode(productCode);
        limit.setBusinessType(businessType);
        limit.setHardBoundaryRate(new BigDecimal(hardRate));
        when(productRateLimitMapper.selectOne(any())).thenReturn(limit);
    }

    @Test
    void 贷款_产品硬边界高于矩阵边界_交集收紧为产品下限() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        // 矩阵BM终审边界=LPR+40BP=3.4;产品硬边界3.45 → 交集取max=3.45
        stubProductLimit("PUB_LOAN_01", "LOAN", "3.45");
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.4");
        in.setProductCode("PUB_LOAN_01");
        RouteResult result = router.calcRoute(in);
        assertEquals("BRANCH_MANAGER", result.getFinalNodeCode());
        assertEquals(new BigDecimal("3.45"), result.getBoundaryRate());
        assertEquals("M-LT-NSOE-1Y-BM", result.getMatchedMatrixNo());
    }

    @Test
    void 贷款_产品硬边界低于矩阵边界_交集取矩阵边界() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        // 矩阵边界3.4 > 产品硬边界3.0 → 交集取max=3.4(矩阵边界不被放松)
        stubProductLimit("PUB_LOAN_01", "LOAN", "3.0");
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.4");
        in.setProductCode("PUB_LOAN_01");
        RouteResult result = router.calcRoute(in);
        assertEquals(new BigDecimal("3.40"), result.getBoundaryRate());
    }

    @Test
    void 存款_产品硬边界低于矩阵上限_交集收紧为产品上限() {
        when(matrixMapper.selectList(any())).thenReturn(List.of(depositRow("M-DEP-TIME-3M", null, "3M", "0.85")));
        // 矩阵期限上限0.85;产品硬边界0.80 → 交集取min=0.80
        stubProductLimit("TIME_DEPOSIT", "DEPOSIT", "0.80");
        MatrixRouteInput in = new MatrixRouteInput();
        in.setBusinessBigType("DEPOSIT");
        in.setNewOrExisting("NEW");
        in.setProductCode("TIME_DEPOSIT");
        in.setTermValue(3);
        in.setTermUnit("MONTH");
        in.setRequestedRate(new BigDecimal("0.9"));
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertEquals(new BigDecimal("0.80"), result.getBoundaryRate());
    }

    @Test
    void 小组兜底行_matchedMatrixNo与boundaryRate均填充() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(soeNew1yChain("LT_5000"));
        // 小组兜底行边界=LPR-20BP=2.8;产品硬边界2.9 → 交集取max=2.9
        stubProductLimit("PUB_LOAN_01", "LOAN", "2.9");
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "SOE", "2000", 12, "2.85");
        in.setProductCode("PUB_LOAN_01");
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertEquals("M-LT_5000-SOE-1Y-GROUP", result.getMatchedMatrixNo());
        assertEquals(0, new BigDecimal("2.9").compareTo(result.getBoundaryRate()));
    }

    @Test
    void 无产品编码_不查产品边界_矩阵边界原样返回() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        RouteResult result = router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.4"));
        assertEquals(new BigDecimal("3.40"), result.getBoundaryRate());
        assertEquals("M-LT-NSOE-1Y-BM", result.getMatchedMatrixNo());
    }

    // ---------- §8A.5② 产品审批链路接入 ----------

    private CcrProductRoute productRoute(String productCode, String mode, String mandatoryVote,
                                         String president, String voteCondition) {
        CcrProductRoute r = new CcrProductRoute();
        r.setProductCode(productCode);
        r.setBusinessBigType("LOAN_PUBLIC");
        r.setRouteMode(mode);
        r.setMandatoryVote(mandatoryVote);
        r.setPresidentDecision(president);
        r.setVoteCondition(voteCondition);
        r.setEffectiveDate(LocalDateTime.now().minusDays(1));
        r.setPriority(0);
        return r;
    }

    private void stubProductRoute(CcrProductRoute... routes) {
        when(productRouteMapper.selectList(any())).thenReturn(java.util.Arrays.asList(routes));
    }

    @Test
    void 存款_产品链路DIRECT_VOTE_必经支行行长后上会并行长决策() {
        when(matrixMapper.selectList(any())).thenReturn(List.of(depositRow("M-DEP-TIME-3M", "TIME_DEPOSIT", "3M", "0.85")));
        // DIRECT_VOTE 不参与链式优先级:申请利率 0.8 未超上限 0.85 也直接上会
        stubProductRoute(productRoute("TIME_DEPOSIT", "DIRECT_VOTE", "N", "Y", null));
        MatrixRouteInput in = new MatrixRouteInput();
        in.setBusinessBigType("DEPOSIT");
        in.setNewOrExisting("NEW");
        in.setProductCode("TIME_DEPOSIT");
        in.setTermValue(3);
        in.setTermUnit("MONTH");
        in.setRequestedRate(new BigDecimal("0.8"));
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertEquals(List.of("BRANCH_MANAGER", "SIX_PEOPLE_GROUP", "PRESIDENT"), result.getRouteChain());
    }

    @Test
    void 贷款_产品链路强制上会_利率权限内仍必经小组() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        // 3.4 ≥ BM线3.4 本应支行行长终审;mandatory_vote=Y → 强制必经六人小组
        stubProductRoute(productRoute("PUB_LOAN_01", "CHAINED", "Y", "N", null));
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.4");
        in.setProductCode("PUB_LOAN_01");
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertEquals("BRANCH_MANAGER", result.getStartNodeCode());
    }

    @Test
    void 贷款_命中上会条件金额档_必经小组() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        // LT_5000 命中 vote_condition.amount_tier → 强制上会(本应 BM 终审)
        stubProductRoute(productRoute("PUB_LOAN_01", "CHAINED", "N", "N", "{\"amount_tier\":\"LT_5000\"}"));
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.4");
        in.setProductCode("PUB_LOAN_01");
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertTrue(result.getMessage().contains("命中上会条件"));
    }

    @Test
    void 贷款_多键上会条件_金额档不命中不上会_矩阵GM终审() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(soeExistingChain("LT_5000"));
        // 上会条件 GE_5000+SOE(AND):金额 2000万(LT_5000)不命中金额档 → 不强制上会,按矩阵分层
        stubProductRoute(productRoute("PUB_LOAN_01", "CHAINED", "N", "N",
                "{\"amount_tier\":\"GE_5000\",\"enterprise_type\":\"SOE\"}"));
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "EXISTING", "SOE", "2000", 12, "3.9");
        in.setProductCode("PUB_LOAN_01");
        in.setOriginalRate(new BigDecimal("4.2"));
        RouteResult result = router.calcRoute(in);
        assertEquals("DEPT_GENERAL_MANAGER", result.getFinalNodeCode());
        assertEquals("M-EX-SOE-GM", result.getMatchedMatrixNo());
        assertEquals(List.of("BRANCH_MANAGER", "DEPT_GENERAL_MANAGER"), result.getRouteChain());
        assertFalse(result.getMessage().contains("命中上会条件"));
    }

    @Test
    void 贷款_多键上会条件_金额档与企业类型同时命中_强制上会() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(soeExistingChain("GE_5000"));
        // GE_5000+SOE 全部命中(AND)→ 强制必经六人小组(本应 VP 终审)
        stubProductRoute(productRoute("PUB_LOAN_01", "CHAINED", "N", "N",
                "{\"amount_tier\":\"GE_5000\",\"enterprise_type\":\"SOE\"}"));
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "EXISTING", "SOE", "6000", 12, "3.3");
        in.setProductCode("PUB_LOAN_01");
        in.setOriginalRate(new BigDecimal("4.0"));
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertTrue(result.getMessage().contains("命中上会条件"));
    }

    @Test
    void 贷款_命中上会条件企业类型SOE_必经小组() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(soeNew1yChain("LT_5000"));
        // 国企 3.0 ≥ GM线3.0 本应 GM 终审;命中 enterprise_type=SOE → 强制上会
        stubProductRoute(productRoute("PUB_LOAN_01", "CHAINED", "N", "N", "{\"enterprise_type\":\"SOE\"}"));
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "SOE", "2000", 12, "3.0");
        in.setProductCode("PUB_LOAN_01");
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
    }

    @Test
    void 贷款_上会并必经行长决策() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        // 3.1 低于全部岗位线 → 上会小组;president_decision=Y → 链路追加 PRESIDENT
        stubProductRoute(productRoute("PUB_LOAN_01", "CHAINED", "N", "Y", null));
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.1");
        in.setProductCode("PUB_LOAN_01");
        RouteResult result = router.calcRoute(in);
        assertEquals("SIX_PEOPLE_GROUP", result.getFinalNodeCode());
        assertEquals(List.of("BRANCH_MANAGER", "DEPT_GENERAL_MANAGER", "VICE_PRESIDENT",
                "SIX_PEOPLE_GROUP", "PRESIDENT"), result.getRouteChain());
    }

    @Test
    void 产品未配置链路_链路不生效() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        // 仅配置 OTHER 产品强制上会;本申请 PUB_LOAN_01 未配置 → 矩阵纯驱动,BM 终审
        stubProductRoute(productRoute("OTHER", "CHAINED", "Y", "N", null));
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.4");
        in.setProductCode("PUB_LOAN_01");
        RouteResult result = router.calcRoute(in);
        assertEquals("BRANCH_MANAGER", result.getFinalNodeCode());
    }

    // ---------- §8A.3 LPR 明细按 (lpr_term, product_type) 取值 ----------

    @Test
    void 贷款_LPR明细按产品取值_优先于头表默认() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        // 该产品 1Y 明细=2.8(低于头表3.0):BM线=3.2 → 3.3 支行行长权限内终审;
        // 无明细时 BM线=3.4 → 3.3 上送 VP。以此验证明细优先于头表默认
        CcrLprConfig cfg1y = new CcrLprConfig();
        cfg1y.setVersionId(9601L);
        cfg1y.setLprTerm("1Y");
        cfg1y.setProductType("PUB_LOAN_01");
        cfg1y.setLprValue(new BigDecimal("2.8"));
        // 第一次(1Y)返回明细,第二次(5Y+)返回 null → 回退头表
        when(lprConfigMapper.selectOne(any())).thenReturn(cfg1y, null);
        MatrixRouteInput in = loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.3");
        in.setProductCode("PUB_LOAN_01");
        RouteResult result = router.calcRoute(in);
        assertEquals("BRANCH_MANAGER", result.getFinalNodeCode());
    }

    @Test
    void 贷款_无产品LPR明细_回退头表默认值() {
        stubCurrentLpr("3.0", "3.5");
        when(matrixMapper.selectList(any())).thenReturn(nonSoeNew1yChain());
        // 无产品编码 → 不查明细 → 回退头表 1Y=3.0 → BM线3.4,3.3 上送 VP
        RouteResult result = router.calcRoute(loanInput("LOAN_PUBLIC", "NEW", "NON_SOE", "2000", 12, "3.3"));
        assertEquals("VICE_PRESIDENT", result.getFinalNodeCode());
    }
}
