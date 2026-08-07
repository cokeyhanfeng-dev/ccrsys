package com.ccr.common.datascope;

/**
 * 数据权限范围上下文(ThreadLocal)
 * <p>
 * 仅在标注 {@link com.ccr.common.datascope.annotation.DataScope} 的方法执行期间写入,
 * 未写入(=null)时 {@link CcrDataPermissionHandler} 不注入任何条件——已验证功能零影响。
 * 使用方必须在 finally 中 {@link #clear()},防止线程池复用线程污染后续请求。
 */
public final class DataScopeContext {

    private static final ThreadLocal<DataScope> HOLDER = new ThreadLocal<>();

    private DataScopeContext() {
    }

    public static void set(DataScope scope) {
        HOLDER.set(scope);
    }

    public static DataScope get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
