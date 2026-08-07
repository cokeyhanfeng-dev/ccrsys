package com.ccr.vote.read;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 申请承诺指标只读 Mapper(ccr_application_commitment,主数据在申请模块)——仅查询,禁止写
 */
@Mapper
public interface ApplicationCommitmentReadMapper extends BaseMapper<ApplicationCommitmentRead> {
}
