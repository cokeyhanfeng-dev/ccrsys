package com.ccr.vote;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.commitment.domain.CcrCommitmentMemberAlloc;
import com.ccr.commitment.domain.CcrCommitmentPlan;
import com.ccr.commitment.mapper.CcrCommitmentPlanMapper;
import com.ccr.commitment.service.CommitmentService;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.common.outbox.OutboxService;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.mapper.CcrNotificationLogMapper;
import com.ccr.resolution.domain.CcrResolution;
import com.ccr.resolution.mapper.CcrResolutionMapper;
import com.ccr.resolution.service.ResolutionService;
import com.ccr.vote.read.ApplicationCommitmentRead;
import com.ccr.vote.mapper.ApplicationCommitmentReadMapper;
import com.ccr.vote.service.impl.ItemFinalizationServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分项终态串联单元测试(事件驱动改造后):
 * afterItemTerminal 写 RESOLUTION_CREATE 事件(写入失败降级同步);主申请状态聚合;
 * processResolutionCreate/processCommitmentCreate 消费入口(幂等+链式事件)
 */
@ExtendWith(MockitoExtension.class)
class ItemFinalizationServiceImplTest {

    @Mock
    private CcrPricingItemMapper pricingItemMapper;
    @Mock
    private CcrApplicationMapper applicationMapper;
    @Mock
    private ResolutionService resolutionService;
    @Mock
    private CcrResolutionMapper resolutionMapper;
    @Mock
    private CommitmentService commitmentService;
    @Mock
    private CcrCommitmentPlanMapper commitmentPlanMapper;
    @Mock
    private ApplicationCommitmentReadMapper commitmentReadMapper;
    @Mock
    private CcrNotificationLogMapper notificationLogMapper;
    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private ItemFinalizationServiceImpl finalizationService;

    private CcrPricingItem item;
    private CcrApplication application;
    private CcrResolution resolution;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrPricingItem.class);
        TableInfoHelper.initTableInfo(assistant, CcrApplication.class);
        TableInfoHelper.initTableInfo(assistant, CcrResolution.class);
        TableInfoHelper.initTableInfo(assistant, ApplicationCommitmentRead.class);
        TableInfoHelper.initTableInfo(assistant, CcrNotificationLog.class);
        TableInfoHelper.initTableInfo(assistant, CcrCommitmentPlan.class);

        item = new CcrPricingItem();
        item.setId(10L);
        item.setApplicationId(30L);
        item.setPricingItemNo("PI001");
        item.setPricingCarrierType("LOAN_CONTRACT");
        item.setFinalRate(new BigDecimal("3.500000"));
        item.setStatus(PricingItemStatus.FINAL.getCode());

        application = new CcrApplication();
        application.setId(30L);
        application.setCustomerScope("CORPORATE_SINGLE");
        application.setCustomerNo("C001");
        application.setApplicantUserId(1001L);
        application.setStatus("ROUTING");

        resolution = new CcrResolution();
        resolution.setId(500L);
        resolution.setResolutionNo("RES001");
        resolution.setPricingItemId(10L);
    }

    private ApplicationCommitmentRead commitmentRow() {
        ApplicationCommitmentRead row = new ApplicationCommitmentRead();
        row.setApplicationId(30L);
        row.setPricingItemId(10L);
        row.setMetricCode("DEPOSIT_BALANCE");
        row.setTargetType("TARGET_BALANCE");
        row.setBaselineValue(new BigDecimal("100"));
        row.setTargetValue(new BigDecimal("200"));
        row.setUnit("万元");
        row.setMetricScope("PUBLIC");
        return row;
    }

    private Map<String, Object> resolutionPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("pricingItemId", 10L);
        payload.put("pricingItemNo", "PI001");
        payload.put("applicationId", 30L);
        payload.put("finalRate", "3.500000");
        payload.put("carrierType", "LOAN_CONTRACT");
        payload.put("carrierBusinessKey", "PI001");
        payload.put("effectiveFrom", "2026-08-07");
        payload.put("effectiveTo", "2027-02-03");
        payload.put("decisionSource", "LEVEL_APPROVED");
        return payload;
    }

    // ---------- afterItemTerminal:事件发布 + 聚合 ----------

    @Test
    void approved_publishesResolutionCreateEvent_andAggregatesFinal() {
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        finalizationService.afterItemTerminal(10L, "LEVEL_APPROVED");

        // 批准分项:写 RESOLUTION_CREATE 事件(event_no 业务键 item:{id},payload 含利率/载体/决策来源)
        verify(outboxService).publish(eq("RESOLUTION_CREATE"), eq("item:10"),
                argThat((String p) -> p.contains("\"pricingItemId\":10")
                        && p.contains("3.500000") && p.contains("LEVEL_APPROVED")));
        // 同事务不再同步调决议/承诺服务
        verify(resolutionService, never()).createResolution(any(), any(), any(), any(), any(), any(), any());
        verify(commitmentService, never()).createPlan(any(CcrCommitmentPlan.class), anyList(), anyList());
        // 全部 FINAL → 主申请 FINAL + final_time
        verify(applicationMapper).updateById(argThat((CcrApplication a) ->
                "FINAL".equals(a.getStatus()) && a.getFinalTime() != null));
    }

    @Test
    void approved_outboxWriteFailure_degradesToSync() {
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        doThrow(new RuntimeException("outbox 表不可用")).when(outboxService)
                .publish(eq("RESOLUTION_CREATE"), anyString(), anyString());
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(resolution);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(commitmentRow()));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        // 事件表写入失败 → 降级同步串联,不阻断主流程
        finalizationService.afterItemTerminal(10L, "LEVEL_APPROVED");

        verify(resolutionService).createResolution(eq(10L), any(), any(), any(), any(), any(), eq("LEVEL_APPROVED"));
        verify(commitmentService).createPlan(argThat(p -> Long.valueOf(500L).equals(p.getResolutionId())),
                anyList(), anyList());
        verify(applicationMapper).updateById(argThat((CcrApplication a) -> "FINAL".equals(a.getStatus())));
    }

    @Test
    void approved_outboxWriteFailure_syncAlsoFails_notBlockingAndNotifies() {
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        doThrow(new RuntimeException("outbox 表不可用")).when(outboxService)
                .publish(eq("RESOLUTION_CREATE"), anyString(), anyString());
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "分项状态未通过"));
        when(notificationLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        finalizationService.afterItemTerminal(10L, "LEVEL_APPROVED");

        // 降级同步也失败:落 PENDING 通知,聚合仍执行
        verify(notificationLogMapper).insert(argThat((CcrNotificationLog n) ->
                "PENDING".equals(n.getSendStatus()) && n.getMessageKey().startsWith("FINALIZE_FAIL:RESOLUTION:")));
        verify(applicationMapper).updateById(argThat((CcrApplication a) -> "FINAL".equals(a.getStatus())));
    }

    // ---------- processResolutionCreate(RESOLUTION_CREATE 消费) ----------

    @Test
    void processResolutionCreate_success_publishesCommitmentAndNotifyEvents() {
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(resolution);
        when(applicationMapper.selectById(30L)).thenReturn(application);

        finalizationService.processResolutionCreate(resolutionPayload());

        // 决议生成参数来自固化 payload
        verify(resolutionService).createResolution(eq(10L), eq(new BigDecimal("3.500000")),
                eq("LOAN_CONTRACT"), eq("PI001"), any(), any(), eq("LEVEL_APPROVED"));
        // 链式 COMMITMENT_CREATE + 决议签发 NOTIFY
        verify(outboxService).publish(eq("COMMITMENT_CREATE"), eq("item:10"),
                argThat((String p) -> p.contains("\"resolutionId\":500")));
        verify(outboxService).publish(eq("NOTIFY"), eq("RES_ISSUED:500"),
                argThat((String p) -> p.contains("1001") && p.contains("RES001")));
    }

    @Test
    void processResolutionCreate_idempotentRepeat_reusesExistingResolution() {
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ServiceException(ErrorCode.IDEMPOTENCY_REPEAT.getCode(), "该分项已存在决议"));
        when(resolutionMapper.selectOne(any(Wrapper.class))).thenReturn(resolution);
        when(applicationMapper.selectById(30L)).thenReturn(application);

        finalizationService.processResolutionCreate(resolutionPayload());

        // 幂等:取原决议继续链式事件
        verify(outboxService).publish(eq("COMMITMENT_CREATE"), eq("item:10"),
                argThat((String p) -> p.contains("\"resolutionId\":500")));
    }

    @Test
    void processResolutionCreate_resolutionFails_throwsForRetry() {
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ServiceException(ErrorCode.INTERNAL_ERROR.getCode(), "决议服务异常"));

        // 消费失败上抛,由 Outbox 消费者退避重试
        assertThrows(ServiceException.class, () -> finalizationService.processResolutionCreate(resolutionPayload()));
        verify(outboxService, never()).publish(eq("COMMITMENT_CREATE"), anyString(), anyString());
    }

    // ---------- processCommitmentCreate(COMMITMENT_CREATE 消费) ----------

    @Test
    void processCommitmentCreate_createsPlan() {
        Map<String, Object> payload = Map.of("pricingItemId", 10L, "resolutionId", 500L);
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionMapper.selectById(500L)).thenReturn(resolution);
        when(commitmentPlanMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(commitmentRow()));
        when(applicationMapper.selectById(30L)).thenReturn(application);

        finalizationService.processCommitmentCreate(payload);

        verify(commitmentService).createPlan(argThat(p -> Long.valueOf(500L).equals(p.getResolutionId())),
                anyList(), anyList());
    }

    @Test
    void processCommitmentCreate_existingPlan_idempotentSkip() {
        Map<String, Object> payload = Map.of("pricingItemId", 10L, "resolutionId", 500L);
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionMapper.selectById(500L)).thenReturn(resolution);
        when(commitmentPlanMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        finalizationService.processCommitmentCreate(payload);

        verify(commitmentService, never()).createPlan(any(CcrCommitmentPlan.class), anyList(), anyList());
    }

    @Test
    void processCommitmentCreate_noCommitmentRows_skipsPlan() {
        Map<String, Object> payload = Map.of("pricingItemId", 10L, "resolutionId", 500L);
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionMapper.selectById(500L)).thenReturn(resolution);
        when(commitmentPlanMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        finalizationService.processCommitmentCreate(payload);

        verify(commitmentService, never()).createPlan(any(CcrCommitmentPlan.class), anyList(), anyList());
    }

    @Test
    void processCommitmentCreate_groupBuildsMemberAllocs() {
        application.setCustomerScope("GROUP");
        application.setGroupNo("G001");
        ApplicationCommitmentRead memberRow = commitmentRow();
        memberRow.setMetricScope("GROUP_MEMBER");
        memberRow.setMemberCustomerNo("M001");
        Map<String, Object> payload = Map.of("pricingItemId", 10L, "resolutionId", 500L);
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionMapper.selectById(500L)).thenReturn(resolution);
        when(commitmentPlanMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(memberRow));
        when(applicationMapper.selectById(30L)).thenReturn(application);

        finalizationService.processCommitmentCreate(payload);

        // 集团共享口径 + 成员分配按 metricCode 关联
        verify(commitmentService).createPlan(
                argThat(p -> "GROUP".equals(p.getScopeType()) && "GROUP_SHARED".equals(p.getAllocationMode())),
                anyList(),
                argThat((List<CcrCommitmentMemberAlloc> allocs) -> allocs.size() == 1
                        && "M001".equals(allocs.get(0).getMemberCustomerNo())
                        && "DEPOSIT_BALANCE".equals(allocs.get(0).getMetricCode())));
    }

    // ---------- 主申请状态聚合(口径不变) ----------

    @Test
    void aggregate_partialTerminal_keepsRouting() {
        item.setStatus(PricingItemStatus.REJECTED.getCode());
        CcrPricingItem routing = new CcrPricingItem();
        routing.setId(11L);
        routing.setStatus(PricingItemStatus.ROUTING.getCode());
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, routing));

        finalizationService.afterItemTerminal(10L, null);

        // 部分终态保持 ROUTING(本就是 ROUTING,无需回写);否决不写决议事件
        verify(applicationMapper, never()).updateById(any(CcrApplication.class));
        verify(outboxService, never()).publish(eq("RESOLUTION_CREATE"), anyString(), anyString());
    }

    @Test
    void aggregate_allRejected_goesRejected() {
        item.setStatus(PricingItemStatus.VETOED.getCode());
        CcrPricingItem rejected = new CcrPricingItem();
        rejected.setId(11L);
        rejected.setStatus(PricingItemStatus.REJECTED.getCode());
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, rejected));

        finalizationService.afterItemTerminal(10L, null);

        verify(applicationMapper).updateById(argThat((CcrApplication a) ->
                "REJECTED".equals(a.getStatus()) && a.getFinalTime() != null));
    }

    @Test
    void aggregate_mixedTerminal_goesFinal_notStuckRouting() {
        // 全部出终态但批准/否决混合:主申请置 FINAL(已批准部分生效),不再滞留 ROUTING
        item.setStatus(PricingItemStatus.FINAL.getCode());
        CcrPricingItem rejected = new CcrPricingItem();
        rejected.setId(11L);
        rejected.setStatus(PricingItemStatus.REJECTED.getCode());
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, rejected));

        finalizationService.afterItemTerminal(10L, "PRESIDENT_APPROVED");

        verify(outboxService).publish(eq("RESOLUTION_CREATE"), eq("item:10"), contains("PRESIDENT_APPROVED"));
        verify(applicationMapper).updateById(argThat((CcrApplication a) ->
                "FINAL".equals(a.getStatus()) && a.getFinalTime() != null));
    }
}
