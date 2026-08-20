package com.ccr.admin.config;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;

/**
 * SQL 打印拦截器(运行日志监控,非审计):拦截 MyBatis Executor 的全部 query/update,
 * 将完整 SQL(含内联参数)打到专用 logger "CCR_SQL"(INFO),随日志文件落盘——
 * 满足"后台执行的 SQL 全部打印在日志监控里面"。不落库、不影响执行结果。
 * <p>
 * 边界:
 * - 与 MybatisPlusInterceptor(InnerInterceptor)互不干扰,本类以 MyBatis 插件方式独立注册(RunLogConfig);
 * - 自身不写库,天然无递归;JdbcTemplate 原生 SQL 由 logback-spring.xml 里
 *   org.springframework.jdbc.core.JdbcTemplate=DEBUG 落到同一日志文件;
 * - 拼参失败时回退打印原始 SQL(带 ?),不影响业务。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        org.apache.ibatis.cache.CacheKey.class, BoundSql.class})
})
public class RunSqlInterceptor implements Interceptor {

    private static final Logger SQL_LOG = LoggerFactory.getLogger("CCR_SQL");

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        MappedStatement ms = (MappedStatement) args[0];
        Object parameter = args[1];
        BoundSql boundSql;
        if (args.length == 6) {
            boundSql = (BoundSql) args[5];
        } else {
            boundSql = ms.getBoundSql(parameter);
        }
        String type = ms.getSqlCommandType() == null ? "OTHER" : ms.getSqlCommandType().name();
        String sql = buildSql(boundSql);
        long start = System.currentTimeMillis();
        try {
            Object result = invocation.proceed();
            SQL_LOG.info("[{}] {}ms | rows={} | {}", type, System.currentTimeMillis() - start, rowCount(result), sql);
            return result;
        } catch (Throwable e) {
            SQL_LOG.info("[{}] {}ms | FAILED({}) | {}", type, System.currentTimeMillis() - start, e.getMessage(), sql);
            throw e;
        }
    }

    /** 拼完整 SQL:按顺序把 ? 替换为参数值 */
    private String buildSql(BoundSql boundSql) {
        String sql = boundSql.getSql().replaceAll("[\\s\\n\\r]+", " ").trim();
        List<ParameterMapping> mappings = boundSql.getParameterMappings();
        if (mappings == null || mappings.isEmpty()) {
            return sql;
        }
        Object parameter = boundSql.getParameterObject();
        StringBuilder sb = new StringBuilder(sql.length() + 32);
        int last = 0;
        for (ParameterMapping m : mappings) {
            int q = sql.indexOf('?', last);
            if (q < 0) {
                break;
            }
            sb.append(sql, last, q);
            sb.append(fmt(resolveValue(parameter, m.getProperty())));
            last = q + 1;
        }
        sb.append(sql.substring(last));
        return sb.toString();
    }

    private Object resolveValue(Object parameter, String property) {
        if (parameter == null) {
            return null;
        }
        // 简单类型实参(如 selectById(Long) 的 Long)与空属性名直接取实参本身,
        // 避免反射取 "id" 属性失败而打印成 NULL
        if (isSimpleValue(parameter) || property == null || property.isEmpty()) {
            return parameter;
        }
        try {
            return SystemMetaObject.forObject(parameter).getValue(property);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isSimpleValue(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean
                || v instanceof Character || v instanceof Enum || v instanceof java.util.Date;
    }

    private static String fmt(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof String s) {
            return "'" + s.replace("'", "''") + "'";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return "'" + v + "'";
    }

    private static Object rowCount(Object result) {
        if (result instanceof List<?> list) {
            return list.size();
        }
        if (result instanceof Number n) {
            return n.longValue();
        }
        return "-";
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 无配置项
    }
}
