package com.ccr.admin.config;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.plugins.inner.DataChangeRecorderInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 业务字段级修改留痕(§15.2):拦截核心业务表 UPDATE,写 ccr_audit_log(FIELD_CHANGE)。
 * 拦截器在执行后对比原始数据/更新数据得到"字段→旧值→新值"变更明细(含主键)。
 * 白名单外不处理;写库异常仅日志不阻断业务。
 */
@Slf4j
public class CcrDataChangeInterceptor extends DataChangeRecorderInnerInterceptor {

    /** 核心业务表白名单(§15.2:申请/分项/合同/担保/决议/承诺/评估;排除系统表与日志表自记录噪音) */
    private static final Set<String> WHITELIST = Set.of(
            "ccr_application", "ccr_pricing_item",
            "ccr_pricing_item_contract_rel", "ccr_pricing_item_deposit_rel",
            "ccr_guarantee_package", "ccr_guarantee_measure",
            "ccr_resolution", "ccr_resolution_round",
            "ccr_commitment_plan", "ccr_commitment_metric", "ccr_tracking_evaluation"
    );

    private final JdbcTemplate jdbcTemplate;

    public CcrDataChangeInterceptor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected boolean allowProcess(String tableName) {
        return WHITELIST.contains(tableName);
    }

    @Override
    protected void dealOperationResult(OperationResult result) {
        try {
            if (!result.isRecordStatus()) {
                return;
            }
            // 白名单显式过滤:3.5.7 拦截器对 INSERT 等语句不调用 allowProcess,须在此二次拦截,
            // 否则 outbox/notification 等系统表也会写留痕(此前已观察到,operator_name 为空)
            String tableName = result.getTableName();
            if (tableName == null || !WHITELIST.contains(tableName)) {
                return;
            }
            Long userId = currentUserId();
            String operatorName = userId == null ? null : nickName(userId);
            // content 列 2000 字符,整行变更快照可能超长,截断(留 UTF-8 余量)
            String content = "表[" + tableName + "] " + result.getOperation() + ": " + result.getChangedData();
            if (content.length() > 1900) {
                content = content.substring(0, 1900) + "...(截断)";
            }
            jdbcTemplate.update("""
                            INSERT INTO ccr_audit_log
                            (id, log_type, biz_id, content, operator_id, operator_name, operate_time)
                            VALUES (?, 'FIELD_CHANGE', ?, ?, ?, ?, ?)
                            """,
                    IdUtil.getSnowflakeNextId(), tableName,
                    content,
                    userId == null ? 0L : userId, operatorName, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("字段级修改留痕写入失败(不影响业务): {}", e.getMessage());
        }
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception ignored) {
            return null; // 定时任务等无登录上下文
        }
    }

    private String nickName(Long userId) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT nick_name FROM ccr_sys_user WHERE id = ? AND del_flag = '0'",
                    String.class, userId).stream().findFirst().orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }
}
