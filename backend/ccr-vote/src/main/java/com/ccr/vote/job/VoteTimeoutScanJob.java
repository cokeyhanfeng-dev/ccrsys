package com.ccr.vote.job;

import com.ccr.vote.service.VoteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 表决超时扫描定时任务(§7.5.5)
 * VOTING 批次超过 ccr.vote.round-timeout-hours(默认 72h)按已投票数强制计票;
 * cron 走配置 ccr.vote.timeout-scan-cron(默认每小时)
 */
@Slf4j
@Component
public class VoteTimeoutScanJob {

    @Resource
    private VoteService voteService;

    /** 超时批次扫描(默认每小时整点) */
    @Scheduled(cron = "${ccr.vote.timeout-scan-cron:0 0 * * * ?}")
    public void scanTimeoutRounds() {
        try {
            voteService.scanTimeoutRounds();
        } catch (Exception e) {
            log.error("表决超时扫描失败", e);
        }
    }
}
