package com.ccr.vote.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 批次分项(ccr_vote_round_item)——一个定价分项同一时间只属一个进行中批次(§9.3)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_vote_round_item")
public class CcrVoteRoundItem extends BaseEntity {

    /** 表决批次主键 */
    private Long roundId;

    /** 定价分项主键 */
    private Long pricingItemId;

    /** 批内顺序 */
    private Integer sequenceNo;
}
