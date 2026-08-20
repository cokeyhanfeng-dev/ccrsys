package com.ccr.admin.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import cn.hutool.core.util.IdUtil;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 运行报错采集 Appender(运行日志监控,非审计):将 ERROR/WARN 级别日志(含完整堆栈)
 * 异步批量落库 ccr_error_log,供前台"系统管理-运行日志监控"页面查询/查看详情/标记处理。
 * <p>
 * 设计要点:
 * - 异步:ArrayBlockingQueue + 专用 daemon 线程,攒批或到间隔时间批量 INSERT,不拖慢业务线程;
 * - 停止时冲刷剩余队列,避免应用退出丢日志;
 * - logback 初始化早于 Spring 容器:JdbcTemplate 由 {@link RunLogDbBootstrap} 在 Spring 启动后经
 *   {@link #setJdbcTemplate} 注入,Spring 未就绪前 flush 直接跳过;
 * - 本 Appender 内部异常只走 System.err,不回灌日志流(避免自身报错触发 ERROR_DB 造成死循环)。
 * <p>
 * 配置项(logback-spring.xml 通过 springProperty 注入 application.yml ccr.run-log.*):
 * minLevel(默认 ERROR)、queueSize(默认 10000)、flushIntervalMs(默认 3000)、batchSize(默认 50)。
 */
public class ErrorLogDbAppender extends AppenderBase<ILoggingEvent> {

    /** 由 RunLogDbBootstrap 注入(static,因 logback 初始化早于 Spring 容器) */
    private static volatile JdbcTemplate jdbcTemplate;

    public static void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        ErrorLogDbAppender.jdbcTemplate = jdbcTemplate;
    }

    private int minLevelValue = Level.ERROR_INT;
    private int queueSize = 10_000;
    private long flushIntervalMs = 3_000;
    private int batchSize = 50;

    private BlockingQueue<ILoggingEvent> queue;
    private Thread worker;
    private volatile boolean running;

    /** 采集最低级别:logback 级别名 ERROR/WARN */
    public void setMinLevel(String level) {
        Level lv = Level.toLevel(level, Level.ERROR);
        this.minLevelValue = lv.toInt();
    }

    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    @Override
    public void start() {
        if (queueSize < 1) queueSize = 10_000;
        if (batchSize < 1) batchSize = 50;
        if (flushIntervalMs < 100) flushIntervalMs = 3_000;
        queue = new ArrayBlockingQueue<>(queueSize);
        running = true;
        worker = new Thread(this::run, "run-log-db-writer");
        worker.setDaemon(true);
        worker.start();
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!isStarted() || event.getLevel() == null) {
            return;
        }
        if (event.getLevel().toInt() < minLevelValue) {
            return;
        }
        if (!queue.offer(event)) {
            // 队列满:丢弃最旧一条,保留最新(报错监控以近期为主)
            queue.poll();
            queue.offer(event);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        super.stop();
    }

    private void run() {
        List<ILoggingEvent> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                long waitMs = running ? flushIntervalMs : 0;
                ILoggingEvent evt = queue.poll(waitMs, TimeUnit.MILLISECONDS);
                if (evt != null) {
                    batch.add(evt);
                }
                if (batch.size() >= batchSize || (evt == null && !batch.isEmpty())) {
                    flush(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                queue.drainTo(batch);
                if (!batch.isEmpty()) {
                    flush(batch);
                }
                return;
            }
        }
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            flush(batch);
        }
    }

    private void flush(List<ILoggingEvent> batch) {
        JdbcTemplate jt = jdbcTemplate;
        if (jt == null) {
            // Spring 未就绪:报错监控非关键路径,静默丢弃本次
            return;
        }
        try {
            List<Object[]> rows = new ArrayList<>(batch.size());
            for (ILoggingEvent e : batch) {
                rows.add(toRow(e));
            }
            jt.batchUpdate("""
                            INSERT INTO ccr_error_log
                            (id, error_time, logger_name, level, message, stack_trace,
                             thread_name, request_uri, operator_id, handle_status)
                            VALUES (?,?,?,?,?,?,?,?,?,'PENDING')
                            """, rows);
        } catch (Exception ex) {
            System.err.println("[run-log] 错误日志落库失败(丢弃 " + batch.size() + " 条): " + ex.getMessage());
        }
    }

    private Object[] toRow(ILoggingEvent e) {
        Map<String, String> mdc = e.getMDCPropertyMap();
        String stack = e.getThrowableProxy() == null ? null : ThrowableProxyUtil.asString(e.getThrowableProxy());
        return new Object[]{
                IdUtil.getSnowflakeNextId(),
                LocalDateTime.now(),
                truncate(e.getLoggerName(), 255),
                e.getLevel() == null ? "ERROR" : e.getLevel().toString(),
                truncate(e.getFormattedMessage(), 4000),
                truncate(stack, 100_000),
                truncate(e.getThreadName(), 128),
                truncate(mdc == null ? null : mdc.get("requestUri"), 512),
                parseLong(mdc == null ? null : mdc.get("userId"))
        };
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
