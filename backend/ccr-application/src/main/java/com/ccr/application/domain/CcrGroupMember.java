package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 手工集团成员(ccr_group_member)——数仓未统计的公司,手动补录;
 * 无 caps_corp 主数据反查,member_name 手工录入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_group_member")
public class CcrGroupMember extends BaseEntity {

    /** 所属手工集团编号 */
    private String groupNo;

    /** 成员客户号(手工补录) */
    private String memberCustomerNo;

    /** 成员公司名称(手工录入,无数仓主数据) */
    private String memberName;

    /** 成员角色(CORE核心/GENERAL一般) */
    private String memberRole;

    /** 控制关系(控股/参股等) */
    private String controlRelation;

    /** 关系起始(空=无限制) */
    private LocalDate relationStart;

    /** 关系结束(空=在团) */
    private LocalDate relationEnd;
}
