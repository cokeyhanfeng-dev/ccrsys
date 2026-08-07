package com.ccr.message.service.recipient;

import java.util.List;

/**
 * 接收人解析器(§11.6 动态对象,可扩展)
 * 新增接收人类型时增加一个实现类即可,由 RecipientResolverRegistry 自动收集
 */
public interface RecipientResolver {

    /** 是否支持该接收对象类型(recipient_type) */
    boolean supports(String recipientType);

    /**
     * 解析接收人标识列表(用户id字符串)
     *
     * @param recipientType  接收对象类型
     * @param recipientValue 规则配置的对象值(角色编码/用户id/机构岗位等,可空)
     * @param context        触发上下文
     */
    List<String> resolve(String recipientType, String recipientValue, RecipientContext context);
}
