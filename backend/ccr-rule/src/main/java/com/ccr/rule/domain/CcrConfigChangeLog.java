package com.ccr.rule.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 配置变更审计日志(ccr_config_change_log,§8A.2)
 * LPR/权限矩阵/利率规则集/产品边界四类配置的 create/submit/publish/disable/reject 全量留痕,
 * 审计人员可按配置域/记录/时间查询变更历史
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_config_change_log")
public class CcrConfigChangeLog extends BaseEntity {

    /** 配置域:LPR/MATRIX/RULE_SET/PRODUCT_LIMIT */
    private String configType;

    /** 配置记录主键 */
    private Long configId;

    /** 动作:CREATE/SUBMIT/PUBLISH/DISABLE/REJECT */
    private String action;

    /** 变更前快照(JSON) */
    private String oldJson;

    /** 变更后快照(JSON) */
    private String newJson;

    /** 复核/驳回意见(驳回必填) */
    private String opinion;

    /** 操作人 */
    private Long operatorId;

    /** 操作时间 */
    private LocalDateTime operateTime;
}
