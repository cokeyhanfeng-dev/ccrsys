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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

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

    /** 单元格显示规整:
     *  ISO 时间(T 分隔,如 2026-08-18T15:24)→ 空格分隔;
     *  纯数值去尾零(4.500000→4.5, 1888.0000→1888),与银行公文习惯一致;
     *  其余原样返回。 */
    private static String display(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?(\\.\\d+)?")) {
            return s.replace('T', ' ');
        }
        if (s.matches("-?\\d+\\.\\d+")) {
            try {
                return new java.math.BigDecimal(s).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignore) {
                // 非标准数值,保持原样
            }
        }
        return s;
    }

    /** 利率显示规整(去尾零;空值→—) */
    private static String rate(String v) {
        return v == null ? "—" : display(v);
    }

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

    // ---------- 决议书中文展示字典(§用户要求:决议书全部中文,不写英文编码) ----------
    // 与前端 utils/dict.ts 展示口径对齐;编号类(分项编号/决议编号/申请号/合同号)不映射,仅映射枚举/编码。

    /** 审批节点→职务名(决议书专用:六人小组用全称「存贷款利率与审批小组」) */
    private static String nodeText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "BRANCH_MANAGER" -> "支行行长";
            case "DEPT_GENERAL_MANAGER" -> "公司金融部总经理";
            case "VICE_PRESIDENT" -> "分管行领导";
            case "SIX_PEOPLE_GROUP" -> "存贷款利率与审批小组";
            case "PRESIDENT" -> "总行行长";
            case "SECRETARY" -> "贷审会秘书岗";
            default -> code;
        };
    }

    /** 审批动作→中文 */
    private static String actionText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "SUBMIT" -> "提交";
            case "APPROVE" -> "通过";
            case "REJECT" -> "否决";
            case "ADJUST" -> "调价";
            case "RETURN" -> "退回";
            case "VETO" -> "一票否决";
            case "AGREE" -> "同意";
            case "COUNT_PASS" -> "计票通过";
            case "ESCALATE" -> "上送";
            case "PRESIDENT_APPROVE" -> "行长决策同意";
            case "VOTE_APPROVED" -> "小组表决通过";
            default -> code;
        };
    }

    /** 操作角色(role_code)→职务 */
    private static String roleText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "customer_manager" -> "客户经理";
            case "branch_manager" -> "支行行长";
            case "dept_gm" -> "公司金融部总经理";
            case "vice_president" -> "分管行领导";
            case "committee_member" -> "审批小组成员";
            case "president" -> "总行行长";
            case "secretary" -> "贷审会秘书岗";
            case "admin" -> "系统管理员";
            case "auditor" -> "审计员";
            default -> code;
        };
    }

    /** 行长决策:APPROVE/AGREE→同意,VETO→一票否决 */
    private static String decisionText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "APPROVE", "AGREE" -> "同意";
            case "VETO" -> "一票否决";
            default -> code;
        };
    }

    /** 表决计票结果 */
    private static String voteResultText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "PASS" -> "通过";
            case "FAIL" -> "未通过";
            default -> code;
        };
    }

    /** 产品编码→产品名 */
    private static String productText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "LOAN_A" -> "对公贷款";
            case "LOAN_P" -> "个人经营性贷款";
            case "CORP_TIME_DEPOSIT" -> "对公定期存款";
            case "AGREEMENT_DEPOSIT" -> "协定存款";
            case "NOTICE_DEPOSIT" -> "通知存款";
            case "BILL_MARGIN", "BANK_ACCEPTANCE_MARGIN" -> "银票保证金";
            case "CREDIT_MARGIN", "LC_MARGIN" -> "信用证保证金";
            default -> code;
        };
    }

    /** 期限单位(兼容存量中文「月」) */
    private static String termUnitText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "YEAR", "Y" -> "年";
            case "MONTH", "M", "月" -> "个月";
            case "DAY", "D" -> "天";
            default -> code;
        };
    }

    /** 币种 */
    private static String currencyText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "CNY" -> "人民币";
            case "USD" -> "美元";
            case "HKD" -> "港币";
            default -> code;
        };
    }

    /** 担保方式 */
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

    /** 贡献度指标编码→中文名(与指标字典口径一致) */
    private static String metricText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "TOTAL" -> "综合贡献总额";
            case "GM_LOAN_CONTRIBUTION" -> "贷款贡献";
            case "GM_DEPOSIT_CONTRIBUTION" -> "存款贡献";
            case "PUBLIC_DEPOSIT_AVG" -> "存款日均";
            case "PUBLIC_LOAN_AVG" -> "流贷日均";
            case "PUBLIC_PROJECT_LOAN_AVG" -> "贷款日均";
            case "PUBLIC_DISCOUNT" -> "贴现利差收益";
            case "PUBLIC_DISCOUNT_SPREAD" -> "贴现规模";
            case "PUBLIC_INTERMEDIATE", "PUBLIC_OFF_BALANCE_INCOME" -> "对公中间业务收入";
            case "PUBLIC_EXCHANGE" -> "汇兑利差收益";
            case "PUBLIC_EXCHANGE_SPREAD" -> "结售汇业务总量";
            case "PUBLIC_PAYROLL" -> "代发贡献度";
            case "PUBLIC_PAYROLL_CONTRIBUTION" -> "代发客户数";
            case "PUBLIC_PAYROLL_AMOUNT" -> "代发金额";
            case "PUBLIC_WEALTH", "PUBLIC_WEALTH_INCOME" -> "对公财富中收";
            case "PRIVATE_DEPOSIT_AVG" -> "对私存款日均";
            case "PRIVATE_LOAN_AVG" -> "对私贷款日均";
            case "PRIVATE_WEALTH", "PRIVATE_WEALTH_INCOME" -> "对私财富中收";
            case "OTHER" -> "其它";
            default -> code;
        };
    }

    /** 承诺目标类型 */
    private static String targetTypeText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "TARGET_BALANCE" -> "目标余额";
            case "INCREMENT" -> "承诺新增";
            case "CUMULATIVE" -> "期间累计";
            default -> code;
        };
    }

    /** 承诺单位 */
    private static String commitmentUnitText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "WAN_YUAN" -> "万元";
            case "COUNT" -> "户/笔";
            default -> code;
        };
    }

    /** 指标适用范围 */
    private static String metricScopeText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "PUBLIC" -> "对公";
            case "PRIVATE_SELF" -> "本人";
            case "RELATED" -> "关联人";
            case "GROUP" -> "集团";
            case "GROUP_MEMBER" -> "集团成员";
            default -> code;
        };
    }

    /** 决议执行状态 */
    private static String execStatusText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "ISSUED" -> "已签发";
            case "CONTRACT_PENDING" -> "待签合同";
            case "CONTRACT_BOUND" -> "已绑定合同";
            case "EXECUTED" -> "已执行";
            case "RECONCILE_EXCEPTION" -> "对账异常";
            case "CLOSED" -> "已关闭";
            case "VOID" -> "已作废";
            default -> code;
        };
    }

    /** 执行核验结果 */
    private static String reconcileText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "PASS" -> "通过";
            case "WARN" -> "预警";
            case "FAIL", "EXCEPTION" -> "异常";
            default -> code;
        };
    }

    /** 企业性质 */
    private static String customerTypeText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "SOE" -> "国企";
            case "NON_SOE" -> "非国企";
            case "PERSONAL" -> "个人";
            default -> code;
        };
    }

    /** 操作人兜底中文化:六人小组计票留痕含英文结果(如「结果 PASS」),存量数据在此替换,新数据已在生成处改中文 */
    private static String operatorText(String v) {
        if (v == null) {
            return null;
        }
        return v.replace("结果 PASS", "结果 通过").replace("结果 FAIL", "结果 未通过");
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
        // 客户号→客户名称映射(§用户要求:决议书有客户的一律写客户名称,定价分项「定价客户」列按此转换)
        Map<String, String> customerNameByNo = new HashMap<>();
        for (Map<String, Object> cust : list(archive.get("customer"))) {
            String no = pick(cust, "customerNo", "cust_no");
            String name = pick(cust, "customerName", "cust_nm");
            if (no != null && name != null) {
                customerNameByNo.putIfAbsent(no, name);
            }
        }
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
        // 业务类型区分:存款决议书保留完整审批留痕;贷款决议书按用户要求精简为三部分(客户基本信息/审批利率调整/贡献度信息)
        boolean isDeposit = "DEPOSIT".equals(pick(app, "business_type", "businessType"));
        Map<String, Object> res = resolutions.isEmpty() ? null : resolutions.get(0);
        boolean committeeReject = res != null
                && "COMMITTEE_REJECT".equals(pick(res, "decisionSource", "decision_source"));

        try (PDDocument doc = new PDDocument();
             InputStream hi = loadFont("fonts/simhei.ttf");
             InputStream bi = loadFont("fonts/simfang.ttf")) {
            PDFont head = PDType0Font.load(doc, hi, true);
            PDFont body = PDType0Font.load(doc, bi, true);
            Pdf ctx = new Pdf(doc, head, body);

            // ---- 标题 ----
            ctx.title("利率定价决议书");

            // ---- 存款决议书:完整审批留痕(抬头/申请贷款信息/审批情况/担保/执行核验) ----
            if (isDeposit) {
            // ---- 抬头信息 ----
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
            }

            // ---- 一、客户信息(贷款决议书标题为"客户基本信息") ----
            ctx.section(isDeposit ? "一、客户信息" : "一、客户基本信息");
            ctx.descTable(new String[][]{
                    {"客户名称", pick(customer, "customerName", "cust_nm")},
                    {"客户号", pick(customer, "customerNo", "cust_no")},
                    {"客户类型", "CORP".equals(pick(customer, "custType")) ? "对公" : "INDIV".equals(pick(customer, "custType")) ? "个人" : pick(customer, "custType")},
                    {"证件号码", pick(customer, "certNo")},
                    {"企业性质", customerTypeText(pick(customer, "entpCharic"))},
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

            // ---- 二、申请的贷款信息(仅存款决议书保留) ----
            if (isDeposit) {
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
            Map<String, UnaryOperator<String>> itemFmt = new HashMap<>();
            itemFmt.put("pricing_customer_no", no -> customerNameByNo.getOrDefault(no, no));
            itemFmt.put("product_code", ResolutionPdfExporter::productText);
            itemFmt.put("term_unit", ResolutionPdfExporter::termUnitText);
            itemFmt.put("currency", ResolutionPdfExporter::currencyText);
            ctx.dataTable("定价分项", items, new String[][]{
                    {"定价客户", "pricing_customer_no", "pricingCustomerNo"},
                    {"产品", "product_code", "productCode"},
                    {"授信协议编号", "agreement_no_display"},
                    {"担保方式", "guarantee_type_display"},
                    {"金额(万元)", "pricing_amount", "pricingAmount"},
                    {"期限", "term_value", "termValue"},
                    {"期限单位", "term_unit", "termUnit"},
                    {"币种", "currency"},
            }, itemFmt);
            ctx.gap(8);
            }

            // ---- 三、利率调整(贷款决议书标题为"审批利率调整") ----
            ctx.section(isDeposit ? "三、利率调整" : "二、审批利率调整");
            ctx.dataTable("利率调整明细", items, new String[][]{
                    {"产品", "product_code", "productCode"},
                    {"授信协议编号", "agreement_no_display"},
                    {"担保方式", "guarantee_type_display"},
                    {"原执行利率(%)", "original_rate", "originalRate"},
                    {"申请利率(%)", "requested_rate", "requestedRate"},
                    {"审批利率(%)", "current_approval_rate", "currentApprovalRate"},
                    {"最终决议利率(%)", "final_rate", "finalRate"},
            }, Map.of("product_code", ResolutionPdfExporter::productText));
            // 利率调整明细以表格呈现,删除冗余文字描述(§2026-08-26 用户要求);
            // 例外:小组表决否决时表格无法表达「未形成最终利率」,保留一行说明
            if (committeeReject) {
                for (Map<String, Object> item : items) {
                    String no = pick(item, "pricing_item_no", "pricingItemNo");
                    String original = pick(item, "original_rate", "originalRate");
                    String from = rate(original != null && !original.isEmpty() ? original : null);
                    ctx.para("该分项(" + no + ")经小组表决否决,未形成最终利率(原执行 " + from + "%)。", 9, 12);
                }
            }
            ctx.gap(8);

            // ---- 四、审批情况(仅存款决议书保留) ----
            if (isDeposit) {
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
                }, Map.of("decision", ResolutionPdfExporter::decisionText));
            }
            if (voteResults != null && !voteResults.isEmpty()) {
                ctx.dataTable("表决计票", voteResults, new String[][]{
                        {"同意票", "approveCount", "approve_count"},
                        {"否决票", "rejectCount", "reject_count"},
                        {"计票结果", "result"},
                        {"计票时间", "countTime", "count_time"},
                }, Map.of("result", ResolutionPdfExporter::voteResultText));
            }
            // 审批轨迹:节点按职务名(六人小组=存贷款利率与审批小组)、动作/角色中文化,操作人已有姓名(nick_name)直接展示
            Map<String, UnaryOperator<String>> actionFmt = new HashMap<>();
            actionFmt.put("node_code", ResolutionPdfExporter::nodeText);
            actionFmt.put("action_type", ResolutionPdfExporter::actionText);
            actionFmt.put("operator_role", ResolutionPdfExporter::roleText);
            // 操作人/意见列兜底:六人小组计票串存 action_comment(如「计票:赞成 5/6,结果 PASS」),存量英文结果在此替换
            actionFmt.put("operatorName", ResolutionPdfExporter::operatorText);
            actionFmt.put("action_comment", ResolutionPdfExporter::operatorText);
            ctx.dataTable("审批轨迹", actions, new String[][]{
                    {"节点", "node_code", "nodeCode"},
                    {"动作", "action_type", "actionType"},
                    {"操作人", "operatorName", "operator_name"},
                    {"操作角色", "operator_role", "operatorRole"},
                    {"调整前利率(%)", "before_rate", "beforeRate"},
                    {"调整后利率(%)", "after_rate", "afterRate"},
                    {"意见", "action_comment", "actionComment"},
                    {"时间", "operation_time", "operationTime"},
            }, actionFmt);
            ctx.gap(8);
            }

            // ---- 五、其他信息(贷款决议书仅保留贡献度承诺,独立成"三、贡献度信息") ----
            if (isDeposit) {
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
                ctx.para("担保方式:" + String.join("、",
                        types.stream().map(ResolutionPdfExporter::guaranteeText).toList()), 9, 0);
            }
            } else if (commitments != null && !commitments.isEmpty()) {
            ctx.section("三、贡献度信息");
            }
            if (commitments != null && !commitments.isEmpty()) {
                Map<String, UnaryOperator<String>> commitmentFmt = new HashMap<>();
                commitmentFmt.put("metricCode", ResolutionPdfExporter::metricText);
                commitmentFmt.put("targetType", ResolutionPdfExporter::targetTypeText);
                commitmentFmt.put("unit", ResolutionPdfExporter::commitmentUnitText);
                commitmentFmt.put("metricScope", ResolutionPdfExporter::metricScopeText);
                ctx.dataTable("拟达成贡献度承诺", commitments, new String[][]{
                        {"指标", "metricCode", "metric_code"},
                        {"目标类型", "targetType", "target_type"},
                        {"基线值", "baselineValue", "baseline_value"},
                        {"目标值", "targetValue", "target_value"},
                        {"单位", "unit"},
                        {"范围", "metricScope", "metric_scope"},
                        {"截止日期", "endDate", "end_date"},
                }, commitmentFmt);
            }
            if (isDeposit && execs != null && !execs.isEmpty()) {
                ctx.dataTable("决议执行核验", execs, new String[][]{
                        {"贷款合同号", "loanContractNo", "loan_contract_no"},
                        {"补充协议号", "supplementAgreementNo", "supplement_agreement_no"},
                        {"执行利率(%)", "executionRate", "execution_rate"},
                        {"执行状态", "executionStatus", "execution_status"},
                        {"核验结果", "reconcileResult", "reconcile_result"},
                        {"核验时间", "reconcileTime", "reconcile_time"},
                }, Map.of("executionStatus", ResolutionPdfExporter::execStatusText,
                        "reconcileResult", ResolutionPdfExporter::reconcileText));
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
            dataTable(title, rows, cols, null);
        }

        /**
         * 数据表(可带列中文格式化):fmt 按「第一字段名」映射编码→中文(决议书全部中文要求),
         * 未配置的列原样展示;fmt 为 null 时与三参版本行为一致。
         */
        private void dataTable(String title, List<Map<String, Object>> rows, String[][] cols,
                               Map<String, UnaryOperator<String>> fmt) throws IOException {
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
                    String v = pick(data, keys);
                    if (fmt != null) {
                        UnaryOperator<String> fn = fmt.get(cols[c][1]);
                        if (fn != null) {
                            v = fn.apply(v);
                        }
                    }
                    grid[r + 1][c] = v;
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
            // 单元格显示规整(时间 T→空格、数值去尾零),先归一保证换行宽度与绘制一致
            for (int r = 0; r < grid.length; r++) {
                for (int c = 0; c < grid[r].length; c++) {
                    grid[r][c] = display(grid[r][c]);
                }
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
                    // 文本(垂直居中,避免文字顶部越过单元格顶边框压在横线上)
                    float textSize = 8.5f;
                    List<String> lines = wrap(grid[r][c], headRow ? head : body, textSize, w - pad * 2);
                    float blockH = lines.size() * lh;
                    float avail = heights[r] - pad * 2;
                    float topPad = Math.max(0f, (avail - blockH) / 2f);
                    float ty = top - pad - topPad - textSize * 0.8f;
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
