package com.ccr.approval.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 决议书版式回归(§2026-09-23 用户拍板口径)。
 *
 * <p>存款决议书 = 抬头 + 一、客户信息 + 二、存款分项,其余一律不渲染;
 * 贷款决议书 = 抬头 + 一、客户基本信息 + 二、审批利率调整 + 三、贡献度信息。
 *
 * <p>断言取「PDF 抽出的正文文本」而非字节,故对排版、字体子集、分页不敏感;
 * 文本先去除全部空白再比对,避免表格单元格折行把词切断造成误判。
 */
class ResolutionPdfExporterTest {

    /** 存款决议书:只保留抬头/客户信息/存款分项,删掉的节一个都不能漏回来 */
    @Test
    void depositKeepsOnlyHeaderCustomerAndDepositItems() throws IOException {
        String text = render(archive("DEPOSIT", true, "VOTE_APPROVED"));

        // 抬头四项(申请号/决议编号/决议签发时间/审批结论)
        assertTrue(text.contains("利率定价决议书"), text);
        assertTrue(text.contains("申请号"), text);
        assertTrue(text.contains("决议编号"), text);
        assertTrue(text.contains("决议签发时间"), text);
        assertTrue(text.contains("审批结论"), text);

        // 2026-09-24 抬头第 5 行:存款=利率审批有效期 7 个工作日(固定文案,展平后无空格)
        assertTrue(text.contains("利率审批有效期"), text);
        assertTrue(text.contains("7个工作日"), text);
        // 贷款那条提醒不得串到存款决议书上
        assertFalse(text.contains("利率审批到期日"), text);
        assertFalse(text.contains("与授信到期日一致"), text);

        // 一、客户信息 + 二、存款分项
        assertTrue(text.contains("一、客户信息"), text);
        assertTrue(text.contains("二、存款分项"), text);

        // 分项表确实渲染了(防「整节删空」把该留的内容也删掉)
        assertTrue(text.contains("定价客户"), text);
        assertTrue(text.contains("测试客户有限公司"), text);

        // 2026-09-23 删除的内容一律不得回归
        assertFalse(text.contains("申请的存款信息"), text);
        assertFalse(text.contains("申请的贷款信息"), text);
        // 2026-09-24 存款不设利率调整节:利率只以存款分项表末列「最终决议利率(%)」表达
        assertTrue(text.contains("最终决议利率"), text);
        assertFalse(text.contains("利率调整"), text);
        assertFalse(text.contains("原执行利率"), text);
        assertFalse(text.contains("申请利率"), text);
        assertFalse(text.contains("审批利率"), text);

        // 存款分项表已去掉「担保方式」「授信协议编号」两列(后者列名不适用于存款)
        assertFalse(text.contains("担保方式"), text);
        assertFalse(text.contains("授信协议编号"), text);

        // 2026-09-23 删除的内容一律不得回归
        assertFalse(text.contains("申请的存款信息"), text);
        assertFalse(text.contains("申请的贷款信息"), text);
        assertFalse(text.contains("其他信息"), text);
        assertFalse(text.contains("贡献度"), text);
        assertFalse(text.contains("执行核验"), text);
        assertFalse(text.contains("审批情况"), text);
    }

    /** 存款分项节标题只能出现一次:section 与 dataTable 标题重复渲染的旧问题不得回归 */
    @Test
    void depositSectionTitleRenderedExactlyOnce() throws IOException {
        String text = render(archive("DEPOSIT", true, "VOTE_APPROVED"));
        assertEquals(1, countOf(text, "存款分项"), text);
    }

    /** 逐分项否决说明已删除(2026-09-24):被小组否决的申请不签发决议书,该段在真实文件中永不出现。
     *  抬头「审批结论=否决」保留为防御性分支(应对有 RES 却被否决的异常/历史数据)。 */
    @Test
    void committeeRejectNeverRendersPerItemNotes() throws IOException {
        for (String biz : new String[]{"DEPOSIT", "LOAN"}) {
            String text = render(archive(biz, true, "COMMITTEE_REJECT"));
            assertTrue(text.contains("否决("), biz + ": 抬头应反映否决(防御分支)");
            assertFalse(text.contains("经小组表决否决"), biz + ": 不得出现逐分项否决说明");
            assertFalse(text.contains("未形成最终利率"), biz + ": 不得出现逐分项否决说明");
        }
    }

    /** 贷款决议书:抬头(本轮新增)+ 审批利率调整 + 贡献度信息 */
    @Test
    void loanKeepsHeaderRateAdjustmentAndCommitments() throws IOException {
        String text = render(archive("LOAN", true, "VOTE_APPROVED"));

        assertTrue(text.contains("申请号"), text);
        assertTrue(text.contains("决议编号"), text);
        assertTrue(text.contains("一、客户基本信息"), text);
        assertTrue(text.contains("二、审批利率调整"), text);
        assertTrue(text.contains("利率调整明细"), text);
        assertTrue(text.contains("三、贡献度信息"), text);

        // 2026-09-24 抬头第 5 行:贷款=利率审批到期日与授信到期日一致(固定提示)
        assertTrue(text.contains("利率审批到期日"), text);
        assertTrue(text.contains("与授信到期日一致"), text);
        // 存款那条有效期不得串到贷款决议书上
        assertFalse(text.contains("利率审批有效期"), text);
        assertFalse(text.contains("7个工作日"), text);

        assertFalse(text.contains("存款分项"), text);
        assertFalse(text.contains("申请的存款信息"), text);
    }

    /** 贷款决议书无承诺时「三、贡献度信息」整节不出现(标题不能空挂) */
    @Test
    void loanOmitsCommitmentSectionWhenNoCommitments() throws IOException {
        String text = render(archive("LOAN", false, "VOTE_APPROVED"));
        assertTrue(text.contains("二、审批利率调整"), text);
        assertFalse(text.contains("贡献度"), text);
    }

    /** 只读保护不因精简而丢失:PDF 仍加密,可打印但不可改/不可复制提取 */
    @Test
    void pdfStaysPrintOnlyProtected() throws IOException {
        byte[] pdf = ResolutionPdfExporter.build(archive("DEPOSIT", true, "VOTE_APPROVED"));
        assertTrue(pdf.length > 1000, "PDF 体积异常,疑似未生成内容");
        try (PDDocument doc = PDDocument.load(pdf)) {
            assertTrue(doc.isEncrypted(), "决议书必须保持加密只读");
            assertTrue(doc.getCurrentAccessPermission().canPrint(), "必须放行打印");
            assertFalse(doc.getCurrentAccessPermission().canModify(), "不得允许编辑");
            assertFalse(doc.getCurrentAccessPermission().canExtractContent(), "不得允许复制/提取");
        }
    }

    // ---------- 夹具 ----------

    /** 造一份最小可用档案(键名与 approvalService.historyDetail 返回结构一致) */
    private static Map<String, Object> archive(String businessType, boolean withCommitments, String decisionSource) {
        boolean deposit = "DEPOSIT".equals(businessType);

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("application_no", "CCR202609240001");
        app.put("business_type", businessType);
        app.put("customer_scope", "SINGLE");
        app.put("customer_no", "CUST001");
        // 授信协议编号来源:creditInfoJson.agreementNo 优先
        app.put("creditInfoJson", "{\"agreementNo\":\"XY2026001\"}");

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("customerNo", "CUST001");
        customer.put("customerName", "测试客户有限公司");
        customer.put("custType", "CORP");
        customer.put("entpCharic", "NON_SOE");
        customer.put("creditLevel", "AA");
        customer.put("fiveLevelClass", "010");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", 1);
        item.put("pricing_item_no", "PI-001");
        item.put("pricing_customer_no", "CUST001");
        item.put("product_code", deposit ? "DEPOSIT_TIME_1Y" : "LOAN_GENERAL");
        item.put("term_value", "12");
        item.put("term_unit", "M");
        item.put("currency", "CNY");
        item.put("pricing_amount", "5000.0000");
        // 存款分项无原执行利率(新增),贷款分项有(存量)
        item.put("original_rate", deposit ? "" : "2.5000");
        item.put("requested_rate", "1.8000");
        item.put("current_approval_rate", "1.8000");
        item.put("final_rate", "1.8000");

        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("resolution_no", "RES2026001");
        resolution.put("issue_time", "2026-09-24T10:00:00");
        resolution.put("decision_source", decisionSource);

        Map<String, Object> commitment = new LinkedHashMap<>();
        commitment.put("metric_code", "DEPOSIT_BALANCE");
        commitment.put("target_type", "INCREMENT");
        commitment.put("baseline_value", "100.0000");
        commitment.put("target_value", "200.0000");
        commitment.put("unit", "1");
        commitment.put("metric_scope", "CUSTOMER");
        commitment.put("end_date", "2026-12-31");

        Map<String, Object> archive = new LinkedHashMap<>();
        archive.put("application", app);
        archive.put("customer", List.of(customer));
        archive.put("pricingItems", List.of(item));
        archive.put("resolutions", List.of(resolution));
        archive.put("commitments", withCommitments ? List.of(commitment) : List.of());
        archive.put("guaranteesByItem", Map.of("1", List.of(Map.of("guaranteeType", "CREDIT"))));
        return archive;
    }

    /** 生成 PDF 并抽出正文文本,去掉全部空白(单元格折行会把词切断) */
    private static String render(Map<String, Object> archive) throws IOException {
        try (PDDocument doc = PDDocument.load(ResolutionPdfExporter.build(archive))) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", "");
        }
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
