package com.ccr.approval.controller;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.ccr.common.core.domain.R;
import com.ccr.vote.read.SysUserRead;
import com.ccr.vote.support.CurrentLoginUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计接口(§11.10)
 * 票据反查:ccr_ballot 只存 voter_user_hash(SHA2-256(userId),与 VoteServiceImpl.voterHash 同口径),
 * 用批次 assignment 名单(含替补)逐个哈希比对还原真实投票人;仅 auditor 角色,反查动作全程写
 * ccr_audit_log 留痕。
 * 导出记录:ccr_export_record 查询,auditor/admin 可见。
 */
@RestController
@RequestMapping("/ccr/audit")
@Slf4j
public class AuditController {

    @Resource
    private CurrentLoginUser currentLoginUser;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 实际投票人查询(仅审计):返回 分项/真实投票人/票型/时间/匿名码
     *
     * @param roundId       表决批次id(必填)
     * @param pricingItemId 分项id(可选,缺省整批)
     */
    @GetMapping("/ballot-detail")
    public R<List<Map<String, Object>>> ballotDetail(@RequestParam Long roundId,
                                                     @RequestParam(required = false) Long pricingItemId) {
        SysUserRead auditor = currentLoginUser.requireCurrentUser();
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_AUDITOR);

        // 批次名单(含替补):确定性哈希 sha256(userId) → 投票人(匿名码/真实身份)
        List<Map<String, Object>> assignments = jdbcTemplate.queryForList(
                "SELECT voter_user_id voterUserId, voter_anonym_no anonymNo"
                        + " FROM ccr_vote_assignment WHERE round_id = ? AND del_flag = '0'", roundId);
        Map<String, Map<String, Object>> hashToVoter = new HashMap<>();
        for (Map<String, Object> assignment : assignments) {
            Long uid = ((Number) assignment.get("voterUserId")).longValue();
            // 与 VoteServiceImpl.voterHash 同口径:DigestUtil.sha256Hex(String.valueOf(userId))
            hashToVoter.putIfAbsent(DigestUtil.sha256Hex(String.valueOf(uid)), assignment);
        }

        StringBuilder sql = new StringBuilder("""
                SELECT b.pricing_item_id pricingItemId, b.voter_user_hash voterUserHash,
                       b.vote_choice voteChoice, b.vote_comment voteComment, b.submit_time submitTime
                FROM ccr_ballot b
                WHERE b.round_id = ? AND b.del_flag = '0'
                """);
        List<Object> args = new ArrayList<>();
        args.add(roundId);
        if (pricingItemId != null) {
            sql.append(" AND b.pricing_item_id = ?");
            args.add(pricingItemId);
        }
        sql.append(" ORDER BY b.pricing_item_id, b.submit_time");
        List<Map<String, Object>> ballots = jdbcTemplate.queryForList(sql.toString(), args.toArray());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> ballot : ballots) {
            Map<String, Object> voter = hashToVoter.get(String.valueOf(ballot.get("voterUserHash")));
            Long voterUserId = voter == null ? null : ((Number) voter.get("voterUserId")).longValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pricingItemId", ballot.get("pricingItemId"));
            row.put("voterUserId", voterUserId);
            row.put("voterName", voterUserId == null ? null : voterName(voterUserId));
            row.put("anonymNo", voter == null ? null : voter.get("anonymNo"));
            row.put("voteChoice", ballot.get("voteChoice"));
            row.put("voteComment", ballot.get("voteComment"));
            row.put("submitTime", ballot.get("submitTime"));
            result.add(row);
        }

        // 反查动作留痕(§11.10/§15.3)
        writeAuditLog("BALLOT_DETAIL", String.valueOf(roundId),
                "票据反查:批次=" + roundId + (pricingItemId == null ? "" : ",分项=" + pricingItemId)
                        + ",还原票据 " + result.size() + " 张", auditor);
        return R.ok(result);
    }

    /** 导出记录查询(auditor/admin;可按申请过滤) */
    @GetMapping("/export-records")
    public R<List<Map<String, Object>>> exportRecords(@RequestParam(required = false) Long applicationId) {
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_AUDITOR, CurrentLoginUser.ROLE_ADMIN);
        String sql = """
                SELECT export_no exportNo, application_id applicationId, export_type exportType,
                       file_name fileName, operator_id operatorId, operator_name operatorName,
                       org_id orgId, export_time exportTime
                FROM ccr_export_record
                WHERE del_flag = '0'
                """ + (applicationId == null ? "" : " AND application_id = " + applicationId)
                + " ORDER BY export_time DESC";
        return R.ok(jdbcTemplate.queryForList(sql));
    }

    /**
     * 审计日志查询(§12.14/§15.2:登录/提交/字段级修改/配置/反查等全程留痕;auditor/admin)。
     * 可按 日志类型/操作人/时间范围/关键词 过滤,时间倒序;分页返回 {total, records},单页上限 100。
     */
    @GetMapping("/logs")
    public R<Map<String, Object>> logs(@RequestParam(required = false) String logType,
                                       @RequestParam(required = false) String operator,
                                       @RequestParam(required = false) String startTime,
                                       @RequestParam(required = false) String endTime,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_AUDITOR, CurrentLoginUser.ROLE_ADMIN);
        StringBuilder where = new StringBuilder(" WHERE del_flag = '0'");
        List<Object> args = new ArrayList<>();
        if (StrUtil.isNotBlank(logType)) {
            where.append(" AND log_type = ?");
            args.add(logType);
        }
        if (StrUtil.isNotBlank(operator)) {
            where.append(" AND operator_name LIKE ?");
            args.add("%" + operator + "%");
        }
        if (StrUtil.isNotBlank(startTime)) {
            where.append(" AND operate_time >= ?");
            args.add(startTime);
        }
        if (StrUtil.isNotBlank(endTime)) {
            where.append(" AND operate_time <= ?");
            args.add(endTime);
        }
        if (StrUtil.isNotBlank(keyword)) {
            where.append(" AND (biz_id LIKE ? OR content LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_audit_log" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                """
                        SELECT log_type logType, biz_id bizId, content, operator_id operatorId,
                               operator_name operatorName, operate_time operateTime
                        FROM ccr_audit_log""" + where + " ORDER BY operate_time DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Map<String, Object> data = new HashMap<>();
        data.put("total", total == null ? 0L : total);
        data.put("records", records);
        return R.ok(data);
    }

    /** 审计留痕;表不存在(未执行 03f)时仅记日志,不阻断查询 */
    private void writeAuditLog(String logType, String bizId, String content, SysUserRead operator) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO ccr_audit_log
                            (id, log_type, biz_id, content, operator_id, operator_name, operate_time)
                            VALUES (?,?,?,?,?,?,?)
                            """,
                    IdUtil.getSnowflakeNextId(), logType, bizId, content,
                    operator.getId(), operator.getNickName(), LocalDateTime.now());
        } catch (DataAccessException e) {
            log.warn("审计留痕写入失败(不影响查询): {}", e.getMessage());
        }
    }

    /** 投票人姓名(ccr_sys_user.nick_name);查不到回退用户id */
    private String voterName(Long userId) {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT nick_name FROM ccr_sys_user WHERE id = ? AND del_flag = '0'", String.class, userId);
        return names.isEmpty() ? String.valueOf(userId) : names.get(0);
    }
}
