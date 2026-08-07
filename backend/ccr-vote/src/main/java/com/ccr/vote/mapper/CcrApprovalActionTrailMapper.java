package com.ccr.vote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.vote.domain.CcrApprovalActionTrail;

/**
 * 审批轨迹写入(ccr_approval_action)——仅计票/行长决策留痕插入(§14.7)
 */
public interface CcrApprovalActionTrailMapper extends BaseMapper<CcrApprovalActionTrail> {
}
