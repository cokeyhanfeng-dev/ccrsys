package com.ccr.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.application.domain.CcrGroupMember;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * CcrGroupMember(手工集团成员) Mapper
 */
@Mapper
public interface CcrGroupMemberMapper extends BaseMapper<CcrGroupMember> {

    /**
     * 物理删除集团下全部成员(管理页全量替换/删除集团用)。
     * uk_gm_group_member(group_no, member_customer_no) 不含 del_flag,逻辑删后重建会撞唯一键;
     * 成员为附属数据无历史价值,物理删除根治。
     */
    @Delete("DELETE FROM ccr_group_member WHERE group_no = #{groupNo}")
    int deletePhysicalByGroup(@Param("groupNo") String groupNo);
}
