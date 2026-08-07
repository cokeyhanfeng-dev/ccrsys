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
import com.ccr.commitment.service.CommitmentService;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.mapper.CcrNotificationLogMapper;
import com.ccr.resolution.domain.CcrResolution;
import com.ccr.resolution.mapper.CcrResolutionMapper;
import com.ccr.resolution.service.ResolutionService;
import com.ccr.vote.read.ApplicationCommitmentRead;
import com.ccr.vote.read.ApplicationCommitmentReadMapper;
import com.ccr.vote.service.impl.ItemFinalizationServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分项终态串联单元测试:决议/承诺生成(异常不阻断+幂等)与主申请状态聚合
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
    private ApplicationCommitmentReadMapper commitmentReadMapper;
    @Mock
    private CcrNotificationLogMapper notificationLogMapper;

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

    @Test
    void approved_createsResolutionAndPlan_andAggregatesFinal() {
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(resolution);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(commitmentRow()));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        finalizationService.afterItemTerminal(10L, "LEVEL_APPROVED");

        // 决议生成,承诺计划回写 resolutionId
        verify(commitmentService).createPlan(argThat(p -> Long.valueOf(500L).equals(p.getResolutionId())),
                anyList(), anyList());
        // 全部 FINAL → 主申请 FINAL + final_time
        verify(applicationMapper).updateById(argThat((CcrApplication a) ->
                "FINAL".equals(a.getStatus()) && a.getFinalTime() != null));
    }

    @Test
    void approved_noCommitmentRows_skipsPlan() {
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(resolution);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        finalizationService.afterItemTerminal(10L, "LEVEL_APPROVED");

        verify(commitmentService, never()).createPlan(any(CcrCommitmentPlan.class),
                anyList(), anyList());
    }

    @Test
    void approved_resolutionIdempotentRepeat_reusesExistingResolution() {
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ServiceException(ErrorCode.IDEMPOTENCY_REPEAT.getCode(), "该分项已存在决议"));
        when(resolutionMapper.selectOne(any(Wrapper.class))).thenReturn(resolution);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(commitmentRow()));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        finalizationService.afterItemTerminal(10L, "LEVEL_APPROVED");

        // 幂等:取原决议继续承诺计划串联
        verify(commitmentService).createPlan(argThat(p -> Long.valueOf(500L).equals(p.getResolutionId())),
                anyList(), anyList());
    }

    @Test
    void approved_resolutionFailure_notBlocking_andNotifiesPending() {
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "分项状态未通过"));
        when(notificationLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        // 不抛异常,主流程不阻断
        finalizationService.afterItemTerminal(10L, "LEVEL_APPROVED");

        // 记录日志 + 通知 PENDING 落库
        verify(notificationLogMapper).insert(argThat((CcrNotificationLog n) ->
                "PENDING".equals(n.getSendStatus()) && n.getMessageKey().startsWith("FINALIZE_FAIL:RESOLUTION:")));
        // 聚合仍执行
        verify(applicationMapper).updateById(argThat((CcrApplication a) -> "FINAL".equals(a.getStatus())));
    }

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

        // 部分终态保持 ROUTING(本就是 ROUTING,无需回写);否决不生成决议
        verify(applicationMapper, never()).updateById(any(CcrApplication.class));
        verify(resolutionService, never()).createResolution(any(), any(), any(), any(), any(), any(), any());
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
    void aggregate_planFailure_notBlocking_andNotifies() {
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(resolution);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(commitmentRow()));
        when(commitmentService.createPlan(any(CcrCommitmentPlan.class), anyList(), anyList()))
                .thenThrow(new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "承诺指标非法"));
        when(notificationLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        finalizationService.afterItemTerminal(10L, "LEVEL_APPROVED");

        verify(notificationLogMapper).insert(argThat((CcrNotificationLog n) ->
                n.getMessageKey().startsWith("FINALIZE_FAIL:COMMITMENT:")));
        verify(applicationMapper).updateById(argThat((CcrApplication a) -> "FINAL".equals(a.getStatus())));
        assertEquals("CORPORATE_SINGLE", application.getCustomerScope());
    }

    @Test
    void groupCommitment_buildsMemberAllocs() {
        application.setCustomerScope("GROUP");
        application.setGroupNo("G001");
        ApplicationCommitmentRead memberRow = commitmentRow();
        memberRow.setMetricScope("GROUP_MEMBER");
        memberRow.setMemberCustomerNo("M001");
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(resolutionService.createResolution(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(resolution);
        when(commitmentReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(memberRow));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        finalizationService.afterItemTerminal(10L, "PRESIDENT_APPROVED");

        // 集团共享口径 + 成员分配按 metricCode 关联
        verify(commitmentService).createPlan(
                argThat(p -> "GROUP".equals(p.getScopeType()) && "GROUP_SHARED".equals(p.getAllocationMode())),
                anyList(),
                argThat((List<CcrCommitmentMemberAlloc> allocs) -> allocs.size() == 1
                        && "M001".equals(allocs.get(0).getMemberCustomerNo())
                        && "DEPOSIT_BALANCE".equals(allocs.get(0).getMetricCode())));
    }
}
