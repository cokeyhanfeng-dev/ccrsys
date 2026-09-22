package com.ccr.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccr.message.domain.CcrNotificationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ccr.message.service.dto.NotificationLogQuery;
import com.ccr.message.service.dto.NotificationLogView;

/**
 * CcrNotificationLog Mapper
 */
@Mapper
public interface CcrNotificationLogMapper extends BaseMapper<CcrNotificationLog> {
    @Select("""
        <script>
        SELECT n.*, u.nick_name AS recipient_name, u.username AS recipient_username,
               d.dept_name AS recipient_org_name
        FROM ccr_notification_log n
        LEFT JOIN ccr_sys_user u ON n.recipient_id = CAST(u.id AS CHAR) AND u.del_flag = '0'
        LEFT JOIN ccr_sys_dept d ON d.id = u.org_id AND d.del_flag = '0'
        WHERE n.del_flag = '0'
        <if test="q.recipient != null and q.recipient != ''">
          AND (LOCATE(#{q.recipient}, u.nick_name) &gt; 0
            OR LOCATE(#{q.recipient}, u.username) &gt; 0 OR n.recipient_id = #{q.recipient})
        </if>
        <if test="q.recipientOrgId != null">AND u.org_id = #{q.recipientOrgId}</if>
        <if test="q.keyword != null and q.keyword != ''">
          AND (LOCATE(#{q.keyword}, n.message_content) &gt; 0 OR LOCATE(#{q.keyword}, n.message_key) &gt; 0)
        </if>
        <if test="q.channel != null">AND n.channel = #{q.channel}</if>
        <if test="q.sendStatus != null">AND n.send_status = #{q.sendStatus}</if>
        <if test="q.receiptStatus == 'READ'">AND n.channel = 'SYSTEM' AND n.receipt_time IS NOT NULL</if>
        <if test="q.receiptStatus == 'UNREAD'">AND n.channel = 'SYSTEM' AND n.receipt_time IS NULL</if>
        <if test="q.startTime != null">AND n.create_time &gt;= #{q.startTime}</if>
        <if test="q.endTime != null">AND n.create_time &lt;= #{q.endTime}</if>
        ORDER BY n.create_time DESC, n.id DESC
        </script>
        """)
    Page<NotificationLogView> selectAdminPage(Page<NotificationLogView> page, @Param("q") NotificationLogQuery query);
}
