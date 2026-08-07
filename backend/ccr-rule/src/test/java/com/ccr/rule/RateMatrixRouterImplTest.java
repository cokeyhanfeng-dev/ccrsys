package com.ccr.rule;

import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrLprVersion;
import com.ccr.rule.domain.CcrRateMatrix;
import com.ccr.rule.dto.MatrixRouteInput;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.mapper.CcrLprVersionMapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
