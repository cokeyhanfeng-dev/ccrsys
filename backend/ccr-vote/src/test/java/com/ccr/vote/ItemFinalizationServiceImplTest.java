package com.ccr.vote;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.commitment.domain.CcrCommitmentTrack;
import com.ccr.commitment.mapper.CommitmentTrackMapper;
import com.ccr.commitment.service.CommitmentTrackService;
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
 * 分项终态串联单元测试(事件驱动改造后 + v2 承诺跟踪简化):
 * afterItemTerminal 写 RESOLUTION_CREATE 事件(写入失败降级同步);主申请状态聚合;
 * processResolutionCreate/processCommitmentCreate 消费入口(幂等+链式事件);
 * processCommitmentCreate 建 ccr_commitment_track TRACKING 记录(逐行,目标类型收敛映射)
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
    private CommitmentTrackService commitmentTrackService;
    @Mock
    private CommitmentTrackMapper commitmentTrackMapper;
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
        TableInfoHelper.initTableInfo(assistant, CcrCommitmentTrack.class);

        item = new CcrPricingItem();
        item.setId(10L);
        item.setApplicationId(30L);
        item.setPricingItemNo("PI001");
        item.setPricingCarrierType("LOAN_CONTRACT");
        item.setFinalRate(new BigDecimal("3.500000"));
        item.setStatus(PricingItemStatus.FINAL.getCode());

        application = new CcrApplication();
        application.setId(30L);
        application.setApplicationNo("APP001");
        application.setCustomerScope("CORPORATE_SINGLE");
        application.setCustomerNo("C001");
        application.setApplicantUserId(1001L);
        application.setStatus("ROUTING");

        resolution = new CcrResolution();
        resolution.setId(500L);
        resolution.setResolutionNo("RES001");
        resolution.setPricingItemId(10L);
        resolution.setApplicationId(30L);
        resolution.setDecisionSource("LEVEL_APPROVED");
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
        payload.put("applicationId", 30L);
        payload.put("applicationNo", "APP001");
        payload.put("finalRate", "3.500000");
        payload.put("carrierType", "LOAN_CONTRACT");
        payload.put("carrierBusinessKey", "APP001");
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

        // 整单化:写 RESOLUTION_CREATE 事件(event_no 业务键 app:{id},payload 含利率/载体/决策来源)
        verify(outboxService).publish(eq("RESOLUTION_CREATE"), eq("app:30"),
                argThat((String p) -> p.contains("\"applicationId\":30")
                        && p.contains("3.500000") && p.contains("LEVEL_APPROVED")));
        // 同事务不再同步调决议/承诺服务(v2 建跟踪由 COMMITMENT_CREATE 异步消费)
        verify(resolutionService, never()).createResolution(any(), any(), any(), any(), any(), any(), any());
        verify(commitmentTrackService, never()).createTracks(anyList());
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

        // 事件表写入失败 → 降级同步串联,不阻断主流程(v2:同步建 TRACKING 跟踪)
        finalizationService.afterItemTerminal(10L, "LEVEL_APPROVED");

        verify(resolutionService).createResolution(eq(30L), any(), any(), any(), any(), any(), eq("LEVEL_APPROVED"));
        verify(commitmentTrackService).createTracks(argThat(tracks -> tracks.size() == 1
                && "BALANCE".equals(tracks.get(0).getTargetKind())
                && "DEPOSIT_BALANCE".equals(tracks.get(0).getMetricCode())));
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
                "PENDING".equals(n.getSendStatus()) && n.getMessageKey().startsWith("FINALIZE_FAIL:RESOLUTION:app:")));
        verify(applicationMapper).updateById(argThat((CcrApplication a) -> "FINAL".equals(a.getStatus())));
    }

    // ---------- processResolutionCreate(RESOLUTION_CREATE 消费) ----------

    @Test
    void processResolutionCreate_success_publishesCommitmentAndNotifyEvents() {
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(resolution);
        when(applicationMapper.selectById(30L)).thenReturn(application);

        finalizationService.processResolutionCreate(resolutionPayload());

        // 决议生成参数来自固化 payload(整单化:applicationId 维度,carrierBusinessKey=applicationNo)
        verify(resolutionService).createResolution(eq(30L), eq(new BigDecimal("3.500000")),
                eq("LOAN_CONTRACT"), eq("APP001"), any(), any(), eq("LEVEL_APPROVED"));
        // 链式 COMMITMENT_CREATE(app 维度) + 决议签发 NOTIFY
        verify(outboxService).publish(eq("COMMITMENT_CREATE"), eq("app:30"),
                argThat((String p) -> p.contains("\"resolutionId\":500")));
        verify(outboxService).publish(eq("NOTIFY"), eq("RES_ISSUED:500"),
                argThat((String p) -> p.contains("1001") && p.contains("RES001")));
    }

    @Test
    void processResolutionCreate_idempotentRepeat_reusesExistingResolution() {
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ServiceException(ErrorCode.IDEMPOTENCY_REPEAT.getCode(), "该申请已存在决议"));
        when(resolutionMapper.selectOne(any(Wrapper.class))).thenReturn(resolution);
        when(applicationMapper.selectById(30L)).thenReturn(application);

        finalizationService.processResolutionCreate(resolutionPayload());

        // 幂等:取原决议继续链式事件
        verify(outboxService).publish(eq("COMMITMENT_CREATE"), eq("app:30"),
                argThat((String p) -> p.contains("\"resolutionId\":500")));
    }

    @Test
    void processResolutionCreate_resolutionFails_throwsForRetry() {
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ServiceException(ErrorCode.INTERNAL_ERROR.getCode(), "决议服务异常"));

        // 消费失败上抛,由 Outbox 消费者退避重试
        assertThrows(ServiceException.class, () -> finalizationService.processResolutionCreate(resolutionPayload()));
        verify(outboxService, never()).publish(eq("COMMITMENT_CREATE"), anyString(), anyString());
    }

    // ---------- processCommitmentCreate(COMMITMENT_CREATE 消费,v2 建跟踪) ----------

    @Test
    void processCommitmentCreate_createsTracks() {
        Map<String, Object> payload = Map.of("applicationId", 30L, "resolutionId", 500L);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(resolutionMapper.selectById(500L)).thenReturn(resolution);
        when(commitmentTrackMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(commitmentRow()));

        finalizationService.processCommitmentCreate(payload);

        // v2:逐行转 TRACKING 记录(TARGET_BALANCE→BALANCE;manager 取申请人;幂等查 track 表)
        verify(commitmentTrackService).createTracks(argThat(tracks -> tracks.size() == 1
                && "BALANCE".equals(tracks.get(0).getTargetKind())
                && "DEPOSIT_BALANCE".equals(tracks.get(0).getMetricCode())
                && new BigDecimal("200").compareTo(tracks.get(0).getTargetValue()) == 0
                && Long.valueOf(1001L).equals(tracks.get(0).getManagerId())
                && "TRACKING".equals(tracks.get(0).getStatus())));
    }

    @Test
    void processCommitmentCreate_existingTrack_idempotentSkip() {
        Map<String, Object> payload = Map.of("applicationId", 30L, "resolutionId", 500L);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(resolutionMapper.selectById(500L)).thenReturn(resolution);
        when(commitmentTrackMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        finalizationService.processCommitmentCreate(payload);

        verify(commitmentTrackService, never()).createTracks(anyList());
    }

    @Test
    void processCommitmentCreate_noCommitmentRows_skipsTracks() {
        Map<String, Object> payload = Map.of("applicationId", 30L, "resolutionId", 500L);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(resolutionMapper.selectById(500L)).thenReturn(resolution);
        when(commitmentTrackMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        finalizationService.processCommitmentCreate(payload);

        verify(commitmentTrackService, never()).createTracks(anyList());
    }

    @Test
    void processCommitmentCreate_untrackedType_skips() {
        ApplicationCommitmentRead other = commitmentRow();
        other.setTargetType("OTHER");
        Map<String, Object> payload = Map.of("applicationId", 30L, "resolutionId", 500L);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(resolutionMapper.selectById(500L)).thenReturn(resolution);
        when(commitmentTrackMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(other));

        finalizationService.processCommitmentCreate(payload);

        // OTHER 不生成跟踪记录(目标类型收敛:仅 BALANCE/COUNT/RATIO)
        verify(commitmentTrackService, never()).createTracks(anyList());
    }

    @Test
    void processCommitmentCreate_ratioRow_createsRatioTrack() {
        // RATIO 型承诺(存贷比,2026-09-04 按码兜底):target_type=RATIO → target_kind=RATIO,unit 直取行字段(%)
        ApplicationCommitmentRead ratioRow = commitmentRow();
        ratioRow.setTargetType("RATIO");
        ratioRow.setMetricCode("PUBLIC_DEPOSIT_LOAN_RATIO");
        ratioRow.setTargetValue(new BigDecimal("65"));
        ratioRow.setUnit("%");
        Map<String, Object> payload = Map.of("applicationId", 30L, "resolutionId", 500L);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(resolutionMapper.selectById(500L)).thenReturn(resolution);
        when(commitmentTrackMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ratioRow));

        finalizationService.processCommitmentCreate(payload);

        verify(commitmentTrackService).createTracks(argThat(tracks -> tracks.size() == 1
                && "RATIO".equals(tracks.get(0).getTargetKind())
                && "PUBLIC_DEPOSIT_LOAN_RATIO".equals(tracks.get(0).getMetricCode())
                && new BigDecimal("65").compareTo(tracks.get(0).getTargetValue()) == 0
                && "%".equals(tracks.get(0).getUnit())
                && Long.valueOf(1001L).equals(tracks.get(0).getManagerId())
                && "TRACKING".equals(tracks.get(0).getStatus())));
    }

    @Test
    void processCommitmentCreate_memberRow_carriesMemberNo() {
        ApplicationCommitmentRead memberRow = commitmentRow();
        memberRow.setMetricScope("GROUP_MEMBER");
        memberRow.setMemberCustomerNo("M001");
        Map<String, Object> payload = Map.of("applicationId", 30L, "resolutionId", 500L);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(resolutionMapper.selectById(500L)).thenReturn(resolution);
        when(commitmentTrackMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(memberRow));

        finalizationService.processCommitmentCreate(payload);

        // v2:成员承诺逐行落 track,member_customer_no 填行字段(不再集团共享口径换算成员分配)
        verify(commitmentTrackService).createTracks(argThat(tracks -> tracks.size() == 1
                && "M001".equals(tracks.get(0).getMemberCustomerNo())
                && "C001".equals(tracks.get(0).getCustomerNo())));
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

        verify(outboxService).publish(eq("RESOLUTION_CREATE"), eq("app:30"), contains("PRESIDENT_APPROVED"));
        verify(applicationMapper).updateById(argThat((CcrApplication a) ->
                "FINAL".equals(a.getStatus()) && a.getFinalTime() != null));
    }
}
