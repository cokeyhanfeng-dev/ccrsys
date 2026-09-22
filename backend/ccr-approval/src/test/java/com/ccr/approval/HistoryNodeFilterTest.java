package com.ccr.approval;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.approval.service.impl.ApprovalServiceImpl;
import com.ccr.common.exception.ServiceException;
import com.ccr.vote.read.SysUserRead;
import com.ccr.vote.support.CurrentLoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 历史岗位过滤必须在分页前生效，并与登录人的对象范围取交集。 */
@ExtendWith(MockitoExtension.class)
class HistoryNodeFilterTest {
    @Mock private CcrApplicationMapper applicationMapper;
    @Mock private CurrentLoginUser currentLoginUser;
    @InjectMocks private ApprovalServiceImpl service;
    private LambdaQueryWrapper<CcrApplication> query;

    @BeforeEach
    void init() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CcrApplication.class);
    }

    private void login(String role) {
        SysUserRead user = new SysUserRead();
        user.setId(123L);
        user.setRoleCode(role);
        when(currentLoginUser.requireCurrentUser()).thenReturn(user);
    }

    private void capturePage() {
        when(applicationMapper.selectPage(any(Page.class), any())).thenAnswer(call -> {
            query = call.getArgument(1);
            return new Page<CcrApplication>(1, 10, 0);
        });
    }

    @Test
    void customerNodeFilterKeepsOwnerAndOtherFilters() {
        login("customer_manager");
        capturePage();
        service.pageHistory(1, 10, "CCR", "ROUTING", "测试客户", " BRANCH_MANAGER ");
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("applicant_user_id ="));
        assertTrue(sql.contains("hp.application_id = ccr_application.id"));
        assertTrue(sql.contains("hp.del_flag = '0'"));
        assertTrue(sql.contains("status NOT IN"));
        assertTrue(sql.contains("application_no LIKE"));
        assertTrue(sql.contains("JSON_EXTRACT"));
        var values = query.getParamNameValuePairs().values();
        assertTrue(values.contains(123L));
        assertTrue(values.contains("BRANCH_MANAGER"));
        assertTrue(values.containsAll(List.of("DRAFT", "FINAL", "REJECTED", "VETOED", "CLOSED")));
    }

    @Test
    void secretaryFilterKeepsParticipationScope() {
        login("secretary");
        capturePage();
        service.pageHistory(1, 10, null, null, null, "SECRETARY");
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("operator_id = 123"));
        assertTrue(sql.contains("EXISTS (SELECT 1 FROM ccr_pricing_item"));
        assertTrue(query.getParamNameValuePairs().containsValue("SECRETARY"));
    }

    @Test
    void presidentFilterUsesPostVoteStatusInsteadOfStaleGroupNode() {
        login("admin");
        capturePage();
        service.pageHistory(1, 10, null, null, null, "PRESIDENT");
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("hp.status IN ('COMMITTEE_PASS','PRESIDENT_DECISION') THEN 'PRESIDENT'"));
        assertTrue(sql.contains("hp.status = 'VOTING' THEN 'SIX_PEOPLE_GROUP'"));
        assertFalse(sql.contains("operator_id"));
        assertTrue(query.getParamNameValuePairs().containsValue("PRESIDENT"));
    }

    @Test
    void noNodeRetainsUnfilteredHistoryIncludingTerminalRecords() {
        login("admin");
        capturePage();
        for (String node : new String[]{null, "", "   "}) {
            service.pageHistory(1, 10, null, null, null, node);
            assertFalse(query.getSqlSegment().contains("hp."));
            assertFalse(query.getSqlSegment().contains("status NOT IN"));
        }
    }

    @Test
    void invalidNodeRejectedBeforeDatabaseQuery() {
        login("admin");
        for (String node : List.of("branch_manager", "UNKNOWN", "BRANCH_MANAGER' OR 1=1 --")) {
            assertThrows(ServiceException.class, () -> service.pageHistory(1, 10, null, null, null, node));
        }
        verifyNoInteractions(applicationMapper);
    }
}
