package com.ccr.commitment.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.commitment.domain.CcrTrackingPolicy;
import com.ccr.commitment.domain.CcrTrackingPolicyVersion;
import com.ccr.commitment.mapper.CcrCommitmentMetricMapper;
import com.ccr.commitment.mapper.CcrCommitmentPlanMapper;
import com.ccr.commitment.mapper.CcrTrackingEvaluationMapper;
import com.ccr.commitment.mapper.CcrTrackingPolicyMapper;
import com.ccr.commitment.mapper.CcrTrackingPolicyVersionMapper;
import com.ccr.commitment.mapper.CcrTrackingThresholdMapper;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 跟踪策略状态机、双人复核与同维度替换回归测试。 */
@ExtendWith(MockitoExtension.class)
class TrackingPolicyStatusTest {

    @Mock
    private CcrTrackingPolicyMapper policyMapper;
    @Mock
    private CcrTrackingPolicyVersionMapper versionMapper;
    @Mock
    private CcrTrackingThresholdMapper thresholdMapper;
    @Mock
    private CcrCommitmentPlanMapper planMapper;
    @Mock
    private CcrCommitmentMetricMapper metricMapper;
    @Mock
    private CcrTrackingEvaluationMapper evaluationMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private TrackingPolicyServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrTrackingPolicy.class);
        TableInfoHelper.initTableInfo(assistant, CcrTrackingPolicyVersion.class);
    }

    @Test
    void policyStatus_rejectsSkippedTransition() {
        CcrTrackingPolicy policy = policy(1L, "DRAFT", 7L, "M1", "LOAN", "ORG1");
        when(policyMapper.selectById(1L)).thenReturn(policy);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.changePolicyStatus(1L, "EFFECTIVE"));

        assertEquals(ErrorCode.DATA_VERSION_CONFLICT.getCode(), exception.getCode());
        verify(policyMapper, never()).updateById(any(CcrTrackingPolicy.class));
    }

    @Test
    void policyPublish_rejectsCreatorSelfReview() {
        CcrTrackingPolicy policy = policy(1L, "REVIEW", 7L, "M1", "LOAN", "ORG1");
        when(policyMapper.selectById(1L)).thenReturn(policy);

        try (MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);

            ServiceException exception = assertThrows(ServiceException.class,
                    () -> service.changePolicyStatus(1L, "EFFECTIVE"));

            assertEquals(ErrorCode.FORBIDDEN.getCode(), exception.getCode());
            verify(policyMapper, never()).updateById(any(CcrTrackingPolicy.class));
        }
    }

    @Test
    void policyPublish_invalidatesOnlySameDimensionPolicy() {
        CcrTrackingPolicy publishing = policy(1L, "REVIEW", 7L, "M1", "LOAN", "ORG1");
        CcrTrackingPolicy sameDimension = policy(2L, "EFFECTIVE", 5L, "M1", "LOAN", "ORG1");
        CcrTrackingPolicy otherDimension = policy(3L, "EFFECTIVE", 5L, "M2", "LOAN", "ORG1");
        when(policyMapper.selectById(1L)).thenReturn(publishing);
        when(policyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(sameDimension, otherDimension));

        try (MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(8L);

            service.changePolicyStatus(1L, "EFFECTIVE");
        }

        assertEquals("EFFECTIVE", publishing.getStatus());
        assertEquals("INVALID", sameDimension.getStatus());
        assertEquals("EFFECTIVE", otherDimension.getStatus());
        verify(policyMapper).updateById(sameDimension);
        verify(policyMapper).updateById(publishing);
        verify(policyMapper, never()).updateById(otherDimension);
    }

    @Test
    void policyStatus_allowsReviewToReturnDraft() {
        CcrTrackingPolicy policy = policy(1L, "REVIEW", 7L, "M1", null, null);
        when(policyMapper.selectById(1L)).thenReturn(policy);

        service.changePolicyStatus(1L, "DRAFT");

        assertEquals("DRAFT", policy.getStatus());
        verify(policyMapper).updateById(policy);
    }

    @Test
    void versionStatus_rejectsSkippedTransition() {
        CcrTrackingPolicyVersion version = new CcrTrackingPolicyVersion();
        version.setId(10L);
        version.setStatus("DRAFT");
        version.setCreateBy(7L);
        when(versionMapper.selectById(10L)).thenReturn(version);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.changeVersionStatus(10L, "EFFECTIVE"));

        assertEquals(ErrorCode.DATA_VERSION_CONFLICT.getCode(), exception.getCode());
        verify(versionMapper, never()).updateById(any(CcrTrackingPolicyVersion.class));
    }

    private CcrTrackingPolicy policy(Long id, String status, Long createBy,
                                     String metricCode, String businessType, String orgCode) {
        CcrTrackingPolicy policy = new CcrTrackingPolicy();
        policy.setId(id);
        policy.setStatus(status);
        policy.setCreateBy(createBy);
        policy.setMetricCode(metricCode);
        policy.setBusinessType(businessType);
        policy.setOrgCode(orgCode);
        return policy;
    }
}
