package com.ccr.application.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
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
 */
@RestController
@RequestMapping("/ccr/other-loans")
@SaCheckRole("customer_manager")
public class OtherLoanImportController {

    /**
     * Excel 导入解析:约定列顺序 融资机构|授信额(万元)|已用额(万元)|余额(万元)|年化利率%
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
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) { // 跳过表头
                List<Object> row = rows.get(i);
                if (row == null || row.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("lenderName", cell(row, 0));
                item.put("creditAmount", num(cell(row, 1)));
                item.put("usedAmount", num(cell(row, 2)));
                item.put("balanceAmount", num(cell(row, 3)));
                item.put("annualRate", num(cell(row, 4)));
                item.put("inputMode", "EXCEL");
                result.add(item);
            }
            return R.ok(result);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("Excel 解析失败: " + e.getMessage());
        }
    }

    private String cell(List<Object> row, int idx) {
        return idx < row.size() && row.get(idx) != null ? row.get(idx).toString().trim() : "";
    }

    private String num(String v) {
        if (v == null || v.isBlank()) {
            return "";
        }
        try {
            return Convert.toBigDecimal(v).stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return v;
        }
    }
}
