package com.ccr.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.application.domain.CcrApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 申请主单 Mapper
 */
@Mapper
public interface CcrApplicationMapper extends BaseMapper<CcrApplication> {

    /**
     * 锁定读取申请最新状态。用于提交 CAS 失败后的幂等判定；锁定读可越过
     * MySQL REPEATABLE READ 的一致性快照，读取并发事务已经提交的结果。
     */
    @Select("SELECT * FROM ccr_application WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    CcrApplication selectByIdForUpdate(@Param("id") Long id);
}
