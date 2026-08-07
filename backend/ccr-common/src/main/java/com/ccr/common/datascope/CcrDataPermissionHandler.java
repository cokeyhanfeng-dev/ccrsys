package com.ccr.common.datascope;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

import java.util.Map;

/**
 * 数据权限 SQL 注入处理器(§5.4,受限全局)
 * <p>
 * 依赖 {@link DataScopeContext} 中的范围:<ul>
 *   <li>范围未设置或 ALL 级 → 返回 null(不注入);</li>
 *   <li>SELF 级 → 有 selfColumn 的表注入 {@code create_by = userId};</li>
 *   <li>DEPT 级 → 有 deptColumn 的表注入 {@code apply_branch_code LIKE '前缀%'}
 *       (机构编码前缀覆盖本支行及下辖网点);</li>
 * </ul>
 * 不在表白名单内的表返回 null——避免对无范围字段的表注入造成 SQL 报错。
 */
public class CcrDataPermissionHandler implements MultiDataPermissionHandler {

    /** 范围字段映射白名单:表名 → {DEPT 级前缀列, SELF 级本人列};字段缺失(null)则该级不注入 */
    private static final Map<String, TableScope> TABLE_SCOPES = Map.of(
            "ccr_application", new TableScope("apply_branch_code", "create_by"),
            "ccr_application_member", new TableScope(null, "create_by"),
            "ccr_application_commitment", new TableScope(null, "create_by"),
            "ccr_application_relation", new TableScope(null, "create_by")
    );

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        DataScope scope = DataScopeContext.get();
        if (scope == null || DataScope.LEVEL_ALL.equals(scope.getLevel())) {
            return null;
        }
        TableScope ts = TABLE_SCOPES.get(table.getName().toLowerCase());
        if (ts == null) {
            return null;
        }
        if (DataScope.LEVEL_SELF.equals(scope.getLevel()) && scope.getUserId() != null && ts.selfColumn != null) {
            return new EqualsTo(new Column(table, ts.selfColumn), new LongValue(scope.getUserId()));
        }
        if (DataScope.LEVEL_DEPT.equals(scope.getLevel()) && StrUtil.isNotBlank(scope.getOrgCodePrefix()) && ts.deptColumn != null) {
            Column col = new Column(table, ts.deptColumn);
            LikeExpression like = new LikeExpression();
            like.setLeftExpression(col);
            like.setRightExpression(new StringValue(scope.getOrgCodePrefix() + "%"));
            return like;
        }
        return null;
    }

    private static final class TableScope {
        final String deptColumn;
        final String selfColumn;

        TableScope(String deptColumn, String selfColumn) {
            this.deptColumn = deptColumn;
            this.selfColumn = selfColumn;
        }
    }
}
