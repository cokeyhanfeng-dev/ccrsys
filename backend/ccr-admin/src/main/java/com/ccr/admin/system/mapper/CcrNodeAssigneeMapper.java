package com.ccr.admin.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.admin.system.domain.CcrNodeAssignee;
import org.apache.ibatis.annotations.Mapper;

/**
 * 节点审批人指派 Mapper(§10.3.19 ccr_node_assignee)
 */
@Mapper
public interface CcrNodeAssigneeMapper extends BaseMapper<CcrNodeAssignee> {
}
