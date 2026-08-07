package com.ccr.vote.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 委员任务(ccr_vote_assignment)——批次和委员唯一;替补由授权人员发起并记录(§7.4)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_vote_assignment")
public class CcrVoteAssignment extends BaseEntity {

    /** 表决批次主键 */
    private Long roundId;

    /** 委员用户id(批次创建时冻结) */
    private Long voterUserId;

    /** 匿名编号(如A-F) */
    private String voterAnonymNo;

    /** 替补自原委员(替补场景) */
    private Long substituteFromUserId;

    /** 替补原因 */
    private String substituteReason;

    /** 全部项提交时间 */
    private LocalDateTime submitTime;
}
