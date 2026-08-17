package com.ccr.common.core.util;

import java.util.regex.Pattern;

/**
 * 密码工具:强密码校验 + 统一初始密码(强密码认证改造,建用户/改用户/改密三处复用)
 */
public final class PasswordUtil {

    private PasswordUtil() {
    }

    /** 统一初始密码(所有存量账号/新建未填密码用户首登用,本身已满足强规则) */
    public static final String INIT_PASSWORD = "Yxnsh@1a3s";

    /** INIT_PASSWORD 的 BCrypt 哈希(一次性生成固定写入,不落明文) */
    public static final String INIT_PASSWORD_HASH = "$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi";

    /** 强密码:不少于8位,必须含大写字母/小写字母/特殊字符(非字母数字且非空白),不强制数字 */
    private static final Pattern STRONG =
            Pattern.compile("^(?=.*[A-Z])(?=.*[a-z])(?=.*[^A-Za-z0-9\\s]).{8,}$");

    public static boolean isStrong(String raw) {
        return raw != null && STRONG.matcher(raw).matches();
    }
}
