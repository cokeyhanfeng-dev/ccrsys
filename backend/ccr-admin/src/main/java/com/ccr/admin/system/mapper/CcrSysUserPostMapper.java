package com.ccr.admin.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.admin.system.domain.CcrSysUserPost;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户-机构-岗位绑定 Mapper
 */
@Mapper
public interface CcrSysUserPostMapper extends BaseMapper<CcrSysUserPost> {

    /**
     * 物理删除用户全部绑定(绕过全局逻辑删除)。
     * saveBindings 整体替换须先物理删旧绑定,否则逻辑删后 del_flag='1'
     * 仍占 uk_user_org_post 唯一键,重建 insert 撞键被 GlobalExceptionHandler 包装为 1013"重复提交"。
     */
    @Delete("DELETE FROM ccr_sys_user_post WHERE user_id = #{userId}")
    int physicalDeleteByUserId(@Param("userId") Long userId);
}
