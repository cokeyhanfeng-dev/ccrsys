package com.ccr.commitment.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 跟踪策略版本(ccr_tracking_policy_version)——生效区间不重叠(§11.5)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ccr_tracking_policy_version", autoResultMap = true)
public class CcrTrackingPolicyVersion extends BaseEntity {

    /** 策略主表主键 */
    private Long policyId;

    /** 版本号(如V1) */
    private String versionCode;

    /** 生效时间 */
    private LocalDateTime effectiveFrom;

    /** 失效时间(空=有效) */
    private LocalDateTime effectiveTo;

    /** 校验频率 */
    private String checkFrequency;

    /** 首次校验时间 */
    private LocalDateTime firstCheckTime;

    /** 数据容忍天数(超过产出 DATA_PENDING) */
    private Integer dataToleranceDays;

    /** 补跑范围 */
    private String backfillScope;

    /** 阈值/提醒等扩展配置 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configJson;
}
