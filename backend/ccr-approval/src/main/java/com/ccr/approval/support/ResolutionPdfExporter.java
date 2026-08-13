package com.ccr.approval.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 利率定价决议书 PDF 生成(§决议,替代 docx)
 * 数据源同 ResolutionDocExporter(approvalService.historyDetail);
 * 排版: A4 + 公文页边距,黑体(simhei)标题 / 仿宋(simfang)正文,表头浅灰底纹。
 * 权限保护: PDF 标准加密,Owner 密码解锁权限(内部保管),User 密码为空(打开无需密码);
 *          AccessPermission 默认全禁,仅放行打印 → 下载后只读:不可编辑/复制/提取/组装/填表/改批注。
 * 中文字体从 classpath fonts/ 加载并嵌入子集(simhei.ttf / simfang.ttf)。
 */
public final class ResolutionPdfExporter {

    private ResolutionPdfExporter() {
    }

    /** A4(pt) */
    private static final float PAGE_W = 595.28f;
    private static final float PAGE_H = 841.89f;
    /** 页边距(pt) */
    private static final float ML = 56, MR = 56, MT = 48, MB = 48;
    private static final float CW = PAGE_W - ML - MR;
    /** Owner 密码(解锁权限/权限变更用,内部保管,不对使用方公开);User 密码空=打开无需密码 */
    private static final String OWNER_PASSWORD = "ccr-rate-resolution-owner-2026";

    // ---------- 文案映射(同 docx) ----------

    private static String decisionSourceText(String source) {
        if ("VOTE_APPROVED".equals(source)) {
            return "小组表决通过";
        }
        if ("PRESIDENT_APPROVED".equals(source)) {
            return "行长决策同意";
        }
        if ("LEVEL_APPROVED".equals(source)) {
            return "权限内审批通过";
        }
        if ("COMMITTEE_REJECT".equals(source)) {
            return "小组表决否决";
        }
        return source == null ? "" : source;
    }

    private static String executionStatusText(String s) {
        if ("CONTRACT_PENDING".equals(s)) {
            return "待绑定合同";
        }
        if ("CONTRACT_BOUND".equals(s)) {
            return "已绑定合同";
        }
        if ("EXECUTED".equals(s)) {
            return "已执行";
        }
        if ("RECONCILE_EXCEPTION".equals(s)) {
            return "核验异常";
        }
        if ("CLOSED".equals(s)) {
            return "已关闭";
        }
        return s == null ? "" : s;
    }

    private static String scopeText(String s) {
        if ("INDIVIDUAL".equals(s)) {
            return "个人";
        }
        if ("CORPORATE_SINGLE".equals(s)) {
            return "企业单户";
        }
        if ("GROUP".equals(s)) {
            return "集团";
        }
        return s == null ? "" : s;
    }

    /**
     * 生成只读 PDF 决议书
     *
     * @param archive approvalService.historyDetail 返回的档案 Map(含 resolutions,调用方须保证非空)
     */
    @SuppressWarnings("unchecked")
    public static byte[] build(Map<String, Object> archive) throws IOException {
        Map<String, Object> app = map(archive.get("application"));
        Map<String, Object> customer = first(archive.get("customer"));
        List<Map<String, Object>> items = list(archive.get("pricingItems"));
        List<Map<String, Object>> resolutions = list(archive.get("resolutions"));
        List<Map<String, Object>> actions = list(archive.get("approvalActions"));
        List<Map<String, Object>> decisions = list(archive.get("presidentDecisions"));
        List<Map<String, Object>> voteResults = list(archive.get("voteResults"));
        List<Map<String, Object>> execs = list(archive.get("resolutionExecutions"));
        List<Map<String, Object>> commitments = list(archive.get("commitments"));
        Map<String, Object> guarantees = map(archive.get("guaranteesByItem"));

        try (PDDocument doc = new PDDocument();
             InputStream hi = loadFont("fonts/simhei.ttf");
             InputStream bi = loadFont("fonts/simfang.ttf")) {
            PDFont head = PDType0Font.load(doc, hi, true);
            PDFont body = PDType0Font.load(doc, bi, true);
            Pdf ctx = new Pdf(doc, head, body);

            // ---- 标题 ----
            ctx.title("利率定价决议书");

            // ---- 抬头信息 ----
            Map<String, Object> res = resolutions.isEmpty() ? null : resolutions.get(0);
            boolean committeeReject = res != null
                    && "COMMITTEE_REJECT".equals(pick(res, "decisionSource", "decision_source"));
            String[][] metaRows = {
                    {"申请号", pick(app, "application_no", "applicationNo")},
                    {"决议编号", res == null ? "—" : pick(res, "resolutionNo", "resolution_no")},
                    {"决议签发时间", res == null ? "—" : pick(res, "issueTime", "issue_time")},
                    {"审批结论", res == null ? "—" : (committeeReject
                            ? "否决(" + decisionSourceText(pick(res, "decisionSource", "decision_source")) + ")"
                            : "同意(" + decisionSourceText(pick(res, "decisionSource", "decision_source")) + ")")},
            };
            ctx.descTable(metaRows);
            ctx.gap(8);

            // ---- 一、客户信息 ----
            ctx.section("一、客户信息");
            ctx.descTable(new String[][]{
                    {"客户名称", pick(customer, "customerName", "cust_nm")},
                    {"客户号", pick(customer, "customerNo", "cust_no")},
                    {"客户类型", "CORP".equals(pick(customer, "custType")) ? "对公" : "INDIV".equals(pick(customer, "custType")) ? "个人" : pick(customer, "custType")},
                    {"证件号码", pick(customer, "certNo")},
                    {"企业性质", pick(customer, "entpCharic")},
                    {"企业规模", pick(customer, "entpScale")},
                    {"所属行业", pick(customer, "industry", "blgd_idsty")},
                    {"内部信用等级", pick(customer, "creditLevel")},
                    {"五级分类", pick(customer, "fiveLevelClass")},
                    {"员工人数", pick(customer, "empeNum")},
                    {"总资产(万元)", pick(customer, "totalAssets")},
                    {"注册资本(万元)", pick(customer, "registeredCapital")},
                    {"成立日期", pick(customer, "estbDate")},
                    {"注册地址", pick(customer, "restAddr")},
                    {"职业", pick(customer, "occupation")},
                    {"年收入(万元)", pick(customer, "annualIncome")},
                    {"婚姻状况", pick(customer, "maritalStatus")},
                    {"联系电话", pick(customer, "phone")},
                    {"开户机构", pick(customer, "openOrgName")},
                    {"开户日期", pick(customer, "openDate")},
            });
            ctx.gap(8);

            // ---- 二、申请的贷款信息 ----
            ctx.section("二、申请的贷款信息");
            String businessType = pick(app, "business_type", "businessType");
            ctx.descTable(new String[][]{
                    {"业务类型", "DEPOSIT".equals(businessType) ? "存款" : "贷款"},
                    {"客户范围", scopeText(pick(app, "customer_scope", "customerScope"))},
                    {"客户号", pick(app, "customer_no", "customerNo")},
                    {"集团号", pick(app, "group_no", "groupNo")},
                    {"提交时间", pick(app, "submit_time", "submitTime")},
                    {"客户经理备注", pick(app, "application_remark", "applicationRemark")},
            });
            ctx.dataTable("定价分项", items, new String[][]{
                    {"分项编号", "pricing_item_no", "pricingItemNo"},
                    {"定价客户", "pricing_customer_no", "pricingCustomerNo"},
                    {"产品", "product_code", "productCode"},
                    {"金额(万元)", "pricing_amount", "pricingAmount"},
                    {"期限", "term_value", "termValue"},
                    {"期限单位", "term_unit", "termUnit"},
                    {"币种", "currency"},
            });
            ctx.gap(8);

            // ---- 三、利率调整 ----
            ctx.section("三、利率调整");
            ctx.dataTable("利率调整明细", items, new String[][]{
                    {"分项编号", "pricing_item_no", "pricingItemNo"},
                    {"产品", "product_code", "productCode"},
                    {"原执行利率(%)", "original_rate", "originalRate"},
                    {"申请利率(%)", "requested_rate", "requestedRate"},
                    {"审批利率(%)", "current_approval_rate", "currentApprovalRate"},
                    {"最终决议利率(%)", "final_rate", "finalRate"},
            });
            for (Map<String, Object> item : items) {
                String no = pick(item, "pricing_item_no", "pricingItemNo");
                String original = pick(item, "original_rate", "originalRate");
                String requested = pick(item, "requested_rate", "requestedRate");
                String finalRate = pick(item, "final_rate", "finalRate");
                String from = original != null && !original.isEmpty() ? original
                        : (requested != null && !requested.isEmpty() ? requested : "—");
                if (committeeReject) {
                    ctx.para("该分项(" + no + ")经小组表决否决,未形成最终利率(原执行 " + from + "%)。", 9, 12);
                } else {
                    ctx.para("该分项(" + no + ")利率由 " + from + "% 调整为 " + (finalRate == null ? "—" : finalRate) + "%", 9, 12);
                }
            }
            ctx.gap(8);

            // ---- 四、审批情况 ----
            ctx.section("四、审批情况");
            if (resolutions != null && !resolutions.isEmpty()) {
                ctx.para(committeeReject
                        ? "审批结论:否决(" + decisionSourceText(pick(res, "decisionSource", "decision_source")) + "),决议已签发。"
                        : "审批结论:同意(" + decisionSourceText(pick(res, "decisionSource", "decision_source")) + "),决议已签发。", 9, 0);
            }
            if (decisions != null && !decisions.isEmpty()) {
                ctx.dataTable("行长决策", decisions, new String[][]{
                        {"决策", "decision"},
                        {"意见", "opinion"},
                        {"决策时间", "decisionTime", "decision_time"},
                });
            }
            if (voteResults != null && !voteResults.isEmpty()) {
                ctx.dataTable("表决计票", voteResults, new String[][]{
                        {"同意票", "approveCount", "approve_count"},
                        {"否决票", "rejectCount", "reject_count"},
                        {"计票结果", "result"},
                        {"计票时间", "countTime", "count_time"},
                });
            }
            ctx.dataTable("审批轨迹", actions, new String[][]{
                    {"节点", "node_code", "nodeCode"},
                    {"动作", "action_type", "actionType"},
                    {"操作人", "operatorName", "operator_name"},
                    {"操作角色", "operator_role", "operatorRole"},
                    {"调整前利率(%)", "before_rate", "beforeRate"},
                    {"调整后利率(%)", "after_rate", "afterRate"},
                    {"意见", "action_comment", "actionComment"},
                    {"时间", "operation_time", "operationTime"},
            });
            ctx.gap(8);

            // ---- 五、其他信息 ----
            ctx.section("五、其他信息");
            if (!guarantees.isEmpty()) {
                List<String> types = new ArrayList<>();
                for (Object v : guarantees.values()) {
                    if (v instanceof List<?> list) {
                        for (Object g : list) {
                            if (g instanceof Map<?, ?> gm) {
                                Object t = ((Map<?, ?>) gm).get("guaranteeType");
                                if (t != null && !types.contains(t.toString())) {
                                    types.add(t.toString());
                                }
                            }
                        }
                    }
                }
                ctx.para("担保方式:" + String.join("、", types), 9, 0);
            }
            if (commitments != null && !commitments.isEmpty()) {
                ctx.dataTable("拟达成贡献度承诺", commitments, new String[][]{
                        {"指标", "metricCode", "metric_code"},
                        {"目标类型", "targetType", "target_type"},
                        {"基线值", "baselineValue", "baseline_value"},
                        {"目标值", "targetValue", "target_value"},
                        {"单位", "unit"},
                        {"范围", "metricScope", "metric_scope"},
                        {"截止日期", "endDate", "end_date"},
                });
            }
            if (execs != null && !execs.isEmpty()) {
                ctx.dataTable("决议执行核验", execs, new String[][]{
                        {"贷款合同号", "loanContractNo", "loan_contract_no"},
                        {"补充协议号", "supplementAgreementNo", "supplement_agreement_no"},
                        {"执行利率(%)", "executionRate", "execution_rate"},
                        {"执行状态", "executionStatus", "execution_status"},
                        {"核验结果", "reconcileResult", "reconcile_result"},
                        {"核验时间", "reconcileTime", "reconcile_time"},
                });
            }
            ctx.gap(12);

            // ---- 落款 ----
            ctx.footer("本决议书由利率定价审批系统依据审批留痕自动生成,已加密只读,可下载打印归档。");

            // ---- 权限保护:全权限关闭,仅打印放行(下载后不可编辑/复制) ----
            AccessPermission ap = new AccessPermission();
            ap.setCanPrint(true);
            StandardProtectionPolicy spp = new StandardProtectionPolicy(OWNER_PASSWORD, "", ap);
            spp.setEncryptionKeyLength(256);
            doc.protect(spp);

            // 必须关闭当前页内容流再 save,否则 PDFBox 报 "Cannot read while there is an open stream writer"
            ctx.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** classpath 字体流 */
    private static InputStream loadFont(String path) {
        InputStream in = ResolutionPdfExporter.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("决议书中文字体缺失(classpath:" + path + "),请检查 resources/fonts 目录");
        }
        return in;
    }

    // ---------- PDF 排版 ----------

    /** 排版上下文: 维护当前页/画布/纵坐标 */
    private static final class Pdf {
        private final PDDocument doc;
        private final PDFont head;
        private final PDFont body;
        private PDPageContentStream cs;
        private float y;

        private Pdf(PDDocument doc, PDFont head, PDFont body) throws IOException {
            this.doc = doc;
            this.head = head;
            this.body = body;
            newPage();
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = PAGE_H - MT;
        }

        /** 关闭当前页内容流(save 前必调;重复调用幂等) */
        private void close() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }

        /** 剩余空间不足则换页 */
        private void ensure(float need) throws IOException {
            if (y - need < MB) {
                newPage();
            }
        }

        private void title(String t) throws IOException {
            ensure(30);
            y -= 26;
            center(t, head, 20);
            y -= 18;
        }

        private void section(String t) throws IOException {
            ensure(24);
            y -= 18;
            text(t, head, 12, ML, y);
            y -= 12;
        }

        private void para(String t, float size, float indent) throws IOException {
            ensure(14);
            List<String> lines = wrap(t, body, size, CW - indent);
            for (String line : lines) {
                ensure(14);
                text(line, body, size, ML + indent, y);
                y -= 14;
            }
        }

        private void footer(String t) throws IOException {
            ensure(20);
            y -= 14;
            cs.setNonStrokingColor(0.5f);
            text(t, body, 7.5f, ML, y);
            cs.setNonStrokingColor(0f);
        }

        private void gap(float g) throws IOException {
            ensure(g);
            y -= g;
        }

        /** 键值描述表: 键黑体加粗,值仿宋,折行 */
        private void descTable(String[][] rows) throws IOException {
            if (rows == null || rows.length == 0) {
                return;
            }
            float[] w = {110, CW - 110};
            table(rows, w, false);
        }

        /** 数据表: 表头浅灰底纹黑体,数据仿宋 */
        private void dataTable(String title, List<Map<String, Object>> rows, String[][] cols) throws IOException {
            if (rows == null || rows.isEmpty()) {
                return;
            }
            ensure(16);
            y -= 12;
            text(title, head, 10, ML, y);
            y -= 12;
            String[][] grid = new String[rows.size() + 1][cols.length];
            for (int c = 0; c < cols.length; c++) {
                grid[0][c] = cols[c][0];
            }
            for (int r = 0; r < rows.size(); r++) {
                Map<String, Object> data = rows.get(r);
                for (int c = 0; c < cols.length; c++) {
                    String[] keys = new String[cols[c].length - 1];
                    System.arraycopy(cols[c], 1, keys, 0, keys.length);
                    grid[r + 1][c] = pick(data, keys);
                }
            }
            float[] w = new float[cols.length];
            for (int i = 0; i < cols.length; i++) {
                w[i] = CW / cols.length;
            }
            table(grid, w, true);
        }

        /** 通用表格绘制(逐行分页;headerShaded 首行灰底) */
        private void table(String[][] grid, float[] widths, boolean headerShaded) throws IOException {
            if (grid.length == 0 || grid[0].length == 0) {
                return;
            }
            final float pad = 3f;
            final float lh = 11f;      // 表格行文本行高
            final float cellMin = 15f; // 单元格最小行高
            float[] heights = new float[grid.length];
            for (int r = 0; r < grid.length; r++) {
                float maxLines = 1;
                for (int c = 0; c < grid[r].length; c++) {
                    boolean headCell = headerShaded && r == 0;
                    PDFont f = headCell ? head : body;
                    List<String> lines = wrap(grid[r][c], f, headCell ? 8.5f : 8.5f, widths[c] - pad * 2);
                    maxLines = Math.max(maxLines, lines.size());
                }
                heights[r] = Math.max(cellMin, maxLines * lh + pad * 2);
            }

            float top = y;
            for (int r = 0; r < grid.length; r++) {
                if (top - heights[r] < MB) {
                    newPage();
                    top = y;
                }
                boolean headRow = headerShaded && r == 0;
                float x = ML;
                for (int c = 0; c < grid[r].length; c++) {
                    float w = widths[c];
                    // 底纹(表头行)
                    if (headRow) {
                        cs.setNonStrokingColor(0.88f);
                        cs.addRect(x, top - heights[r], w, heights[r]);
                        cs.fill();
                        cs.setNonStrokingColor(0f);
                    }
                    // 边框
                    cs.setStrokingColor(0f);
                    cs.setLineWidth(0.8f);
                    cs.moveTo(x, top - heights[r]);
                    cs.lineTo(x + w, top - heights[r]);
                    cs.lineTo(x + w, top);
                    cs.lineTo(x, top);
                    cs.closePath();
                    cs.stroke();
                    // 文本
                    float textSize = 8.5f;
                    List<String> lines = wrap(grid[r][c], headRow ? head : body, textSize, w - pad * 2);
                    float ty = top - pad - 3;
                    for (String line : lines) {
                        text(line, headRow ? head : body, textSize, x + pad, ty);
                        ty -= lh;
                    }
                    x += w;
                }
                top -= heights[r];
            }
            y = top;
        }

        private List<String> wrap(String s, PDFont font, float size, float maxW) throws IOException {
            List<String> lines = new ArrayList<>();
            if (s == null || s.isEmpty()) {
                lines.add("");
                return lines;
            }
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\n') {
                    lines.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
                cur.append(c);
                if (font.getStringWidth(cur.toString()) * size / 1000f > maxW) {
                    lines.add(cur.toString());
                    cur.setLength(0);
                }
            }
            if (cur.length() > 0) {
                lines.add(cur.toString());
            }
            return lines;
        }

        private void center(String t, PDFont font, float size) throws IOException {
            float w = font.getStringWidth(t) * size / 1000f;
            text(t, font, size, ML + (CW - w) / 2, y);
        }

        private void text(String s, PDFont font, float size, float x, float baseline) throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, baseline);
            cs.showText(s == null ? "" : s);
            cs.endText();
        }
    }

    // ---------- 数据取值 ----------

    private static String pick(Map<String, Object> row, String... keys) {
        if (row == null) {
            return null;
        }
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(Object o) {
        if (o instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        if (!(o instanceof List<?> raw)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> m) {
                result.add((Map<String, Object>) m);
            }
        }
        return result;
    }
}
