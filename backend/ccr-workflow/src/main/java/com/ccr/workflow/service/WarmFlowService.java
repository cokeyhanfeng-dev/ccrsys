package com.ccr.workflow.service;

import java.util.List;
import java.util.Map;

/**
 * Warm-Flow 工作流封装(§4.2)
 * 流程定义只表达节点、候选人和流转;业务记录使用定价分项编号作为 businessId(§4.2)
 * 业务审批状态机在 ccr-approval/ccr-vote(手写状态机),引擎在此作为流程定义与审批轨迹载体
 */
public interface WarmFlowService {

    /** 利率审批标准流程编码(业务审批轨迹载体,节点见 ensureStandardFlow) */
    String STANDARD_FLOW_CODE = "rate_approval";

    /**
     * 创建并发布流程定义(编程式,§18 POC)
     *
     * @param flowCode 流程编码(如 loan_approval)
     * @param flowName 流程名称
     * @param nodeHandlers 有序节点编码→处理人(逗号分隔用户id),自动含开始/结束节点
     * @return 流程定义id
     */
    Long createFlow(String flowCode, String flowName, Map<String, String> nodeHandlers);

    /**
     * 发起流程实例
     *
     * @param flowCode   流程编码
     * @param businessId 业务id(定价分项编号)
     * @param createBy   发起人
     * @param startNode  起始节点(权限矩阵路由结果,§7.2);空=流程默认起始
     */
    Long start(String flowCode, String businessId, String createBy, String startNode);

    /**
     * 查询用户待办(通过 flow_user 关联)
     */
    List<Map<String, Object>> todoTasks(String userId);

    /**
     * 完成任务(通过/否决)
     *
     * @param taskId   任务id
     * @param userId   处理人
     * @param message  意见
     * @param pass     true通过 false否决
     */
    boolean complete(Long taskId, String userId, String message, boolean pass);

    /**
     * 确保"利率审批标准流程"定义存在(系统启动时调用;节点=支行行长→部门总经理→分管副行长→六人小组→总行行长)
     * 已发布则跳过;失败仅记日志,不影响系统启动
     */
    void ensureStandardFlow();

    /**
     * 记录业务审批轨迹(低成本整合):把业务审批动作以流程实例/历史任务形式写入引擎表,
     * businessId=定价分项编号(§4.2)。供业务审批状态机(ccr-approval/ccr-vote)在审批动作处调用。
     * 失败只记日志,不影响业务主流程
     *
     * @param pricingItemNo 定价分项编号(引擎 businessId)
     * @param nodeCode      业务节点编码(BRANCH_MANAGER/DEPT_GENERAL_MANAGER/VICE_PRESIDENT/SIX_PEOPLE_GROUP/PRESIDENT)
     * @param action        审批动作(如 SUBMIT/PASS/REJECT/RETURN/VETO,含 REJECT/RETURN/VETO 视为否决类)
     * @param operator      操作人(用户id或账号)
     * @param comment       审批意见(可空)
     */
    void recordBusinessTrail(String pricingItemNo, String nodeCode, String action, String operator, String comment);
}
