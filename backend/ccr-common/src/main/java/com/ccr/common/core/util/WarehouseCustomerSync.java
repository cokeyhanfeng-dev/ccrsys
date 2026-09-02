package com.ccr.common.core.util;

import cn.hutool.core.util.StrUtil;

import java.util.Map;

/**
 * 数仓客户主档行 → 客户人工快照(customer_info_json)权威回填(2026-09-02 #460)。
 *
 * <p>无客户号新增客户占位期,客户经理只能手工填写少量客户要素;数仓收录后,凡数仓主档行
 * ({@code caps_corp_cust_basic_info} / {@code caps_indv_cust_basic_info})可查出的字段,
 * 一律以数仓为权威整体覆盖人工快照对应键(用户拍板:以数仓为权威整体覆盖,非仅补空缺)。
 * 仅数仓列非空才覆盖;数仓未收录/为空的键保留人工值不动(「凡是能查出来的都回填」)。
 * 数仓列名按 {@code snake_case},customer_info_json 键为前端 {@code camelCase},与审批详情
 * 人工覆盖层(ApprovalController.overwriteCustomer)读取键一致。
 * 纯工具,无状态;供提交通道与审批回填通道共用。</p>
 */
public final class WarehouseCustomerSync {

    private WarehouseCustomerSync() {
    }

    /** 对公数仓列 → customer_info_json 键(仅覆盖人工覆盖层实际读取的键,避免污染前端回显) */
    private static final String[][] CORP_COLS = {
            {"cust_no", "customerNo"},
            {"cust_name", "customerName"},
            {"cert_no", "ucrCode"},
            {"entp_charic", "entpCharic"},
            {"blgd_idsty", "industry"},
            {"crdt_grd", "creditLevel"},
            {"ffthlv_class", "fiveLevelClass"},
            {"openact_org_nm", "openOrg"},
            {"openact_dt", "openDate"},
            {"basic_account_no", "basicAccount"},
    };

    /** 对私数仓列 → customer_info_json 键(证件类型 cert_tp 数仓为 ID/UNIFIED,前端 idType 为 ID_CARD/USCC,不覆盖) */
    private static final String[][] INDV_COLS = {
            {"cust_no", "customerNo"},
            {"cust_nm", "customerName"},
            {"cert_no", "idNo"},
            {"ocupn", "occupation"},
            {"whlyr_incm", "annualIncome"},
            {"mrrg_sittn", "maritalStatus"},
            {"tel_no", "phone"},
            {"ffthlv_class", "fiveLevelClass"},
            {"opnact_org_nm", "openOrg"},
            {"opnact_dt", "openDate"},
    };

    /**
     * 用数仓主档行权威覆盖人工快照对应键(就地改 target,不新增 target 未有的键)。
     *
     * @param target customer_info_json 解析后的键值容器(Map 形态,可传 hutool JSONObject)
     * @param dw     数仓主档行(列名 snake_case);为 null 时无事发生
     * @param indv   true=对私(caps_indv_cust_basic_info),false=对公
     */
    public static void applyCustomerInfo(Map<String, Object> target, Map<String, Object> dw, boolean indv) {
        if (target == null || dw == null || dw.isEmpty()) {
            return;
        }
        String[][] cols = indv ? INDV_COLS : CORP_COLS;
        for (String[] col : cols) {
            Object v = dw.get(col[0]);
            if (v == null) {
                continue; // 数仓未查出/为空:保留人工值
            }
            String s = String.valueOf(v).trim();
            if (s.isEmpty() || "null".equals(s)) {
                continue;
            }
            target.put(col[1], normalize(v));
        }
    }

    /**
     * 快照客户行 core_json 的整行数据源:把数仓主档行(全部非空列)铺进快照 JSON,
     * 供审批详情快照路径展示数仓宽字段(企业规模/员工数/总资产/地址/成立日等人工快照没有的列)。
     * core_json 键为数仓 snake_case(与 addSnapshotRecord 落库一致)。
     *
     * @param core 快照客户行 core_json 解析对象(Map 形态)
     * @param dw   数仓主档行
     */
    public static void applyWarehouseRow(Map<String, Object> core, Map<String, Object> dw) {
        if (core == null || dw == null || dw.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> e : dw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            core.put(e.getKey(), normalize(e.getValue()));
        }
    }

    /** 值归一:日期/时间转字符串(避免 JSONObject 把 java.sql.Date 序列化成 epoch),其余原样 */
    private static Object normalize(Object v) {
        // §2026-09-02 #460 E2E 实测修复:java.sql.Date.toInstant() 在 JDK 直接抛
        // UnsupportedOperationException(message=null),此前导致客户其他信息回填在此处被静默 catch,
        // 实际只回填了 customerNo。分支必须先判 java.sql.Date(java.util.Date 子类),用 toLocalDate()。
        if (v instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().toString();
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return v;
    }

    /** 字符串判断非空(工具内部用,避免上游散落 StrUtil 判断) */
    public static boolean notBlank(Object v) {
        return v != null && StrUtil.isNotBlank(String.valueOf(v));
    }
}
