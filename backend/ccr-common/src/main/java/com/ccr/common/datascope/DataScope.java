package com.ccr.common.datascope;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据权限范围(详设 §5.4 四级:ALL/DEPT/GROUP_MEMBER/SELF)
 * <p>
 * 由登录上下文计算后写入 {@link DataScopeContext},供 {@link CcrDataPermissionHandler}
 * 在 SQL 层注入过滤条件。level=ALL 时 orgCodePrefix/userId 可为空(不注入);
 * level=DEPT 时按 orgCodePrefix 机构编码前缀匹配(覆盖本支行及下辖网点);
 * level=SELF 时按 userId 过滤本人数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataScope {

    /** 全行(admin/总行行长/审计,不注入) */
    public static final String LEVEL_ALL = "ALL";
    /** 本机构及全部下级机构 */
    public static final String LEVEL_DEPT = "DEPT";
    /** 本人 */
    public static final String LEVEL_SELF = "SELF";

    /** ALL/DEPT/SELF */
    private String level;

    /** 机构编码前缀(DEPT 级有效) */
    private String orgCodePrefix;

    /** 用户id(SELF 级有效) */
    private Long userId;
}
