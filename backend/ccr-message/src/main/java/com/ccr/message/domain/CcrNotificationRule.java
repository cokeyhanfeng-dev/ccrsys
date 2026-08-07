package com.ccr.message.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 通知规则(ccr_notification_rule)——触发/渠道/频率/冷却/升级(§11.5、§11.6)
 * 触发等级: WATCH/AT_RISK/EXPIRED
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ccr_notification_rule", autoResultMap = true)
public class CcrNotificationRule extends BaseEntity {

    /** 规则编号(唯一) */
    private String ruleNo;

    /** 规则名称 */
    private String ruleName;

    /** 触发等级(WATCH/AT_RISK/EXPIRED) */
    private String triggerLevel;

    /** 渠道(SYSTEM 站内信=落库即达;其余渠道走 MessageSender 扩展点) */
    private String channel;

    /** 重复提醒间隔(小时) */
    private Integer repeatIntervalHours;

    /** 最大提醒次数 */
    private Integer maxRepeatCount;

    /** 冷却时间(小时),冷却期内同规则同接收人不再发送 */
    private Integer coolDownHours;

    /** 升级路径配置,如 {"AT_RISK":["DEPT_GM"],"EXPIRED":["VICE_PRESIDENT"]} */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> upgradeRuleJson;

    /** 消息模板,占位符 {planNo}/{customerNo}/{metricName}/{dataDt}/{achievementRatio}/{resultStatus} */
    private String messageTemplate;
}
