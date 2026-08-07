package com.ccr.vote.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 表决批次(ccr_vote_round)——6人名单批次创建时冻结(§7.4)
 * 状态: CREATED/VOTING/COUNTING/PASSED/FAILED/CANCELLED
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_vote_round")
public class CcrVoteRound extends BaseEntity {

    /** 所属申请 */
    private Long applicationId;

    /** 申请内顺序编号(唯一) */
    private Integer roundNo;

    /** 批次名称 */
    private String roundName;

    /** 应投人数(默认6) */
    private Integer voterCount;

    /** 通过所需票数(默认4) */
    private Integer requiredCount;

    /** 批次开始时间 */
    private LocalDateTime roundStartTime;

    /** 批次结束时间 */
    private LocalDateTime roundEndTime;

    /** 创建说明 */
    private String createReason;
}
