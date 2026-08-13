package com.ccr.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.application.domain.CcrPricingItemDepositRel;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * CcrPricingItemDepositRel Mapper
 */
@Mapper
public interface CcrPricingItemDepositRelMapper extends BaseMapper<CcrPricingItemDepositRel> {

    /**
     * 物理删除申请下存款账户关系(草稿重建用)。与合同关系表同因:逻辑删除撞含 del_flag 的唯一键,物理删除根治。
     *
     * @param excludeItemIds 需保留的沿用(inherit)分项 id,为空则删除申请下全部关系
     */
    @Delete("<script>DELETE FROM ccr_pricing_item_deposit_rel WHERE application_id = #{applicationId}" +
            "<if test='excludeItemIds != null and excludeItemIds.size() &gt; 0'>" +
            " AND pricing_item_id NOT IN " +
            "<foreach collection='excludeItemIds' item='it' open='(' separator=',' close=')'>#{it}</foreach>" +
            "</if></script>")
    int deletePhysical(@Param("applicationId") Long applicationId, @Param("excludeItemIds") List<Long> excludeItemIds);
}
