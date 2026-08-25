package com.ccr.workflow.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.workflow.service.WarmFlowService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.dto.DefJson;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.dto.NodeJson;
import org.dromara.warm.flow.core.dto.SkipJson;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.HisTask;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.enums.FlowStatus;
import org.dromara.warm.flow.core.enums.NodeType;
import org.dromara.warm.flow.core.enums.SkipType;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.HisTaskService;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Warm-Flow 工作流封装实现
 * warm-flow 服务为 Spring bean(BeanConfig 注册),直接注入;待办通过 flow_user 关联 flow_task 查询
 */
@Slf4j
@Service
public class WarmFlowServiceImpl implements WarmFlowService {

    /** 标准流程节点编码→节点名(与业务状态机节点一致) */
    private static final Map<String, String> NODE_NAMES = new LinkedHashMap<>();

    static {
        NODE_NAMES.put("BRANCH_MANAGER", "支行行长");
        NODE_NAMES.put("DEPT_GENERAL_MANAGER", "部门总经理");
        NODE_NAMES.put("VICE_PRESIDENT", "分管副行长");
        NODE_NAMES.put("SECRETARY", "贷审会秘书岗");
        NODE_NAMES.put("SIX_PEOPLE_GROUP", "六人审批小组");
        NODE_NAMES.put("PRESIDENT", "总行行长");
    }

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private DefService definitionService;

    @Resource
    private InsService instanceService;

    @Resource
    private TaskService taskService;

    @Resource
    private HisTaskService hisTaskService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFlow(String flowCode, String flowName, Map<String, String> nodeHandlers) {
        if (nodeHandlers == null || nodeHandlers.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "流程节点不能为空");
        }
        try {
            List<String> codes = new ArrayList<>(nodeHandlers.keySet());

            DefJson def = new DefJson();
            def.setFlowCode(flowCode);
            def.setFlowName(flowName);
            def.setVersion("1");

            // 开始节点
            NodeJson start = new NodeJson();
            start.setNodeType(NodeType.START.getKey());
            start.setNodeCode("start");
            start.setNodeName("开始");
            start.setSkipList(buildSkips("start", codes.get(0)));
            def.setNodeList(new ArrayList<>());
            def.getNodeList().add(start);

            // 审批节点
            for (int i = 0; i < codes.size(); i++) {
                String code = codes.get(i);
                NodeJson node = new NodeJson();
                node.setNodeType(NodeType.BETWEEN.getKey());
                node.setNodeCode(code);
                node.setNodeName(code);
                node.setPermissionFlag(nodeHandlers.get(code));
                String next = i + 1 < codes.size() ? codes.get(i + 1) : "end";
                node.setSkipList(buildSkips(code, next));
                def.getNodeList().add(node);
            }

            // 结束节点
            NodeJson end = new NodeJson();
            end.setNodeType(NodeType.END.getKey());
            end.setNodeCode("end");
            end.setNodeName("结束");
            def.getNodeList().add(end);

            // 用 importDef:内部 structureFlow 会为节点填充 definitionId(§4.2 POC)
            Definition saved = definitionService.importDef(def);
            Long defId = saved.getId();
            definitionService.publish(defId);
            log.info("流程定义已发布: {} defId={}", flowCode, defId);
            return defId;
        } catch (Exception e) {
            log.error("创建流程定义失败 flowCode={}", flowCode, e);
            throw new ServiceException("创建流程定义失败: " + e.getMessage());
        }
    }

    @Override
    public Long start(String flowCode, String businessId, String createBy, String startNode) {
        try {
            Definition definition = definitionService.getPublishByFlowCode(flowCode);
            if (definition == null) {
                throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "流程未发布: " + flowCode);
            }
            // warm-flow 1.7: start(businessId, FlowParams);起始节点由权限矩阵路由决定(§7.2)
            FlowParams params = FlowParams.build().flowCode(flowCode).handler(createBy);
            if (startNode != null && !startNode.isBlank()) {
                params.nodeCode(startNode);
            }
            Instance instance = instanceService.start(businessId, params);
            log.info("流程已发起: flowCode={} businessId={} startNode={} instanceId={}",
                    flowCode, businessId, startNode, instance.getId());
            return instance.getId();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("发起流程失败 flowCode={}", flowCode, e);
            throw new ServiceException("发起流程失败: " + e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> todoTasks(String userId) {
        String sql = """
                SELECT t.id AS task_id, t.node_code, t.node_name, t.instance_id,
                       i.flow_status, i.business_id
                FROM flow_user u
                JOIN flow_task t ON t.id = u.associated
                JOIN flow_instance i ON i.id = t.instance_id
                WHERE u.processed_by = ? AND u.type = '1' AND t.del_flag = '0'
                ORDER BY t.create_time
                """;
        return jdbcTemplate.queryForList(sql, userId);
    }

    @Override
    public boolean complete(Long taskId, String userId, String message, boolean pass) {
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("approve_comment", message == null ? "" : message);
            if (pass) {
                taskService.pass(taskId, userId, variables);
            } else {
                taskService.reject(taskId, userId, variables);
            }
            return true;
        } catch (Exception e) {
            log.error("完成任务失败 taskId={}", taskId, e);
            throw new ServiceException("完成任务失败: " + e.getMessage());
        }
    }

    // ---------- 业务轨迹整合 ----------

    @Override
    public synchronized void ensureStandardFlow() {
        // 需求④P6/决策⑨:存贷分设。贷款走标准流程,存款/保证金走独立流程;已发布则跳过(重建需删旧定义)
        ensureFlow(STANDARD_FLOW_CODE, "利率审批标准流程", loanNodeHandlers());
        ensureFlow(DEPOSIT_FLOW_CODE, "存款保证金审批流程", depositNodeHandlers());
    }

    /** 贷款标准流程节点:支行行长→部门总经理→分管副行长→秘书岗(≥1000万且<2.6%条件必经)→六人小组→总行行长 */
    private static Map<String, String> loanNodeHandlers() {
        Map<String, String> handlers = new LinkedHashMap<>();
        handlers.put("BRANCH_MANAGER", "branch_manager");
        handlers.put("DEPT_GENERAL_MANAGER", "dept_gm");
        handlers.put("VICE_PRESIDENT", "vice_president");
        handlers.put("SECRETARY", "secretary");
        handlers.put("SIX_PEOPLE_GROUP", "committee_member");
        handlers.put("PRESIDENT", "president");
        return handlers;
    }

    /** 存款/保证金独立流程节点:支行行长→六人小组→总行行长(决策⑨:存款上会必经行长决议) */
    private static Map<String, String> depositNodeHandlers() {
        Map<String, String> handlers = new LinkedHashMap<>();
        handlers.put("BRANCH_MANAGER", "branch_manager");
        handlers.put("SIX_PEOPLE_GROUP", "committee_member");
        handlers.put("PRESIDENT", "president");
        return handlers;
    }

    /** 确保指定流程定义已发布;已发布跳过,初始化失败仅记日志不影响系统启动 */
    private void ensureFlow(String flowCode, String flowName, Map<String, String> nodeHandlers) {
        try {
            Definition published = definitionService.getPublishByFlowCode(flowCode);
            if (published != null) {
                return;
            }
            createFlow(flowCode, flowName, nodeHandlers);
            log.info("流程定义已初始化: {}", flowCode);
        } catch (Exception e) {
            // 初始化失败不影响系统启动(轨迹记录时再降级)
            log.error("流程定义初始化失败 flowCode={}", flowCode, e);
        }
    }

    @Override
    public void recordBusinessTrail(String pricingItemNo, String nodeCode, String action,
                                    String operator, String comment) {
        try {
            Definition definition = resolveTrailDefinition(pricingItemNo);
            if (definition == null) {
                log.warn("业务轨迹跳过:流程未发布 pricingItemNo={} node={} action={}",
                        pricingItemNo, nodeCode, action);
                return;
            }
            Instance instance = findOrCreateTrailInstance(definition, pricingItemNo, nodeCode, operator);

            boolean rejectish = isRejectAction(action);
            HisTask his = FlowEngine.newHisTask()
                    // 手动业务轨迹无真实流程任务,生成雪花id满足 flow_his_task.task_id NOT NULL
                    .setTaskId(IdUtil.getSnowflakeNextId())
                    .setDefinitionId(definition.getId())
                    .setFlowName(definition.getFlowName())
                    .setInstanceId(instance.getId())
                    .setBusinessId(pricingItemNo)
                    .setNodeCode(nodeCode)
                    .setNodeName(nodeName(nodeCode))
                    .setNodeType(NodeType.BETWEEN.getKey())
                    .setApprover(operator)
                    .setSkipType(rejectish ? SkipType.REJECT.getKey() : SkipType.PASS.getKey())
                    .setFlowStatus(rejectish ? FlowStatus.REJECT.getKey() : FlowStatus.PASS.getKey())
                    .setMessage(comment == null || comment.isBlank() ? action : action + ": " + comment)
                    .setCreateBy(operator);
            hisTaskService.save(his);
            log.info("业务审批轨迹已记录: pricingItemNo={} node={} action={} operator={}",
                    pricingItemNo, nodeCode, action, operator);
        } catch (Exception e) {
            // 轨迹记录失败不影响业务主流程
            log.error("业务审批轨迹记录失败 pricingItemNo={} node={} action={}",
                    pricingItemNo, nodeCode, action, e);
        }
    }

    /**
     * 按业务载体解析轨迹所用流程定义(需求④P6):存款/保证金(DEPOSIT_ACCOUNT)走独立存款流程,
     * 其余(贷款)走标准流程;查不到业务类型时降级标准流程,无发布定义返回 null
     */
    private Definition resolveTrailDefinition(String pricingItemNo) {
        String flowCode = STANDARD_FLOW_CODE;
        if (StrUtil.isNotBlank(pricingItemNo)) {
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT pricing_carrier_type FROM ccr_pricing_item WHERE pricing_item_no = ? AND del_flag = '0'",
                        pricingItemNo);
                if (!rows.isEmpty()) {
                    Object carrier = rows.get(0).get("pricing_carrier_type");
                    if ("DEPOSIT_ACCOUNT".equals(carrier)) {
                        flowCode = DEPOSIT_FLOW_CODE;
                    }
                }
            } catch (Exception e) {
                // 反查失败不阻断轨迹记录,降级标准流程
                log.warn("轨迹流程反查失败 pricingItemNo={}, 降级标准流程", pricingItemNo, e);
            }
        }
        return definitionService.getPublishByFlowCode(flowCode);
    }

    /** 查找(businessId=定价分项编号)或创建轨迹用流程实例 */
    private Instance findOrCreateTrailInstance(Definition definition, String pricingItemNo,
                                               String nodeCode, String operator) {
        Instance probe = FlowEngine.newIns().setBusinessId(pricingItemNo);
        List<Instance> existing = instanceService.list(probe);
        Instance instance = existing.stream()
                .filter(i -> definition.getId().equals(i.getDefinitionId()))
                .findFirst()
                .orElse(null);
        if (instance == null) {
            instance = FlowEngine.newIns()
                    .setDefinitionId(definition.getId())
                    .setFlowName(definition.getFlowName())
                    .setBusinessId(pricingItemNo)
                    .setNodeType(NodeType.BETWEEN.getKey())
                    .setNodeCode(nodeCode)
                    .setNodeName(nodeName(nodeCode))
                    .setFlowStatus(FlowStatus.APPROVAL.getKey())
                    .setCreateBy(operator);
            instanceService.save(instance);
        } else {
            // 实例当前节点跟随业务审批位置
            instance.setNodeCode(nodeCode);
            instance.setNodeName(nodeName(nodeCode));
            instanceService.updateById(instance);
        }
        return instance;
    }

    private static boolean isRejectAction(String action) {
        if (action == null) {
            return false;
        }
        String a = action.toUpperCase();
        return a.contains("REJECT") || a.contains("RETURN") || a.contains("VETO");
    }

    private static String nodeName(String nodeCode) {
        return NODE_NAMES.getOrDefault(nodeCode, nodeCode);
    }

    // ---------- 私有 ----------

    private List<SkipJson> buildSkips(String from, String to) {
        SkipJson skip = new SkipJson();
        skip.setNowNodeCode(from);
        skip.setNextNodeCode(to);
        skip.setSkipName("通过");
        skip.setSkipType("PASS");
        List<SkipJson> skips = new ArrayList<>();
        skips.add(skip);
        return skips;
    }
}
