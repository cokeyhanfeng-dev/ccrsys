package com.ccr.approval.support;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史档案 xlsx 导出组装(PRD F7)
 * 复用档案查询结果 Map,按 §14.4 区块拆分为多个 sheet;
 * 每个 sheet 首行为水印行(操作人/机构/导出时间,§15 审计口径)
 */
public final class HistoryArchiveExporter {

    private HistoryArchiveExporter() {
    }

    /** 列定义:表头 + 候选键(档案 Map 混用 snake_case / camelCase) */
    private record Col(String header, String... keys) {
    }

    /**
     * 生成档案工作簿字节流
     *
     * @param archive   approvalService.historyDetail 返回的档案 Map
     * @param watermark 水印行文本(操作人/机构/导出时间)
     */
    @SuppressWarnings("unchecked")
    public static byte[] build(Map<String, Object> archive, String watermark) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 申请内容(单条,按键值对纵向输出)
            Sheet appSheet = wb.createSheet("申请内容");
            int r = writeWatermark(appSheet, watermark, 0);
            Map<String, Object> app = (Map<String, Object>) archive.get("application");
            if (app != null) {
                writeHeader(appSheet, r++, List.of("字段", "值"));
                String[][] fields = {
                        {"申请号", "application_no"}, {"业务类型", "business_type"}, {"客户范围", "customer_scope"},
                        {"客户号", "customer_no"}, {"集团号", "group_no"}, {"状态", "status"},
                        {"提交时间", "submit_time"}, {"终态时间", "final_time"}, {"客户经理备注", "application_remark"},
                        {"关联原申请", "source_application_id"}, {"快照包", "snapshot_bundle_id"},
                        {"冻结规则集版本", "rule_set_version_id"}, {"冻结LPR版本", "lpr_version_id"},
                        {"冻结流程版本", "flow_definition_version"}, {"路由基准日", "route_as_of_date"}
                };
                for (String[] f : fields) {
                    Row row = appSheet.createRow(r++);
                    row.createCell(0).setCellValue(f[0]);
                    row.createCell(1).setCellValue(str(app.get(f[1])));
                }
            }
            autosize(appSheet, 2);

            writeTable(wb, "集团成员", watermark, (List<Map<String, Object>>) archive.get("members"), List.of(
                    new Col("成员客户号", "member_customer_no"),
                    new Col("成员角色", "member_role"),
                    new Col("申请金额(万元)", "request_amount")));

            writeTable(wb, "定价分项", watermark, (List<Map<String, Object>>) archive.get("pricingItems"), List.of(
                    new Col("分项号", "pricing_item_no"),
                    new Col("定价客户", "pricing_customer_no"),
                    new Col("成员客户号", "member_customer_no"),
                    new Col("产品", "product_code"),
                    new Col("金额(万元)", "pricing_amount"),
                    new Col("期限值", "term_value"),
                    new Col("期限单位", "term_unit"),
                    new Col("币种", "currency"),
                    new Col("原利率(%)", "original_rate"),
                    new Col("申请利率(%)", "requested_rate"),
                    new Col("审批利率(%)", "current_approval_rate"),
                    new Col("最终利率(%)", "final_rate"),
                    new Col("终审岗位", "route_code"),
                    new Col("状态", "status")));

            writeTable(wb, "数据快照", watermark, (List<Map<String, Object>>) archive.get("snapshotBundles"), List.of(
                    new Col("快照包号", "bundleNo", "bundle_no"),
                    new Col("状态", "status"),
                    new Col("冻结时间", "freezeTime", "freeze_time"),
                    new Col("记录数", "recordCount", "record_count"),
                    new Col("摘要哈希", "bundleHash", "bundle_hash")));

            writeTable(wb, "资料校验", watermark, (List<Map<String, Object>>) archive.get("qualityResults"), List.of(
                    new Col("规则", "ruleCode", "rule_code"),
                    new Col("级别", "ruleLevel", "rule_level"),
                    new Col("对象类型", "subjectType", "subject_type"),
                    new Col("对象ID", "subjectId", "subject_id"),
                    new Col("说明", "message"),
                    new Col("校验时间", "checkedTime", "checked_time")));

            writeTable(wb, "审批轨迹", watermark, (List<Map<String, Object>>) archive.get("approvalActions"), List.of(
                    new Col("节点", "node_code"),
                    new Col("动作", "action_type"),
                    new Col("操作人", "operator_id"),
                    new Col("操作角色", "operator_role"),
                    new Col("调整前利率(%)", "before_rate"),
                    new Col("调整后利率(%)", "after_rate"),
                    new Col("意见", "action_comment"),
                    new Col("渠道", "operation_channel"),
                    new Col("时间", "operation_time")));

            writeTable(wb, "调价记录", watermark, (List<Map<String, Object>>) archive.get("rateAdjustments"), List.of(
                    new Col("分项", "pricing_item_id"),
                    new Col("调整前利率(%)", "before_rate"),
                    new Col("调整后利率(%)", "after_rate"),
                    new Col("原因", "adjust_reason", "reason"),
                    new Col("操作时间", "operation_time")));

            writeTable(wb, "表决轮次", watermark, (List<Map<String, Object>>) archive.get("voteRounds"), List.of(
                    new Col("轮次", "roundNo", "round_no"),
                    new Col("名称", "roundName", "round_name"),
                    new Col("状态", "status"),
                    new Col("应投", "voterCount", "voter_count"),
                    new Col("实投", "requiredCount", "required_count"),
                    new Col("开始", "roundStartTime", "round_start_time"),
                    new Col("结束", "roundEndTime", "round_end_time")));

            // 表决汇总只到计票结果粒度,不含投票人明细(匿名口径)
            writeTable(wb, "表决汇总", watermark, (List<Map<String, Object>>) archive.get("voteResults"), List.of(
                    new Col("轮次", "roundId", "round_id"),
                    new Col("分项", "pricingItemId", "pricing_item_id"),
                    new Col("同意票", "approveCount", "approve_count"),
                    new Col("否决票", "rejectCount", "reject_count"),
                    new Col("计票结果", "result"),
                    new Col("计票时间", "countTime", "count_time")));

            writeTable(wb, "行长决议", watermark, (List<Map<String, Object>>) archive.get("presidentDecisions"), List.of(
                    new Col("分项", "pricingItemId", "pricing_item_id"),
                    new Col("决策", "decision"),
                    new Col("意见", "opinion"),
                    new Col("决策时间", "decisionTime", "decision_time")));

            writeTable(wb, "决议", watermark, (List<Map<String, Object>>) archive.get("resolutions"), List.of(
                    new Col("决议号", "resolutionNo", "resolution_no"),
                    new Col("分项", "pricingItemId", "pricing_item_id"),
                    new Col("最终利率(%)", "finalRate", "final_rate"),
                    new Col("生效日", "effectiveFrom", "effective_from"),
                    new Col("到期日", "effectiveTo", "effective_to"),
                    new Col("来源", "decisionSource", "decision_source"),
                    new Col("状态", "status"),
                    new Col("签发时间", "issueTime", "issue_time")));

            writeTable(wb, "执行核验", watermark, (List<Map<String, Object>>) archive.get("resolutionExecutions"), List.of(
                    new Col("决议", "resolutionId", "resolution_id"),
                    new Col("借据/合同号", "loanContractNo", "loan_contract_no"),
                    new Col("补充协议", "supplementAgreementNo", "supplement_agreement_no"),
                    new Col("执行利率(%)", "executionRate", "execution_rate"),
                    new Col("执行状态", "executionStatus", "execution_status"),
                    new Col("核验结果", "reconcileResult", "reconcile_result"),
                    new Col("核验时间", "reconcileTime", "reconcile_time")));

            writeTable(wb, "承诺计划", watermark, (List<Map<String, Object>>) archive.get("commitmentPlans"), List.of(
                    new Col("计划号", "planNo", "plan_no"),
                    new Col("决议", "resolutionId", "resolution_id"),
                    new Col("范围", "scopeType", "scope_type"),
                    new Col("状态", "status"),
                    new Col("开始日", "startDate", "start_date"),
                    new Col("到期日", "endDate", "end_date")));

            writeTable(wb, "承诺指标", watermark, (List<Map<String, Object>>) archive.get("commitmentMetrics"), List.of(
                    new Col("计划", "planId", "plan_id"),
                    new Col("指标", "metricCode", "metric_code"),
                    new Col("目标类型", "targetType", "target_type"),
                    new Col("基线值", "baselineValue", "baseline_value"),
                    new Col("目标值", "targetValue", "target_value"),
                    new Col("单位", "unit"),
                    new Col("指标范围", "metricScope", "metric_scope")));

            // ---- 申请内容留痕(§14.4 完整保留,与审批详情同口径) ----
            writeTable(wb, "客户基本信息", watermark, (List<Map<String, Object>>) archive.get("customer"), List.of(
                    new Col("客户名称", "customerName", "cust_nm"),
                    new Col("客户号", "customerNo", "cust_no"),
                    new Col("客户类型", "custType"),
                    new Col("证件号码", "certNo"),
                    new Col("企业性质", "entpCharic"),
                    new Col("企业规模", "entpScale"),
                    new Col("所属行业", "industry", "blgd_idsty"),
                    new Col("信用等级", "creditLevel"),
                    new Col("五级分类", "fiveLevelClass"),
                    new Col("员工人数", "empeNum"),
                    new Col("总资产(万元)", "totalAssets"),
                    new Col("注册资本(万元)", "registeredCapital"),
                    new Col("成立日期", "estbDate"),
                    new Col("注册地址", "restAddr"),
                    new Col("职业", "occupation"),
                    new Col("年收入(万元)", "annualIncome"),
                    new Col("婚姻状况", "maritalStatus"),
                    new Col("居住地址", "address"),
                    new Col("联系电话", "phone"),
                    new Col("开户机构", "openOrgName"),
                    new Col("开户日期", "openDate"),
                    new Col("客户分类", "customerClass")));

            writeTable(wb, "授信协议", watermark, (List<Map<String, Object>>) archive.get("creditAgreements"), List.of(
                    new Col("协议编号", "agreementNo"),
                    new Col("授信类型", "agreementType"),
                    new Col("币种", "currency"),
                    new Col("状态", "agreementStatus"),
                    new Col("开始日期", "startDate"),
                    new Col("结束日期", "endDate"),
                    new Col("授信额度(万元)", "creditAmount"),
                    new Col("已用额度(万元)", "usedAmount"),
                    new Col("可用额度(万元)", "availableAmount"),
                    new Col("来源", "source")));

            writeTable(wb, "本行融资", watermark, (List<Map<String, Object>>) archive.get("financing"), List.of(
                    new Col("合同号", "contractNo"),
                    new Col("授信协议号", "agreementNo"),
                    new Col("合同金额(万元)", "contractAmount"),
                    new Col("余额(万元)", "loanBalance"),
                    new Col("执行利率(%)", "contractRate"),
                    new Col("利率类型", "rateType"),
                    new Col("LPR期限", "lprTerm"),
                    new Col("开始日期", "startDate"),
                    new Col("到期日期", "maturityDate"),
                    new Col("合同状态", "contractStatus"),
                    new Col("担保类型", "guaranteeType"),
                    new Col("币种", "currency")));

            writeTable(wb, "他行融资", watermark, (List<Map<String, Object>>) archive.get("appOtherLoans"), List.of(
                    new Col("融资机构", "lenderName"),
                    new Col("授信额(万元)", "creditAmount"),
                    new Col("已用额(万元)", "usedAmount"),
                    new Col("余额(万元)", "balanceAmount"),
                    new Col("年化利率(%)", "annualRate"),
                    new Col("来源", "inputMode")));

            writeTable(wb, "申请附件", watermark, (List<Map<String, Object>>) archive.get("attachments"), List.of(
                    new Col("文件名", "fileName"),
                    new Col("大小(字节)", "fileSize"),
                    new Col("上传时间", "createTime")));

            // 机构达成(§2026-08-26 存款档案已置空 orgPerformance,贷款无数据亦不写空表)
            List<Map<String, Object>> orgPerformance = (List<Map<String, Object>>) archive.get("orgPerformance");
            if (orgPerformance != null && !orgPerformance.isEmpty()) {
                writeTable(wb, "机构达成", watermark, orgPerformance, List.of(
                        new Col("机构", "orgCode"),
                        new Col("统计月份", "statMonth"),
                        new Col("达成金额(万元)", "achievedAmount"),
                        new Col("目标金额(万元)", "expectedAmount"),
                        new Col("达成率", "completionRate"),
                        new Col("数据日期", "dataDt")));
            }

            writeTable(wb, "集团授信", watermark, (List<Map<String, Object>>) archive.get("groupCredit"), List.of(
                    new Col("授信总额(万元)", "approvedTotalAmount"),
                    new Col("已分配额度", "allocatedAmount"),
                    new Col("已用额度", "usedAmount"),
                    new Col("可用额度", "availableAmount"),
                    new Col("授信开始", "creditStart"),
                    new Col("授信到期", "creditEnd"),
                    new Col("授信状态", "creditStatus")));

            // 担保分项:按分项平铺(Map<分项ID, 担保行[]> → 行序列)
            List<Map<String, Object>> guaranteeRows = new ArrayList<>();
            Map<?, ?> byItem = (Map<?, ?>) archive.get("guaranteesByItem");
            if (byItem != null) {
                for (Map.Entry<?, ?> e : byItem.entrySet()) {
                    for (Object g : (List<?>) e.getValue()) {
                        if (g instanceof Map<?, ?> gMap) {
                            Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) gMap);
                            row.put("pricingItemId", String.valueOf(e.getKey()));
                            guaranteeRows.add(row);
                        }
                    }
                }
            }
            writeTable(wb, "担保分项", watermark, guaranteeRows, List.of(
                    new Col("分项", "pricingItemId"),
                    new Col("担保方式", "guaranteeType"),
                    new Col("措施类型", "measureType"),
                    new Col("担保金额(万元)", "guaranteeAmount"),
                    new Col("措施扩展", "extJson")));

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("档案导出失败", e);
        }
    }

    // ---------- 私有 ----------

    private static void writeTable(Workbook wb, String sheetName, String watermark,
                                   List<Map<String, Object>> rows, List<Col> cols) {
        Sheet sheet = wb.createSheet(sheetName);
        int r = writeWatermark(sheet, watermark, 0);
        writeHeader(sheet, r++, cols.stream().map(Col::header).toList());
        if (rows != null) {
            for (Map<String, Object> data : rows) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < cols.size(); c++) {
                    row.createCell(c).setCellValue(str(pick(data, cols.get(c).keys())));
                }
            }
        }
        autosize(sheet, cols.size());
    }

    private static int writeWatermark(Sheet sheet, String watermark, int rowIdx) {
        Row row = sheet.createRow(rowIdx);
        Cell cell = row.createCell(0);
        cell.setCellValue(watermark);
        CellStyle style = sheet.getWorkbook().createCellStyle();
        Font font = sheet.getWorkbook().createFont();
        font.setItalic(true);
        font.setColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        cell.setCellStyle(style);
        return rowIdx + 1;
    }

    private static void writeHeader(Sheet sheet, int rowIdx, List<String> headers) {
        Row row = sheet.createRow(rowIdx);
        CellStyle style = sheet.getWorkbook().createCellStyle();
        Font font = sheet.getWorkbook().createFont();
        font.setBold(true);
        style.setFont(font);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(style);
        }
    }

    private static void autosize(Sheet sheet, int cols) {
        for (int i = 0; i < cols; i++) {
            sheet.setColumnWidth(i, 20 * 256);
        }
    }

    /** 多候选键取值 */
    private static Object pick(Map<String, Object> row, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
