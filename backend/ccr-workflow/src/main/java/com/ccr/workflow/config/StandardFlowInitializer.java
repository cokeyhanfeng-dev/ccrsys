package com.ccr.workflow.config;

import com.ccr.workflow.service.WarmFlowService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 利率审批标准流程定义初始化(§4.2)
 * 引擎作为流程定义与审批轨迹载体:启动时确保存在一条已发布的标准流程定义,
 * 供 {@link WarmFlowService#recordBusinessTrail} 挂载业务审批轨迹(businessId=定价分项编号)
 */
@Slf4j
@Component
public class StandardFlowInitializer implements ApplicationRunner {

    @Resource
    private WarmFlowService warmFlowService;

    @Override
    public void run(ApplicationArguments args) {
        warmFlowService.ensureStandardFlow();
    }
}
