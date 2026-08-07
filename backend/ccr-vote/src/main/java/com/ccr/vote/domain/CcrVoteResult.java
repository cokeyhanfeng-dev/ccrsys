package com.ccr.vote.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 分项计票结果(ccr_vote_result)——六人全部提交后一次性生成(§9.3)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_vote_result")
public class CcrVoteResult extends BaseEntity {

    /** 表决批次主键 */
    private Long roundId;

    /** 定价分项主键 */
    private Long pricingItemId;

    /** 通过所需票数 */
    private Integer requiredCount;

    /** 已提交票数 */
    private Integer submittedCount;

    /** 赞成数 */
    private Integer approveCount;

    /** 否决数 */
    private Integer rejectCount;

    /** PASS / FAIL */
    private String result;

    /** 计票时间 */
    private LocalDateTime countTime;
}
