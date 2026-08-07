package com.ccr.common.datascope;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据权限 SQL 注入单测(§5.4 受限全局)
 * SELF→create_by = userId;DEPT→apply_branch_code LIKE '前缀%';ALL/未设置/白名单外不注入。
 */
class CcrDataPermissionHandlerTest {

    private final CcrDataPermissionHandler handler = new CcrDataPermissionHandler();

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    private Expression inject(String table, DataScope scope) {
        if (scope != null) {
            DataScopeContext.set(scope);
        }
        return handler.getSqlSegment(new Table(table), null, "test");
    }

    @Test
    void self_ccrApplication_注入本人条件() {
        Expression expr = inject("ccr_application", new DataScope(DataScope.LEVEL_SELF, null, 1000L));
        String sql = expr.toString();
        assertTrue(sql.contains("create_by"));
        assertTrue(sql.contains("1000"));
        assertTrue(sql.contains("ccr_application"));
    }

    @Test
    void dept_ccrApplication_注入支行前缀() {
        Expression expr = inject("ccr_application", new DataScope(DataScope.LEVEL_DEPT, "100201", 1000L));
        String sql = expr.toString();
        assertTrue(sql.contains("apply_branch_code"));
        assertTrue(sql.contains("100201%"));
    }

    @Test
    void allLevel_不注入() {
        assertNull(inject("ccr_application", new DataScope(DataScope.LEVEL_ALL, null, 1000L)));
    }

    @Test
    void context未设置_不注入() {
        assertNull(inject("ccr_application", null));
    }

    @Test
    void 白名单外表_不注入() {
        assertNull(inject("sys_user", new DataScope(DataScope.LEVEL_SELF, null, 1000L)));
    }

    @Test
    void self_member表_注入本人条件() {
        Expression expr = inject("ccr_application_member", new DataScope(DataScope.LEVEL_SELF, null, 1000L));
        assertTrue(expr.toString().contains("create_by"));
    }

    @Test
    void dept_无支行列的表_不注入() {
        // ccr_application_member 无 apply_branch_code,DEPT 级不注入避免 SQL 报错
        assertNull(inject("ccr_application_member", new DataScope(DataScope.LEVEL_DEPT, "100201", 1000L)));
    }

    @Test
    void dept_前缀为空_不注入() {
        assertNull(inject("ccr_application", new DataScope(DataScope.LEVEL_DEPT, null, 1000L)));
    }
}
