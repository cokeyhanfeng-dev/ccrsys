package com.ccr.application.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

/**
 * 客户号工具(新增客户无客户号支持,2026-08-20 #017)
 *
 * <p>新增客户可能没有数仓客户号,以占位号兜底 NOT NULL 约束(pricing_customer_no 等),
 * 提交时按证件号反查数仓回填真实客户号,未命中则保留占位号走人工快照(MANUAL)。</p>
 *
 * <p>占位号规则:前缀 {@code NEW} + 证件号后 6 位(如 {@code NEW123456})。
 * 撞号不致命:数仓反查按完整证件号匹配,占位号仅作过渡标识与 NOT NULL 兜底。</p>
 */
public final class CustomerNoUtil {

    private CustomerNoUtil() {
    }

    /** 占位号前缀(数仓新增客户 cust_class=NEW,取首字母) */
    public static final String PREFIX = "NEW";

    /** 按证件号生成占位客户号;证件号为空返回 null */
    public static String placeholderCustomerNo(String certNo) {
        if (StrUtil.isBlank(certNo)) {
            return null;
        }
        String normalized = certNo.trim();
        String tail = normalized.length() > 6 ? normalized.substring(normalized.length() - 6) : normalized;
        return PREFIX + tail;
    }

    /**
     * 集团成员占位号:{@code NEW} + 完整证件号(单户用后 6 位,集团成员必须完整证件号)。
     *
     * <p>集团成员客户号落 {@code ccr_application_member.member_customer_no} /
     * {@code ccr_group_member.member_customer_no} / {@code ccr_commitment_member_alloc.member_customer_no},
     * 三者均有唯一键(uk_app_member/uk_gm_group_member/uk_alloc),后 6 位同申请多成员易撞号导致
     * INSERT 失败;完整证件号保证同申请内唯一(前端已按证件号去重)。</p>
     */
    public static String placeholderMemberCustomerNo(String certNo) {
        if (StrUtil.isBlank(certNo)) {
            return null;
        }
        return PREFIX + certNo.trim();
    }

    /** 是否为占位客户号 */
    public static boolean isPlaceholder(String customerNo) {
        return StrUtil.isNotBlank(customerNo) && customerNo.startsWith(PREFIX);
    }

    /**
     * 从 customer_info_json 解析证件号
     *
     * @param customerInfoJson 人工客户信息快照 JSON
     * @param customerScope    INDIVIDUAL(对私取 idNo)/CORPORATE(对公取 ucrCode)
     */
    public static String certNoFromInfoJson(String customerInfoJson, String customerScope) {
        if (StrUtil.isBlank(customerInfoJson)) {
            return null;
        }
        try {
            JSONObject json = JSONUtil.parseObj(customerInfoJson);
            return "INDIVIDUAL".equals(customerScope) ? json.getStr("idNo") : json.getStr("ucrCode");
        } catch (Exception e) {
            return null;
        }
    }

    /** 客户号真实值(占位号回填后移除占位标识;非占位号原样返回) */
    public static String realValue(String customerNo) {
        return isPlaceholder(customerNo) ? null : customerNo;
    }
}
