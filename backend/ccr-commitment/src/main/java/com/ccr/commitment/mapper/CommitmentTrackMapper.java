package com.ccr.commitment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.commitment.domain.CcrCommitmentTrack;
import org.apache.ibatis.annotations.Mapper;

/**
 * 承诺跟踪表 Mapper(v2 在途行+终态行)
 */
@Mapper
public interface CommitmentTrackMapper extends BaseMapper<CcrCommitmentTrack> {
}
