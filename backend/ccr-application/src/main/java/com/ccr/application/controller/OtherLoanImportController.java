package com.ccr.application.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.hutool.core.convert.Convert;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import com.ccr.common.core.domain.R;
import com.ccr.common.exception.ServiceException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 他行融资明细 Excel 导入(PRD F1:融资情况管理·Excel 解析;D13f 按申请逐笔录入)
 * 导入解析返回行列表供前端确认/存档;不回写数仓权威数据
 *
 * <p>2026-09-17 生产事故修复:原实现按固定列位读、只跳过第 1 行,且数值解析失败时原样返回字符串,
 * 导致客户上传征信报告等非模板文件(前几行为报告头/表名)时,文字被当作金额流到前端,
 * 提交/存草稿时后端 BigDecimal 反序列化失败(HttpMessageNotReadableException),该单彻底卡死。
 * 现改为:表头不符/数值列非法一律整单拒绝并提示具体行列,不返回任何部分数据。
 */
@RestController
@RequestMapping("/ccr/other-loans")
@SaCheckRole(value = {"customer_manager", "admin"}, mode = SaMode.OR)
public class OtherLoanImportController {

    /** 模板表头:与 frontend/public/templates/other-loans-template.xlsx 第 1 行一致 */
    private static final String[] TEMPLATE_HEADERS = {"融资机构", "授信额", "已用额", "余额", "年化利率"};

    /** 数值列名(与 TEMPLATE_HEADERS 同序;第 0 列为机构名,非数值),用于报错时指明是哪一列 */
    private static final String[] NUMBER_COLUMNS = {null, "授信额(万元)", "已用额(万元)", "余额(万元)", "年化利率%"};

    /**
     * Excel 导入解析:约定列顺序 融资机构|授信额(万元)|已用额(万元)|余额(万元)|年化利率%
     * <p>校验不通过一律整单拒绝并指明行/列,不返回部分数据——宁可不导,也不让脏数据流到前端卡死提交。
     */
    @PostMapping("/import")
    public R<List<Map<String, Object>>> importExcel(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException(400, "请选择 Excel 文件");
        }
        try {
            ExcelReader reader = ExcelUtil.getReader(file.getInputStream());
            List<List<Object>> rows = reader.read();
            reader.close();
            if (rows == null || rows.isEmpty()) {
                throw new ServiceException(400, "文件内容为空,请使用「他行融资明细导入模板」填写后导入");
            }
            requireTemplateHeader(rows.get(0));
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) { // 跳过表头
                List<Object> row = rows.get(i);
                if (isBlankRow(row)) {
                    continue;
                }
                int excelRowNo = i + 1; // 折算为 Excel 界面行号:第 1 行为表头
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("lenderName", cell(row, 0));
                item.put("creditAmount", requireNum(cell(row, 1), excelRowNo, NUMBER_COLUMNS[1]));
                item.put("usedAmount", requireNum(cell(row, 2), excelRowNo, NUMBER_COLUMNS[2]));
                item.put("balanceAmount", requireNum(cell(row, 3), excelRowNo, NUMBER_COLUMNS[3]));
                item.put("annualRate", requireNum(cell(row, 4), excelRowNo, NUMBER_COLUMNS[4]));
                item.put("inputMode", "EXCEL");
                result.add(item);
            }
            if (result.isEmpty()) {
                throw new ServiceException(400, "未解析到有效数据行,请确认已按模板填写融资明细");
            }
            return R.ok(result);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("Excel 解析失败: " + e.getMessage());
        }
    }

    /**
     * 表头校验:第 1 行须为本模板表头。
     * 用包含匹配而非全等——容忍客户在表头格内多写说明字,但仍能挡住"完全不是这个模板"的文件
     * (征信报告第 1 行是报告标题,必然过不了)。
     */
    private void requireTemplateHeader(List<Object> head) {
        for (int c = 0; c < TEMPLATE_HEADERS.length; c++) {
            String actual = cell(head, c);
            if (!actual.contains(TEMPLATE_HEADERS[c])) {
                throw new ServiceException(400, "文件格式与导入模板不一致:第 " + (c + 1)
                        + " 列应为「" + TEMPLATE_HEADERS[c] + "」,实际为「"
                        + (actual.isEmpty() ? "(空)" : actual) + "」。请点击「模板下载」获取标准模板后重新导入");
            }
        }
    }

    /** 整行全空(含行对象为空/越界)→ 视为空行跳过;Excel 尾部常带空行,不应据此报错 */
    private boolean isBlankRow(List<Object> row) {
        if (row == null || row.isEmpty()) {
            return true;
        }
        for (Object o : row) {
            if (o != null && !o.toString().trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String cell(List<Object> row, int idx) {
        return idx < row.size() && row.get(idx) != null ? row.get(idx).toString().trim() : "";
    }

    /**
     * 数值列取值:空 → 空串(允许不填);非空但转不成数字 → 整单拒绝并指明行/列。
     * <p>原实现解析失败原样返回字符串(return v),是本次生产事故的根源:非法文字得以流入前端。
     */
    private String requireNum(String v, int excelRowNo, String columnName) {
        if (v == null || v.isBlank()) {
            return "";
        }
        try {
            return Convert.toBigDecimal(v).stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            throw new ServiceException(400, "第 " + excelRowNo + " 行「" + columnName
                    + "」不是有效数字:[" + v + "],请改为纯数字后重新导入");
        }
    }
}
