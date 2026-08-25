package com.ccr.workflow;

import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.workflow.service.WarmFlowService;
import com.ccr.workflow.service.impl.WarmFlowServiceImpl;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.HisTask;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.HisTaskService;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Warm-Flow 工作流封装单元测试(§4.2)
 * 覆盖:流程定义创建/发布、发起实例、待办查询、完成/驳回、标准流程初始化、业务轨迹记录
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarmFlowServiceImplTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DefService definitionService;
    @Mock
    private InsService instanceService;
    @Mock
    private TaskService taskService;
    @Mock
    private HisTaskService hisTaskService;

    @InjectMocks
    private WarmFlowServiceImpl service;

    private Definition mockDefinition(Long id, String flowName) {
        Definition def = mock(Definition.class);
        when(def.getId()).thenReturn(id);
        when(def.getFlowName()).thenReturn(flowName);
        return def;
    }

    // ---------- createFlow ----------

    @Test
    void createFlow_节点为空抛BAD_REQUEST() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.createFlow("F001", "测试流程", null));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    @Test
    void createFlow_节点为空Map抛BAD_REQUEST() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.createFlow("F001", "测试流程", Map.of()));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    @Test
    void createFlow_成功创建并发布() {
        Map<String, String> handlers = new LinkedHashMap<>();
        handlers.put("BRANCH_MANAGER", "branch_manager");
        handlers.put("PRESIDENT", "president");

        Definition saved = mockDefinition(200L, "测试流程");
        when(definitionService.importDef(any())).thenReturn(saved);

        Long defId = service.createFlow("F001", "测试流程", handlers);

        assertEquals(200L, defId);
        verify(definitionService).importDef(any());
        verify(definitionService).publish(200L);
    }

    @Test
    void createFlow_importDef异常包装为ServiceException() {
        Map<String, String> handlers = new LinkedHashMap<>();
        handlers.put("N1", "handler1");

        when(definitionService.importDef(any())).thenThrow(new RuntimeException("DB连接失败"));

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.createFlow("F001", "测试流程", handlers));
        assertTrue(e.getMessage().contains("创建流程定义失败"));
    }

    // ---------- start ----------

    @Test
    void start_流程未发布抛FLOW_STATUS_CONFLICT() {
        when(definitionService.getPublishByFlowCode("F001")).thenReturn(null);

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.start("F001", "BIZ001", "U001", null));
        assertEquals(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), e.getCode());
    }

    @Test
    void start_成功发起返回实例ID() {
        Definition def = mockDefinition(200L, "测试流程");
        Instance instance = mock(Instance.class);
        when(instance.getId()).thenReturn(500L);

        when(definitionService.getPublishByFlowCode("F001")).thenReturn(def);
        when(instanceService.start(eq("BIZ001"), any())).thenReturn(instance);

        Long instanceId = service.start("F001", "BIZ001", "U001", "BRANCH_MANAGER");

        assertEquals(500L, instanceId);
    }

    @Test
    void start_无起始节点正常发起() {
        Definition def = mockDefinition(200L, "测试流程");
        Instance instance = mock(Instance.class);
        when(instance.getId()).thenReturn(501L);

        when(definitionService.getPublishByFlowCode("F001")).thenReturn(def);
        when(instanceService.start(eq("BIZ001"), any())).thenReturn(instance);

        Long instanceId = service.start("F001", "BIZ001", "U001", null);

        assertEquals(501L, instanceId);
    }

    @Test
    void start_ServiceException原样抛出() {
        Definition def = mockDefinition(200L, "测试流程");
        when(definitionService.getPublishByFlowCode("F001")).thenReturn(def);
        when(instanceService.start(any(), any())).thenThrow(new ServiceException(999, "自定义异常"));

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.start("F001", "BIZ001", "U001", null));
        assertEquals(999, e.getCode());
    }

    @Test
    void start_非ServiceException包装为ServiceException() {
        Definition def = mockDefinition(200L, "测试流程");
        when(definitionService.getPublishByFlowCode("F001")).thenReturn(def);
        when(instanceService.start(any(), any())).thenThrow(new RuntimeException("超时"));

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.start("F001", "BIZ001", "U001", null));
        assertTrue(e.getMessage().contains("发起流程失败"));
    }

    // ---------- todoTasks ----------

    @Test
    void todoTasks_委托JdbcTemplate查询() {
        List<Map<String, Object>> mockResult = List.of(
                Map.of("task_id", 1L, "node_code", "BRANCH_MANAGER", "business_id", "BIZ001"),
                Map.of("task_id", 2L, "node_code", "PRESIDENT", "business_id", "BIZ002")
        );
        when(jdbcTemplate.queryForList(anyString(), eq("U001"))).thenReturn(mockResult);

        List<Map<String, Object>> result = service.todoTasks("U001");

        assertEquals(2, result.size());
        assertEquals("BRANCH_MANAGER", result.get(0).get("node_code"));
        verify(jdbcTemplate).queryForList(anyString(), eq("U001"));
    }

    // ---------- complete ----------

    @Test
    void complete_pass为true调用taskService_pass() {
        service.complete(100L, "U001", "同意", true);

        verify(taskService).pass(eq(100L), eq("U001"), any());
        verify(taskService, never()).reject(anyLong(), anyString(), any());
    }

    @Test
    void complete_pass为false调用taskService_reject() {
        service.complete(100L, "U001", "不同意", false);

        verify(taskService).reject(eq(100L), eq("U001"), any());
        verify(taskService, never()).pass(anyLong(), anyString(), any());
    }

    @Test
    void complete_message为null传空串() {
        service.complete(100L, "U001", null, true);

        verify(taskService).pass(eq(100L), eq("U001"), any());
    }

    @Test
    void complete_异常包装为ServiceException() {
        org.mockito.Mockito.doThrow(new RuntimeException("锁冲突"))
                .when(taskService).pass(anyLong(), anyString(), any());

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.complete(100L, "U001", "同意", true));
        assertTrue(e.getMessage().contains("完成任务失败"));
    }

    // ---------- ensureStandardFlow ----------

    @Test
    void ensureStandardFlow_已发布则跳过() {
        // 需求④:贷款标准流程与存款独立流程分设,两者均已发布则均跳过
        Definition existing = mockDefinition(300L, "利率审批标准流程");
        when(definitionService.getPublishByFlowCode(WarmFlowService.STANDARD_FLOW_CODE))
                .thenReturn(existing);
        when(definitionService.getPublishByFlowCode(WarmFlowService.DEPOSIT_FLOW_CODE))
                .thenReturn(existing);

        service.ensureStandardFlow();

        verify(definitionService, never()).importDef(any());
        verify(definitionService, never()).publish(anyLong());
    }

    @Test
    void ensureStandardFlow_未发布则创建() {
        // 需求④:两个流程均未发布时各创建一次(贷款含SECRETARY/DGM=dept_gm等修正,存款独立定义)
        when(definitionService.getPublishByFlowCode(WarmFlowService.STANDARD_FLOW_CODE))
                .thenReturn(null);
        when(definitionService.getPublishByFlowCode(WarmFlowService.DEPOSIT_FLOW_CODE))
                .thenReturn(null);
        Definition saved = mockDefinition(301L, "利率审批标准流程");
        when(definitionService.importDef(any())).thenReturn(saved);

        service.ensureStandardFlow();

        verify(definitionService, times(2)).importDef(any());
        verify(definitionService, times(2)).publish(301L);
    }

    @Test
    void ensureStandardFlow_异常不抛出() {
        when(definitionService.getPublishByFlowCode(WarmFlowService.STANDARD_FLOW_CODE))
                .thenReturn(null);
        when(definitionService.getPublishByFlowCode(WarmFlowService.DEPOSIT_FLOW_CODE))
                .thenReturn(null);
        when(definitionService.importDef(any())).thenThrow(new RuntimeException("初始化失败"));

        // 异常被吞掉,不影响系统启动
        service.ensureStandardFlow();
    }

    // ---------- recordBusinessTrail ----------

    @Test
    void recordBusinessTrail_标准流程未发布跳过() {
        when(definitionService.getPublishByFlowCode(WarmFlowService.STANDARD_FLOW_CODE))
                .thenReturn(null);

        // 不抛异常,静默跳过
        service.recordBusinessTrail("PI001", "BRANCH_MANAGER", "PASS", "U001", "同意");

        verify(hisTaskService, never()).save(any());
    }

    @Test
    void recordBusinessTrail_成功记录轨迹() {
        Definition def = mockDefinition(300L, "利率审批标准流程");
        when(definitionService.getPublishByFlowCode(WarmFlowService.STANDARD_FLOW_CODE))
                .thenReturn(def);

        // FlowEngine 静态工厂 mock
        Instance mockInstance = mock(Instance.class, RETURNS_SELF);
        when(mockInstance.getId()).thenReturn(500L);

        HisTask mockHisTask = mock(HisTask.class, RETURNS_SELF);

        try (MockedStatic<FlowEngine> mockedFlowEngine = mockStatic(FlowEngine.class)) {
            mockedFlowEngine.when(FlowEngine::newIns).thenReturn(mockInstance);
            mockedFlowEngine.when(FlowEngine::newHisTask).thenReturn(mockHisTask);

            // 无已有实例 → 创建新实例
            when(instanceService.list(any())).thenReturn(List.of());

            service.recordBusinessTrail("PI001", "BRANCH_MANAGER", "PASS", "U001", "同意");

            verify(instanceService).save(any());
            verify(hisTaskService).save(any());
        }
    }

    @Test
    void recordBusinessTrail_已有实例则更新节点() {
        Definition def = mockDefinition(300L, "利率审批标准流程");
        when(definitionService.getPublishByFlowCode(WarmFlowService.STANDARD_FLOW_CODE))
                .thenReturn(def);

        Instance mockInstance = mock(Instance.class, RETURNS_SELF);
        when(mockInstance.getId()).thenReturn(500L);
        when(mockInstance.getDefinitionId()).thenReturn(300L);

        Instance existingInstance = mock(Instance.class, RETURNS_SELF);
        when(existingInstance.getId()).thenReturn(501L);
        when(existingInstance.getDefinitionId()).thenReturn(300L);

        HisTask mockHisTask = mock(HisTask.class, RETURNS_SELF);

        try (MockedStatic<FlowEngine> mockedFlowEngine = mockStatic(FlowEngine.class)) {
            mockedFlowEngine.when(FlowEngine::newIns).thenReturn(mockInstance);
            mockedFlowEngine.when(FlowEngine::newHisTask).thenReturn(mockHisTask);

            // 已有实例 → 更新
            when(instanceService.list(any())).thenReturn(List.of(existingInstance));

            service.recordBusinessTrail("PI001", "PRESIDENT", "PASS", "U001", "同意");

            verify(instanceService).updateById(any(Instance.class));
            verify(instanceService, never()).save(any());
            verify(hisTaskService).save(any());
        }
    }

    @Test
    void recordBusinessTrail_rejectAction标记为REJECT() {
        Definition def = mockDefinition(300L, "利率审批标准流程");
        when(definitionService.getPublishByFlowCode(WarmFlowService.STANDARD_FLOW_CODE))
                .thenReturn(def);

        Instance mockInstance = mock(Instance.class, RETURNS_SELF);
        when(mockInstance.getId()).thenReturn(500L);

        HisTask mockHisTask = mock(HisTask.class, RETURNS_SELF);

        try (MockedStatic<FlowEngine> mockedFlowEngine = mockStatic(FlowEngine.class)) {
            mockedFlowEngine.when(FlowEngine::newIns).thenReturn(mockInstance);
            mockedFlowEngine.when(FlowEngine::newHisTask).thenReturn(mockHisTask);

            when(instanceService.list(any())).thenReturn(List.of());

            // action 含 REJECT → rejectish=true
            service.recordBusinessTrail("PI001", "BRANCH_MANAGER", "REJECT", "U001", "不同意");

            verify(hisTaskService).save(any());
        }
    }

    @Test
    void recordBusinessTrail_异常不抛出() {
        Definition def = mockDefinition(300L, "利率审批标准流程");
        when(definitionService.getPublishByFlowCode(WarmFlowService.STANDARD_FLOW_CODE))
                .thenReturn(def);
        when(instanceService.list(any())).thenThrow(new RuntimeException("DB异常"));

        // 异常被吞掉,不影响业务主流程
        service.recordBusinessTrail("PI001", "BRANCH_MANAGER", "PASS", "U001", "同意");

        verify(hisTaskService, never()).save(any());
    }
}
