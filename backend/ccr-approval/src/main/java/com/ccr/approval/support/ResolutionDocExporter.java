package com.ccr.approval.support;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowHeightRule;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblCellMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 利率定价决议书 Word(docx)组装(§决议)
 * 复用档案查询结果 Map(approvalService.historyDetail),按决议书章节输出;
 * 数据源:客户信息(customer)/申请与分项(application/pricingItems)/决议(resolutions)/
 * 审批轨迹(approvalActions)/行长决策与表决(presidentDecisions/voteResults)/执行核验(resolutionExecutions)。
 * 排版:公文式——A4+页边距,黑体标题,表头底纹,黑色 1pt 边框,描述表固定列宽。
 * 说明:本文件输出可下载/可打印的 Word 决议书,内容以审批留痕为准。
 */
public final class ResolutionDocExporter {

    private ResolutionDocExporter() {
    }

    /** 正文中文字体(Word 需 eastAsia 字体才按中文渲染,POI 需显式设置) */
    private static final String FONT_BODY = "宋体";
    private static final String FONT_HEAD = "黑体";
    /** 页面: A4(11906×16838 twips) + 公文页边距 */
    private static final int PAGE_W = 11906, PAGE_H = 16838;
    private static final int MAR_TOP = 2098, MAR_BOTTOM = 1984, MAR_LEFT = 1588, MAR_RIGHT = 1474;
    /** 内容区宽度 = 页宽 - 左右边距 ≈ 8844 twips,表格宽度用整数 */
    private static final int CONTENT_W = 8600;
    private static final String BORDER_COLOR = "000000";

    /** 决议来源 → 审批结论文案(§12.7) */
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
        return source == null ? "" : source;
    }

    private static String actionTypeText(String t) {
        if ("APPROVE".equals(t)) {
            return "同意";
        }
        if ("REJECT".equals(t)) {
            return "否决";
        }
        if ("PRESIDENT_APPROVE".equals(t)) {
            return "行长同意";
        }
        if ("VETO".equals(t)) {
            return "一票否决";
        }
        if ("ADJUST".equals(t)) {
            return "调价";
        }
        if ("RETURN".equals(t)) {
            return "退回";
        }
        return t == null ? "" : t;
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

    /** 担保方式编码→中文(与 Pdf 决议书/前端字典一致) */
    private static String guaranteeText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "MORTGAGE" -> "抵押";
            case "PLEDGE" -> "质押";
            case "GUARANTEE" -> "保证";
            case "CREDIT" -> "信用";
            case "BILL_MARGIN" -> "银票保证金";
            case "CREDIT_MARGIN" -> "信用证保证金";
            case "CERTIFICATE_DEPOSIT" -> "存单质押";
            default -> code;
        };
    }

    /** 分项增强:担保方式(按分项聚合去重)+授信协议编号(存量分项=有原执行利率取申请协议,新增显示「新增业务」)
     *  §2026-08-26 决议书与申请档案定价分项口径同步(分项编号不再展示) */
    private static void enrichPricingItems(Map<String, Object> archive, Map<String, Object> app, List<Map<String, Object>> items, Map<String, Object> guarantees) {
        String agreementNo = agreementNoOf(archive, app);
        for (Map<String, Object> item : items) {
            item.put("guarantee_type_display", guaranteeTypesOf(guarantees, item));
            boolean existing = pick(item, "original_rate", "originalRate") != null
                    && !pick(item, "original_rate", "originalRate").isEmpty();
            item.put("agreement_no_display", existing ? (agreementNo == null ? "—" : agreementNo) : "新增业务");
        }
    }

    /** 决议书授信协议编号:申请提交时授信协议(creditInfoJson.agreementNo)优先;缺失兜底数仓/补录合并协议第一条 */
    private static String agreementNoOf(Map<String, Object> archive, Map<String, Object> app) {
        Object ci = app == null ? null : app.get("creditInfoJson");
        if (ci != null) {
            String no = agreementNoFromJson(ci.toString());
            if (no != null) {
                return no;
            }
        }
        List<Map<String, Object>> agreements = list(archive.get("creditAgreements"));
        if (!agreements.isEmpty()) {
            String no = pick(agreements.get(0), "agreementNo");
            if (no != null && !no.isEmpty()) {
                return no;
            }
        }
        return null;
    }

    /** 轻量提取 creditInfoJson 中 agreementNo(结构简单,不引入 JSON 解析依赖) */
    private static String agreementNoFromJson(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"agreementNo\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** 分项担保方式合并(多担保方式去重顿号分隔,与前端担保明细口径一致) */
    private static String guaranteeTypesOf(Map<String, Object> guarantees, Map<String, Object> item) {
        Object id = item.get("id");
        if (id == null) {
            return "—";
        }
        Object g = guarantees.get(id);
        if (g == null) {
            g = guarantees.get(String.valueOf(id));
        }
        if (!(g instanceof List<?> list) || list.isEmpty()) {
            return "—";
        }
        java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> gm) {
                Object t = gm.get("guaranteeType");
                if (t != null) {
                    types.add(t.toString());
                }
            }
        }
        if (types.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (String t : types) {
            String zh = guaranteeText(t);
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(zh == null ? t : zh);
        }
        return sb.toString();
    }

    /**
     * 生成决议书字节流
     *
     * @param archive approvalService.historyDetail 返回的档案 Map(含 resolutions,调用方须保证非空)
     */
    @SuppressWarnings("unchecked")
    public static byte[] build(Map<String, Object> archive) {
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
        // 分项增强(§2026-08-26 决议书与申请档案同步):担保方式聚合 + 授信协议编号(存量分项取申请协议,新增显示「新增业务」)
        enrichPricingItems(archive, app, items, guarantees);

        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            setupPage(doc);

            // ---- 标题 ----
            title(doc, "利率定价决议书");

            // ---- 抬头信息 ----
            Map<String, Object> res = resolutions.isEmpty() ? null : resolutions.get(0);
            String[][] metaRows = {
                    {"申请号", pick(app, "application_no", "applicationNo")},
                    {"决议编号", res == null ? "—" : pick(res, "resolutionNo", "resolution_no")},
                    {"决议签发时间", res == null ? "—" : pick(res, "issueTime", "issue_time")},
                    {"审批结论", res == null ? "—" : "同意(" + decisionSourceText(pick(res, "decisionSource", "decision_source")) + ")"},
            };
            descTable(doc, metaRows);
            blank(doc);

            // ---- 一、客户信息 ----
            section(doc, "一、客户信息");
            String[][] custFields = {
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
            };
            descTable(doc, custFields);
            blank(doc);

            // ---- 二、申请的贷款信息 ----
            section(doc, "二、申请的贷款信息");
            String businessType = pick(app, "business_type", "businessType");
            String[][] loanFields = {
                    {"业务类型", "DEPOSIT".equals(businessType) ? "存款" : "贷款"},
                    {"客户范围", scopeText(pick(app, "customer_scope", "customerScope"))},
                    {"客户号", pick(app, "customer_no", "customerNo")},
                    {"集团号", pick(app, "group_no", "groupNo")},
                    {"提交时间", pick(app, "submit_time", "submitTime")},
                    {"客户经理备注", pick(app, "application_remark", "applicationRemark")},
            };
            descTable(doc, loanFields);
            dataTable(doc, "定价分项", items, new String[][]{
                    {"定价客户", "pricing_customer_no", "pricingCustomerNo"},
                    {"产品", "product_code", "productCode"},
                    {"授信协议编号", "agreement_no_display"},
                    {"担保方式", "guarantee_type_display"},
                    {"金额(万元)", "pricing_amount", "pricingAmount"},
                    {"期限", "term_value", "termValue"},
                    {"期限单位", "term_unit", "termUnit"},
                    {"币种", "currency"},
            });
            blank(doc);

            // ---- 三、利率调整(从什么利率到什么利率) ----
            section(doc, "三、利率调整");
            dataTable(doc, "利率调整明细", items, new String[][]{
                    {"产品", "product_code", "productCode"},
                    {"授信协议编号", "agreement_no_display"},
                    {"担保方式", "guarantee_type_display"},
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
                XWPFParagraph p = doc.createParagraph();
                p.setIndentationLeft(240);
                XWPFRun run = p.createRun();
                run.setText("该分项(" + no + ")利率由 " + from + "% 调整为 " + (finalRate == null ? "—" : finalRate) + "%");
                setEastAsia(run, FONT_BODY);
                run.setFontFamily(FONT_BODY);
                run.setFontSize(21);
            }
            blank(doc);

            // ---- 四、审批情况 ----
            section(doc, "四、审批情况");
            if (resolutions != null && !resolutions.isEmpty()) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun run = p.createRun();
                run.setText("审批结论:同意(" + decisionSourceText(pick(res, "decisionSource", "decision_source")) + "),决议已签发。");
                setEastAsia(run, FONT_BODY);
                run.setFontFamily(FONT_BODY);
                run.setFontSize(21);
            }
            if (decisions != null && !decisions.isEmpty()) {
                dataTable(doc, "行长决策", decisions, new String[][]{
                        {"决策", "decision"},
                        {"意见", "opinion"},
                        {"决策时间", "decisionTime", "decision_time"},
                });
            }
            if (voteResults != null && !voteResults.isEmpty()) {
                dataTable(doc, "表决计票", voteResults, new String[][]{
                        {"同意票", "approveCount", "approve_count"},
                        {"否决票", "rejectCount", "reject_count"},
                        {"计票结果", "result"},
                        {"计票时间", "countTime", "count_time"},
                });
            }
            dataTable(doc, "审批轨迹", actions, new String[][]{
                    {"节点", "node_code", "nodeCode"},
                    {"动作", "action_type", "actionType"},
                    {"操作人", "operatorName", "operator_name"},
                    {"操作角色", "operator_role", "operatorRole"},
                    {"调整前利率(%)", "before_rate", "beforeRate"},
                    {"调整后利率(%)", "after_rate", "afterRate"},
                    {"意见", "action_comment", "actionComment"},
                    {"时间", "operation_time", "operationTime"},
            });
            blank(doc);

            // ---- 五、其他信息 ----
            section(doc, "五、其他信息");
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
                addMeta(doc, "担保方式", String.join("、", types));
            }
            if (commitments != null && !commitments.isEmpty()) {
                dataTable(doc, "拟达成贡献度承诺", commitments, new String[][]{
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
                dataTable(doc, "决议执行核验", execs, new String[][]{
                        {"贷款合同号", "loanContractNo", "loan_contract_no"},
                        {"补充协议号", "supplementAgreementNo", "supplement_agreement_no"},
                        {"执行利率(%)", "executionRate", "execution_rate"},
                        {"执行状态", "executionStatus", "execution_status"},
                        {"核验结果", "reconcileResult", "reconcile_result"},
                        {"核验时间", "reconcileTime", "reconcile_time"},
                });
            }
            blank(doc);

            // ---- 落款 ----
            XWPFParagraph footer = doc.createParagraph();
            footer.setAlignment(ParagraphAlignment.CENTER);
            footer.setSpacingBefore(360);
            XWPFRun fr = footer.createRun();
            fr.setText("本决议书由利率定价审批系统依据审批留痕自动生成，可下载打印归档。");
            fr.setFontSize(18);
            fr.setColor("808080");
            fr.setFontFamily(FONT_BODY);
            setEastAsia(fr, FONT_BODY);

            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("决议书生成失败", e);
        }
    }

    // ---------- docx 组装私有方法 ----------

    /** A4 页面与公文页边距 */
    private static void setupPage(XWPFDocument doc) {
        CTSectPr sectPr = doc.getDocument().getBody().getSectPr();
        if (sectPr == null) {
            sectPr = doc.getDocument().getBody().addNewSectPr();
        }
        CTPageSz pgSz = sectPr.getPgSz();
        if (pgSz == null) {
            pgSz = sectPr.addNewPgSz();
        }
        pgSz.setW(PAGE_W);
        pgSz.setH(PAGE_H);
        CTPageMar pgMar = sectPr.getPgMar();
        if (pgMar == null) {
            pgMar = sectPr.addNewPgMar();
        }
        pgMar.setTop(MAR_TOP);
        pgMar.setBottom(MAR_BOTTOM);
        pgMar.setLeft(MAR_LEFT);
        pgMar.setRight(MAR_RIGHT);
        pgMar.setHeader(720);
        pgMar.setFooter(720);
    }

    /** 决议书大标题(黑体 22pt 居中) */
    private static void title(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        p.setSpacingAfter(320);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(44);
        run.setFontFamily(FONT_HEAD);
        setEastAsia(run, FONT_HEAD);
    }

    /** 小节标题(黑体 16pt 加粗) */
    private static void section(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(280);
        p.setSpacingAfter(140);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(32);
        run.setFontFamily(FONT_HEAD);
        setEastAsia(run, FONT_HEAD);
    }

    /** 单条字段元信息行(左键右值) */
    private static void addMeta(XWPFDocument doc, String key, String value) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(60);
        XWPFRun k = p.createRun();
        k.setText(key + ":");
        k.setBold(true);
        k.setFontSize(21);
        k.setFontFamily(FONT_BODY);
        setEastAsia(k, FONT_BODY);
        XWPFRun v = p.createRun();
        v.setText(value == null ? "—" : value);
        v.setFontSize(21);
        v.setFontFamily(FONT_BODY);
        setEastAsia(v, FONT_BODY);
    }

    /** 两列(字段/值)描述表,标签列固定 2200 twips */
    private static void descTable(XWPFDocument doc, String[][] rows) {
        if (rows == null || rows.length == 0) {
            return;
        }
        XWPFTable table = doc.createTable(rows.length, 2);
        styleTable(table, new int[]{2200, CONTENT_W - 2200});
        for (int i = 0; i < rows.length; i++) {
            setCell(table.getRow(i).getCell(0), rows[i][0], true, 21, false);
            setCell(table.getRow(i).getCell(1), rows[i][1], false, 21, false);
        }
        blank(doc);
    }

    /** 通用数据表(表头底纹 + 数据行,列均分) */
    private static void dataTable(XWPFDocument doc, String title, List<Map<String, Object>> rows, String[][] cols) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        XWPFParagraph head = doc.createParagraph();
        head.setSpacingBefore(120);
        head.setSpacingAfter(80);
        XWPFRun hr = head.createRun();
        hr.setText(title);
        hr.setBold(true);
        hr.setFontSize(22);
        hr.setFontFamily(FONT_BODY);
        setEastAsia(hr, FONT_BODY);

        int[] widths = new int[cols.length];
        for (int c = 0; c < cols.length; c++) {
            widths[c] = CONTENT_W / cols.length;
        }
        XWPFTable table = doc.createTable(rows.size() + 1, cols.length);
        styleTable(table, widths);
        XWPFTableRow header = table.getRow(0);
        for (int c = 0; c < cols.length; c++) {
            setCell(header.getCell(c), cols[c][0], true, 20, true);
            shadeCell(header.getCell(c), "D9D9D9");
        }
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> data = rows.get(i);
            XWPFTableRow row = table.getRow(i + 1);
            for (int c = 0; c < cols.length; c++) {
                String[] keys = new String[cols[c].length - 1];
                System.arraycopy(cols[c], 1, keys, 0, keys.length);
                setCell(row.getCell(c), pick(data, keys), false, 20, false);
            }
        }
        blank(doc);
    }

    /** 单元格文本(清空默认段落 run 后写入,统一字体与对齐) */
    private static void setCell(XWPFTableCell cell, String text, boolean bold, int halfPoints, boolean center) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        while (!p.getRuns().isEmpty()) {
            p.removeRun(0);
        }
        p.setAlignment(center ? ParagraphAlignment.CENTER : ParagraphAlignment.LEFT);
        XWPFRun run = p.createRun();
        run.setText(text == null ? "" : text);
        run.setBold(bold);
        run.setFontSize(halfPoints);
        run.setFontFamily(FONT_BODY);
        setEastAsia(run, FONT_BODY);
    }

    /** 表格:黑色 1pt 边框、单元格边距、固定列宽、行高下限 */
    private static void styleTable(XWPFTable table, int[] widths) {
        table.setWidth(CONTENT_W);
        table.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, 8, 0, BORDER_COLOR);
        table.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, 8, 0, BORDER_COLOR);
        table.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, 8, 0, BORDER_COLOR);
        table.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, 8, 0, BORDER_COLOR);
        table.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, 8, 0, BORDER_COLOR);
        table.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, 8, 0, BORDER_COLOR);

        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) {
            tblPr = table.getCTTbl().addNewTblPr();
        }
        CTTblCellMar cellMar = tblPr.getTblCellMar();
        if (cellMar == null) {
            cellMar = tblPr.addNewTblCellMar();
        }
        cellMar.addNewTop().setW(60);
        cellMar.addNewBottom().setW(60);
        cellMar.addNewLeft().setW(100);
        cellMar.addNewRight().setW(100);

        for (XWPFTableRow row : table.getRows()) {
            row.setHeight(400);
            row.setHeightRule(TableRowHeightRule.AT_LEAST);
            List<XWPFTableCell> cells = row.getTableCells();
            for (int c = 0; c < cells.size() && c < widths.length; c++) {
                cells.get(c).setWidth(String.valueOf(widths[c]));
            }
        }
    }

    /** 表头底纹(浅灰) */
    private static void shadeCell(XWPFTableCell cell, String hex) {
        CTTcPr tcPr = cell.getCTTc().getTcPr();
        if (tcPr == null) {
            tcPr = cell.getCTTc().addNewTcPr();
        }
        CTShd shd = tcPr.getShd();
        if (shd == null) {
            shd = tcPr.addNewShd();
        }
        shd.setVal(STShd.Enum.forString("clear"));
        shd.setFill(hex);
    }

    /** 空行 */
    private static void blank(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(40);
        XWPFRun run = p.createRun();
        run.setText("");
    }

    /** 设置中文字体(eastAsia),否则 Word 对中文按默认字体渲染;FontCharRange 是 XWPFRun 内部枚举,常量名为小写 eastAsia */
    private static void setEastAsia(XWPFRun run, String eastAsia) {
        run.setFontFamily(eastAsia, XWPFRun.FontCharRange.eastAsia);
    }

    // ---------- 数据取值 ----------

    /** 多候选键取值(档案 Map 混用 snake_case / camelCase) */
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
