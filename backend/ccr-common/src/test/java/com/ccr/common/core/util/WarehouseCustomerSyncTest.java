package com.ccr.common.core.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 数仓主档行 → 客户人工快照权威回填(WarehouseCustomerSync)单元测试。
 *
 * <p>§2026-09-02 #460 E2E 实测回归:数仓列含 {@code java.sql.Date}(data_dt/openact_dt 等),
 * 旧 normalize 走 {@code java.sql.Date.toInstant()} 在 JDK 直接抛
 * {@code UnsupportedOperationException(message=null)},使「客户其他信息回填」被静默 catch,
 * 实际只回填了 customerNo。本用例锁死 Date → "yyyy-MM-dd" 字符串且不抛。</p>
 */
class WarehouseCustomerSyncTest {

    /** 模拟 JDBC 数仓主档行(SELECT * → Map,列名 snake_case,含 Date/Decimal/Integer) */
    private Map<String, Object> corpDwRow() {
        Map<String, Object> dw = new LinkedHashMap<>();
        dw.put("etl_md5", "a1b2c3");
        dw.put("data_dt", Date.valueOf("2026-09-01"));
        dw.put("cust_no", "CUST8888");
        dw.put("cust_name", "宜兴E2E占位回填测试有限公司");
        dw.put("cert_tp", "UNIFIED");
        dw.put("cert_no", "9132888888");
        dw.put("entp_charic", "SOE");
        dw.put("entp_scale", "中型");
        dw.put("blgd_idsty", "G");
        dw.put("crdt_grd", "AAA");
        dw.put("ffthlv_class", "010");
        dw.put("rest_addr", "宜兴市环科园路1号");
        dw.put("rest_asts", new BigDecimal("1234.5000"));
        dw.put("entp_empe_num", 12);
        dw.put("openact_org_no", "1019");
        dw.put("openact_org_nm", "江苏宜兴农村商业银行高塍支行");
        dw.put("openact_dt", Date.valueOf("2026-09-01"));
        dw.put("basic_account_no", "6220001234");
        dw.put("cust_class", "EXISTING");
        dw.put("mgr_no", "02301082");
        return dw;
    }

    private Map<String, Object> corpInfoJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("customerNo", "NEW888888");
        json.put("customerName", "cust0005");
        json.put("entpCharic", "NON_SOE");
        json.put("ucrCode", "9132888888");
        json.put("creditLevel", "AA+");
        json.put("fiveLevelClass", "010");
        json.put("openOrg", "");
        json.put("openDate", "");
        json.put("basicAccount", "");
        return json;
    }

    /** 对公 applyCustomerInfo:凡数仓非空键整体覆盖人工快照;Date 列转字符串且不抛 */
    @Test
    void applyCustomerInfo_overridesNonNullWarehouseColsWithDateNormalized() {
        Map<String, Object> json = corpInfoJson();
        WarehouseCustomerSync.applyCustomerInfo(json, corpDwRow(), false);

        assertEquals("宜兴E2E占位回填测试有限公司", json.get("customerName"));
        assertEquals("SOE", json.get("entpCharic"));
        assertEquals("AAA", json.get("creditLevel"));
        assertEquals("010", json.get("fiveLevelClass"));
        assertEquals("江苏宜兴农村商业银行高塍支行", json.get("openOrg"));
        assertEquals("6220001234", json.get("basicAccount"));
        // 回归点:java.sql.Date.openact_dt → "2026-09-01"(修复前此处抛 UOE 被静默 catch,键保持 ""/旧值)
        assertEquals("2026-09-01", json.get("openDate"));
    }

    /** 数仓未收录/为空的键保留人工值不动 */
    @Test
    void applyCustomerInfo_keepsManualValueWhenWarehouseNull() {
        Map<String, Object> json = corpInfoJson();
        Map<String, Object> dw = corpDwRow();
        dw.put("cust_name", null);          // 数仓空:名称保留人工
        dw.put("entp_charic", "");          // 空串同空
        dw.put("crdt_grd", "null");         // 字面 null 同空
        WarehouseCustomerSync.applyCustomerInfo(json, dw, false);

        assertEquals("cust0005", json.get("customerName"));
        assertEquals("NON_SOE", json.get("entpCharic"));
        assertEquals("AA+", json.get("creditLevel"));
    }

    /** 对公数仓无 date 键时人工值不受影响,仍覆盖其它非空键 */
    @Test
    void applyCustomerInfo_copiesWarehouseNullDateKeepsManual() {
        Map<String, Object> json = corpInfoJson();
        Map<String, Object> dw = corpDwRow();
        dw.put("openact_dt", null);
        WarehouseCustomerSync.applyCustomerInfo(json, dw, false);

        assertEquals("宜兴E2E占位回填测试有限公司", json.get("customerName"));
        assertEquals("", json.get("openDate")); // 数仓无开户日:保留人工空
    }

    /** applyWarehouseRow:数仓全部非空列铺进快照 core_json(宽字段),Date 归一为字符串不抛 */
    @Test
    void applyWarehouseRow_spreadsAllNonNullColsWithDateNormalized() {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("cust_no", "CUST8888");
        core.put("data_source", "MANUAL");
        WarehouseCustomerSync.applyWarehouseRow(core, corpDwRow());

        assertEquals("EXISTING", core.get("cust_class"));
        assertEquals("中型", core.get("entp_scale"));
        assertEquals(12, core.get("entp_empe_num"));
        assertEquals(new BigDecimal("1234.5000"), core.get("rest_asts"));
        assertEquals("宜兴市环科园路1号", core.get("rest_addr"));
        assertEquals("2026-09-01", core.get("data_dt"));  // 回归点:Date 不再抛且为字符串
        assertEquals("2026-09-01", core.get("openact_dt"));
        // 不删除调用方自有键(data_source 移除由调用方语义决定)
        assertEquals("MANUAL", core.get("data_source"));
    }

    /** dw 为空 Map 时无事发生(调用方容错) */
    @Test
    void applyCustomerInfo_emptyDwIsNoop() {
        Map<String, Object> json = corpInfoJson();
        WarehouseCustomerSync.applyCustomerInfo(json, new LinkedHashMap<>(), false);
        assertEquals("cust0005", json.get("customerName"));

        Map<String, Object> core = new LinkedHashMap<>();
        core.put("cust_no", "CUST8888");
        WarehouseCustomerSync.applyWarehouseRow(core, new LinkedHashMap<>());
        assertEquals("CUST8888", core.get("cust_no"));
    }

    /** 显式守卫:直接对 java.sql.Date 调 toInstant 在 JDK 抛 UOE,证明修复分支必须先判 sql.Date */
    @Test
    void javaSqlDateToInstantIsUnsupported() {
        Date d = Date.valueOf("2026-09-01");
        assertThrows(UnsupportedOperationException.class, d::toInstant);
    }
}
