package com.ccr.rule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.rule.domain.CcrLprConfig;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * CcrLprConfig LPR 明细 Mapper(§8A.3)
 */
@Mapper
public interface CcrLprConfigMapper extends BaseMapper<CcrLprConfig> {

    /**
     * 物理删除某版本全部明细(全量替换用):
     * 逻辑删除会占用 (version_id, lpr_term, product_type) 唯一键,须物理删除方可重建同键行。
     */
    @Delete("DELETE FROM ccr_lpr_config WHERE version_id = #{versionId}")
    int physicalDeleteByVersionId(@Param("versionId") Long versionId);
}
