package com.ccr.resolution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.resolution.domain.CcrNotificationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * CcrNotificationLog Mapper(本模块仅写入核验异常通知,消费与发送由消息模块负责)
 */
@Mapper
public interface CcrNotificationLogWriteMapper extends BaseMapper<CcrNotificationLog> {
}
