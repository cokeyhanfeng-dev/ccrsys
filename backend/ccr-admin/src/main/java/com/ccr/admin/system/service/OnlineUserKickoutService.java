package com.ccr.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.ccr.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

/** 管理员账号级下线；沿用 Sa-Token 撤销所有终端，不修改账号及审批业务数据。 */
@Service
@RequiredArgsConstructor
public class OnlineUserKickoutService {
    private final JdbcTemplate jdbc;

    // 审计先落库再操作 Redis，不使用会回滚前置审计的数据库事务。
    public void kickout(Long userId) {
        StpUtil.checkRole("admin");
        if (userId == null || userId <= 0) throw new ServiceException(400, "用户编号无效");
        long operator = StpUtil.getLoginIdAsLong();
        if (userId == operator) throw new ServiceException(400, "当前账号请使用右上角退出登录");
        var users = jdbc.queryForList("SELECT id FROM ccr_sys_user WHERE id = ? AND del_flag = '0'", userId);
        if (users.isEmpty()) throw new ServiceException(404, "用户不存在");
        String operatorName = jdbc.queryForObject("SELECT nick_name FROM ccr_sys_user WHERE id = ?", String.class, operator);
        long auditId = IdUtil.getSnowflakeNextId();
        String detail = "强制下线账号 " + userId + " 的全部电脑端和移动端会话";
        int inserted = jdbc.update("""
                INSERT INTO ccr_audit_log
                (id, log_type, biz_id, content, operator_id, operator_name, operate_time)
                VALUES (?,?,?,?,?,?,?)
                """, auditId, "FORCE_LOGOUT", userId.toString(), detail + "；状态：请求已记录",
                operator, operatorName, LocalDateTime.now());
        if (inserted != 1) throw new ServiceException(500, "操作日志记录失败，未执行强制下线");
        try {
            StpUtil.kickout(userId);
        } catch (RuntimeException error) {
            try { recordResult(auditId, detail + "；状态：执行异常，需核对会话状态"); }
            catch (RuntimeException auditError) { error.addSuppressed(auditError); }
            throw new ServiceException(503, "强制下线执行异常，部分会话可能已失效，请刷新清单核对");
        }
        try {
            recordResult(auditId, detail + "；状态：已完成");
        } catch (RuntimeException error) {
            throw new ServiceException(500, "已执行强制下线，但日志状态更新失败，请刷新清单核对");
        }
    }

    private void recordResult(long auditId, String content) {
        if (jdbc.update("UPDATE ccr_audit_log SET content = ? WHERE id = ?", content, auditId) != 1) {
            throw new ServiceException(500, "操作日志状态更新失败");
        }
    }
}
