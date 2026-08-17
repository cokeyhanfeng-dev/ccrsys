package com.ccr.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.application.domain.CcrApplicationMember;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * CcrApplicationMember Mapper
 */
@Mapper
public interface CcrApplicationMemberMapper extends BaseMapper<CcrApplicationMember> {

    /**
     * 物理删除申请下成员(草稿重建用)。uk_app_member(application_id, member_customer_no) 不含 del_flag,
     * MP 逻辑删除把 del_flag 0→1 时旧行仍占唯一键,重建 INSERT 撞键报 Duplicate entry;成员无历史价值,物理删除根治。
     */
    @Delete("DELETE FROM ccr_application_member WHERE application_id = #{applicationId}")
    int deletePhysical(@Param("applicationId") Long applicationId);
}
