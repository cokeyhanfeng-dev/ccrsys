package com.ccr.message.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 提醒对象规则(ccr_notification_recipient)——动态解析接收人(§11.6)
 * recipient_type: CUSTOMER_MANAGER/GROUP_MANAGER/MEMBER_MANAGER/BRANCH_MANAGER/GROUP_ORG_LEADER/
 * ORIGINAL_APPROVER/ORIGINAL_VOTER/DEPT_GM/VICE_PRESIDENT/ROLE/USER/ORG_POST
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_notification_recipient")
public class CcrNotificationRecipient extends BaseEntity {

    /** 通知规则主键 */
    private Long ruleId;

    /** 接收对象类型 */
    private String recipientType;

    /** 对象值(角色编码/用户id/机构岗位,格式见各 RecipientResolver) */
    private String recipientValue;

    /** 适用预警等级(空=全部;高等级按规则升级配置追加) */
    private String levelCondition;
}
