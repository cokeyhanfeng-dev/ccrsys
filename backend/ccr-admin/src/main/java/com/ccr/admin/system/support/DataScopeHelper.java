package com.ccr.admin.system.support;

import cn.hutool.core.util.StrUtil;
import com.ccr.admin.system.domain.CcrSysDept;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysDeptMapper;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

/**
 * 数据权限范围计算(详设 §5.4 四级:ALL/DEPT/GROUP_MEMBER/SELF)
 * <p>
 * 现行口径:
 * <ul>
 *   <li>ALL  —— president/auditor/admin,全行;</li>
 *   <li>DEPT —— branch_manager 按所属支行 org_code 前缀匹配(覆盖本支行及下辖网点);
 *       dept_gm/vice_president 按本部门 org_code 前缀匹配;</li>
 *   <li>SELF —— customer_manager 及其余角色,仅本人。</li>
 * </ul>
 * 机构范围统一按 org_code 编码前缀匹配(LIKE '前缀%'),供后续模块在 SQL 层注入过滤条件。
 */
@Component
public class DataScopeHelper {

    /** 全行 */
    public static final String LEVEL_ALL = "ALL";
    /** 本机构及全部下级机构 */
    public static final String LEVEL_DEPT = "DEPT";
    /** 本人 */
    public static final String LEVEL_SELF = "SELF";

    @Resource
    private CcrSysUserMapper userMapper;

    @Resource
    private CcrSysDeptMapper deptMapper;

    /** 按登录人id计算数据权限范围 */
    public DataScope compute(Long userId) {
        if (userId == null) {
            return new DataScope(LEVEL_SELF, null, null);
        }
        CcrSysUser user = userMapper.selectById(userId);
        if (user == null || "1".equals(user.getDelFlag())) {
            return new DataScope(LEVEL_SELF, null, userId);
        }
        return compute(user);
    }

    /** 按用户实体计算数据权限范围 */
    public DataScope compute(CcrSysUser user) {
        String role = user.getRoleCode() == null ? "" : user.getRoleCode();
        switch (role) {
            case "president":
            case "auditor":
            case "admin":
                return new DataScope(LEVEL_ALL, null, user.getId());
            case "branch_manager":
                // 支行行长:本支行及下辖网点,按支行编码前缀匹配(§5.4)
                return new DataScope(LEVEL_DEPT, resolveBranchCode(user.getOrgId()), user.getId());
            case "dept_gm":
            case "vice_president":
                // 部门总经理/分管行领导:本部门,按本部门编码前缀匹配
                return new DataScope(LEVEL_DEPT, resolveOrgCode(user.getOrgId()), user.getId());
            default:
                return new DataScope(LEVEL_SELF, null, user.getId());
        }
    }

    /** 支行编码:BRANCH=自身orgCode;NETWORK=所属支行branchCode;其他兜底自身orgCode */
    private String resolveBranchCode(Long orgId) {
        CcrSysDept dept = orgId == null ? null : deptMapper.selectById(orgId);
        if (dept == null) {
            return null;
        }
        return StrUtil.isNotBlank(dept.getBranchCode()) ? dept.getBranchCode() : dept.getOrgCode();
    }

    private String resolveOrgCode(Long orgId) {
        CcrSysDept dept = orgId == null ? null : deptMapper.selectById(orgId);
        return dept == null ? null : dept.getOrgCode();
    }

    /**
     * 数据权限范围
     * level=ALL 时 orgCodePrefix 为 null(不过滤);
     * level=DEPT 时按 orgCodePrefix 前缀匹配机构编码;
     * level=SELF 时按 userId 过滤本人数据
     */
    @Data
    @AllArgsConstructor
    public static class DataScope {
        /** ALL/DEPT/SELF */
        private String level;
        /** 机构编码前缀(DEPT 级有效) */
        private String orgCodePrefix;
        /** 用户id(SELF 级有效) */
        private Long userId;
    }
}
