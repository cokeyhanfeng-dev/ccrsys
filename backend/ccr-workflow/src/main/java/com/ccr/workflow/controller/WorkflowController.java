package com.ccr.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ccr.common.core.domain.R;
import com.ccr.rule.dto.MatrixRouteInput;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.service.RateMatrixRouter;
import com.ccr.workflow.service.WarmFlowService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Warm-Flow 流程定义与轨迹载体(§4.2)
 * 业务审批状态机见 ccr-approval/ccr-vote(手写状态机为主链路,引擎不驱动业务流转);
 * 本类端点为流程管理/演示用途,仅流程管理员(admin)角色可用
 */
@SaCheckRole("admin")
@RestController
@RequestMapping("/ccr/workflow")
public class WorkflowController {

    @Resource
    private WarmFlowService warmFlowService;

    @Resource
    private RateMatrixRouter rateMatrixRouter;

    /** 创建并发布流程定义 */
    @PostMapping("/definitions")
    public R<Long> createFlow(@RequestBody Map<String, Object> body) {
        String flowCode = body.get("flowCode").toString();
        String flowName = body.get("flowName") == null ? flowCode : body.get("flowName").toString();
        @SuppressWarnings("unchecked")
        Map<String, String> nodeHandlers = (Map<String, String>) body.get("nodeHandlers");
        return R.ok(warmFlowService.createFlow(flowCode, flowName, nodeHandlers));
    }

    /** 发起流程实例 */
    @PostMapping("/start")
    public R<Long> start(@RequestBody Map<String, Object> body) {
        return R.ok(warmFlowService.start(
                body.get("flowCode").toString(),
                body.get("businessId").toString(),
                body.get("createBy") == null ? "1000" : body.get("createBy").toString(),
                body.get("startNode") == null ? null : body.get("startNode").toString()));
    }

    /**
     * 衔接:权限矩阵路由(§7.2)→ 发起 Warm-Flow 流程(逐担保类型, D18a)
     * 输入担保分项维度 → 矩阵定起始节点 → 发流程 → 返回 起始节点 + 流程实例id
     */
    @PostMapping("/submit-start")
    public R<Map<String, Object>> submitStart(@RequestBody Map<String, Object> body) {
        // 1. 权限矩阵路由
        MatrixRouteInput routeInput = new MatrixRouteInput();
        routeInput.setBusinessBigType(body.get("businessBigType").toString());
        routeInput.setNewOrExisting(body.get("newOrExisting").toString());
        routeInput.setCustomerType(body.get("customerType") == null ? null : body.get("customerType").toString());
        routeInput.setAmount(body.get("amount") == null ? null : new BigDecimal(body.get("amount").toString()));
        routeInput.setTermValue(body.get("termValue") == null ? null : Integer.valueOf(body.get("termValue").toString()));
        routeInput.setTermUnit(body.get("termUnit") == null ? null : body.get("termUnit").toString());
        routeInput.setGuaranteeType(body.get("guaranteeType") == null ? null : body.get("guaranteeType").toString());
        routeInput.setRequestedRate(new BigDecimal(body.get("requestedRate").toString()));
        RouteResult route = rateMatrixRouter.calcRoute(routeInput);

        // 2. 发起 Warm-Flow 流程(起始节点=矩阵终审岗位)
        Long instanceId = warmFlowService.start(
                body.get("flowCode").toString(),
                body.get("businessId").toString(),
                body.get("createBy") == null ? "1000" : body.get("createBy").toString(),
                route.getStartNodeCode());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("startNodeCode", route.getStartNodeCode());
        result.put("routeMessage", route.getMessage());
        return R.ok(result);
    }

    /** 查询用户待办 */
    @GetMapping("/tasks")
    public R<List<Map<String, Object>>> tasks(@RequestParam String userId) {
        return R.ok(warmFlowService.todoTasks(userId));
    }

    /** 完成任务(通过/否决) */
    @PostMapping("/tasks/{taskId}/complete")
    public R<Boolean> complete(@PathVariable Long taskId,
                               @RequestBody Map<String, Object> body) {
        boolean pass = body.get("pass") == null || Boolean.parseBoolean(body.get("pass").toString());
        return R.ok(warmFlowService.complete(
                taskId,
                body.get("userId").toString(),
                body.get("message") == null ? null : body.get("message").toString(),
                pass));
    }
}
