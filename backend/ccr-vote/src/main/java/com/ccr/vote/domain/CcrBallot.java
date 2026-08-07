package com.ccr.vote.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 实际票据(ccr_ballot)——一人一票,提交后不可修改(§7.4)
 * 人员信息密文落库+查询哈希,只允许表决服务和审计权限读取(§9.3)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_ballot")
public class CcrBallot extends BaseEntity {

    /** 表决批次主键 */
    private Long roundId;

    /** 定价分项主键 */
    private Long pricingItemId;

    /** 委员用户id密文 */
    private String voterUserIdCipher;

    /** 委员用户id查询哈希 */
    private String voterUserHash;

    /** APPROVE通过 / REJECT否决 */
    private String voteChoice;

    /** 意见 */
    private String voteComment;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 幂等键 */
    private String idempotencyKey;
}
