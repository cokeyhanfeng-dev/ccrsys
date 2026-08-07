package com.ccr.snapshot.service;

import java.util.List;
import java.util.Map;

/**
 * 数据中心监控服务(§11.7,只读)
 * 数仓落地表(caps_* 与 dw_*,db/02)批次概览与数据源时效看板
 */
public interface DatacenterService {

    /**
     * 批次落地监控:各数仓表最新 data_dt、最新批次行数、落地时间(有 snapshot_ts 列的表)
     * 返回键: table/sourceName/latestDataDt/batchRows/landedTime
     */
    List<Map<String, Object>> batchOverview();

    /**
     * 数据源时效看板:每个数据源最新 data_dt、距今天数、容忍阈值(ccr.snapshot.data-stale-days)、状态
     * 状态: OK(在阈值内)/STALE(超阈值或无数据)
     * 返回键: table/sourceName/latestDataDt/delayDays/thresholdDays/status
     */
    List<Map<String, Object>> sourceStatus();
}
