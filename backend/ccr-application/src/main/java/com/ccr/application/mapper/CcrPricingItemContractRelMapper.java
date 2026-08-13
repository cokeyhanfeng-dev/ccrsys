package com.ccr.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.application.domain.CcrPricingItemContractRel;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * CcrPricingItemContractRel Mapper
 */
@Mapper
public interface CcrPricingItemContractRelMapper extends BaseMapper<CcrPricingItemContractRel> {

    /**
     * 物理删除申请下合同关系(草稿重建用)。uk_app_contract(application_id, contract_business_key, del_flag) 含 del_flag,
     * MP 逻辑删除把 del_flag 0→1 时与既有 del_flag=1 残留撞唯一键;此表无历史价值,物理删除根治。
     *
     * @param excludeItemIds 需保留的沿用(inherit)分项 id,为空则删除申请下全部关系
     */
    @Delete("<script>DELETE FROM ccr_pricing_item_contract_rel WHERE application_id = #{applicationId}" +
            "<if test='excludeItemIds != null and excludeItemIds.size() &gt; 0'>" +
            " AND pricing_item_id NOT IN " +
            "<foreach collection='excludeItemIds' item='it' open='(' separator=',' close=')'>#{it}</foreach>" +
            "</if></script>")
    int deletePhysical(@Param("applicationId") Long applicationId, @Param("excludeItemIds") List<Long> excludeItemIds);
}
