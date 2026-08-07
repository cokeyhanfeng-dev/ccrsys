package com.ccr.resolution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.resolution.domain.DwLoanNoteSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * DwLoanNoteSnapshot Mapper(只读:数仓落地表,仅用于第二级核验查询,禁止写操作)
 */
@Mapper
public interface DwLoanNoteSnapshotMapper extends BaseMapper<DwLoanNoteSnapshot> {
}
