package com.ccr.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.approval.domain.DwLoanNoteSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * DwLoanNoteSnapshot Mapper(只读:数仓落地表,仅用于历史档案借据查询,禁止写操作)
 */
@Mapper
public interface DwLoanNoteReadMapper extends BaseMapper<DwLoanNoteSnapshot> {
}
