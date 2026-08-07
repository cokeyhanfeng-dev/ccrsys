package com.ccr.common.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.common.outbox.domain.CcrOutboxEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * Outbox 可靠事件 Mapper
 */
@Mapper
public interface CcrOutboxEventMapper extends BaseMapper<CcrOutboxEvent> {
}
