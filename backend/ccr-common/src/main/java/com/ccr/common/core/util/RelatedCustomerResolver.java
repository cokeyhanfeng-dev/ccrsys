package com.ccr.common.core.util;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 关联人客户号兜底反查(增量021):按 (cert_type, cert_no) 反查数仓主数据行内客户号。
 * 前台关联人录入时 related_customer_no 可能为空(证件号已填、客户号未自动匹配上),
 * 归并前按证件号补全;仅服务于前台录入关联人(ccr_application_related_person),
 * 与数仓关系表 dw_customer_relation_snapshot 无关。
 * - USCC → caps_corp_cust_basic_info(对公)
 * - ID_CARD → caps_indv_cust_basic_info(对私)
 * 取数沿用 DataWarehouseService.findCorpByCertNo/findIndvByCertNo 同款 SQL(最新批次 + etl_md5 DESC)。
 */
public final class RelatedCustomerResolver {

    /** 证件类型:对公(统一社会信用代码) */
    public static final String CERT_TYPE_CORP = "USCC";
    /** 证件类型:对私(身份证) */
    public static final String CERT_TYPE_INDV = "ID_CARD";

    private RelatedCustomerResolver() {
    }

    /**
     * 按证件类型+证件号反查行内客户号;未命中返回 null。
     * certType 未知或为空时不反查(历史数据无证件类型,不做猜测反查,避免误配)。
     */
    public static String resolve(JdbcTemplate jdbcTemplate, String certType, String certNo) {
        if (certNo == null || certNo.isBlank()) {
            return null;
        }
        String table;
        if (CERT_TYPE_CORP.equals(certType)) {
            table = "caps_corp_cust_basic_info";
        } else if (CERT_TYPE_INDV.equals(certType)) {
            table = "caps_indv_cust_basic_info";
        } else {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT cust_no FROM " + table
                        + " WHERE cert_no = ? AND data_dt = (SELECT MAX(d2.data_dt) FROM " + table
                        + " d2 WHERE d2.cert_no = " + table + ".cert_no)"
                        + " ORDER BY etl_md5 DESC LIMIT 1", certNo);
        return rows.isEmpty() || rows.get(0).get("cust_no") == null
                ? null : rows.get(0).get("cust_no").toString();
    }

    /**
     * 批量兜底反查(就地补全):把 relatedCustomerNo 为空、但 certType/certNo 齐全的行反查补全。
     * 每行需含字段 relatedCustomerNo / certType / certNo(与审批详情 relatedPersons、
     * 承诺列表关联人行字段同名);不新增行、不影响其他字段。
     */
    public static void resolveBatch(JdbcTemplate jdbcTemplate, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            Object no = row.get("relatedCustomerNo");
            if (no != null && !no.toString().isBlank()) {
                continue;
            }
            Object certType = row.get("certType");
            Object certNo = row.get("certNo");
            if (certNo == null || certNo.toString().isBlank()) {
                continue;
            }
            String resolved = resolve(jdbcTemplate,
                    certType == null ? null : certType.toString(), certNo.toString());
            if (resolved != null) {
                row.put("relatedCustomerNo", resolved);
            }
        }
    }
}
