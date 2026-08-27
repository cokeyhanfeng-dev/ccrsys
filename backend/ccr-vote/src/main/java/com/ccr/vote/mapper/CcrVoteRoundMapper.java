package com.ccr.vote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.vote.domain.CcrVoteRound;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * CcrVoteRound Mapper
 */
@Mapper
public interface CcrVoteRoundMapper extends BaseMapper<CcrVoteRound> {

    /**
     * 带行锁读(统一计票串行化):锁定批次行,保证同批次投票/超时计票串行,
     * 后到事务获得锁后读到前序已提交票数,避免"最后两票并发均判未全员投完"的丢失更新
     */
    @Select("SELECT * FROM ccr_vote_round WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    CcrVoteRound selectByIdForUpdate(@Param("id") Long id);
}
