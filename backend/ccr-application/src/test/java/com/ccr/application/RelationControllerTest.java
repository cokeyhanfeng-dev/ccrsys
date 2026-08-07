package com.ccr.application;

import com.ccr.application.controller.RelationController;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrRelation;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrRelationMapper;
import com.ccr.application.support.AppLoginUser;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关联人唯一绑定单测(§6.2/§10.3.21):判重 / 冲突阻断 / 同客户幂等 / 并发唯一键兜底 / 主客户反查。
 */
@ExtendWith(MockitoExtension.class)
class RelationControllerTest {

    @Mock
    private CcrRelationMapper relationMapper;

    @Mock
    private CcrApplicationMapper applicationMapper;

    @Mock
    private AppLoginUser appLoginUser;

    @InjectMocks
    private RelationController controller;

    @BeforeEach
    void setUp() {
        // lenient:check/applicationRelations 不经过 bind,该 stub 在部分用例中不被消费
        lenient().when(appLoginUser.requireLoginId()).thenReturn(1000L);
    }

    private CcrRelation relation(String customerNo, String groupNo) {
        CcrRelation r = new CcrRelation();
        r.setId(1L);
        r.setCertType("ID_CARD");
        r.setCertNo("320101199001010011");
        r.setRelationName("张三");
        r.setCustomerNo(customerNo);
        r.setGroupNo(groupNo);
        return r;
    }

    private Map<String, Object> bindBody(String customerNo, String groupNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("certType", "ID_CARD");
        body.put("certNo", "320101199001010011");
        body.put("relationName", "张三");
        if (customerNo != null) body.put("customerNo", customerNo);
        if (groupNo != null) body.put("groupNo", groupNo);
        return body;
    }

    @Test
    void check_未绑定_返回boundFalse() {
        when(relationMapper.selectOne(any())).thenReturn(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) controller.check("ID_CARD", "320101199001010011").getData();
        assertFalse((Boolean) r.get("bound"));
    }

    @Test
    void check_已绑定_返回绑定对象() {
        when(relationMapper.selectOne(any())).thenReturn(relation("CUST001", null));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) controller.check("ID_CARD", "320101199001010011").getData();
        assertTrue((Boolean) r.get("bound"));
        assertEquals("CUST001", r.get("boundCustomerNo"));
    }

    @Test
    void check_证件类型非法_阻断() {
        assertThrows(ServiceException.class, () -> controller.check("PASSPORT", "123"));
    }

    @Test
    void bind_未绑定_新建成功() {
        when(relationMapper.selectOne(any())).thenReturn(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) controller.bind(bindBody("CUST001", null)).getData();
        assertTrue((Boolean) r.get("created"));
        verify(relationMapper).insert(any(CcrRelation.class));
    }

    @Test
    void bind_无绑定对象_阻断() {
        // 绑定对象缺失判定先于判重查询,selectOne 不被调用
        Map<String, Object> body = bindBody(null, null);
        assertThrows(ServiceException.class, () -> controller.bind(body));
        verify(relationMapper, never()).insert(any(CcrRelation.class));
    }

    @Test
    void bind_已绑定其他客户_冲突阻断() {
        when(relationMapper.selectOne(any())).thenReturn(relation("CUST002", null));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.bind(bindBody("CUST001", null)));
        assertTrue(ex.getMessage().contains("已绑定其他客户"));
        verify(relationMapper, never()).insert(any(CcrRelation.class));
    }

    @Test
    void bind_同客户_幂等不重复插入() {
        when(relationMapper.selectOne(any())).thenReturn(relation("CUST001", null));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) controller.bind(bindBody("CUST001", null)).getData();
        assertFalse((Boolean) r.get("created"));
        verify(relationMapper, never()).insert(any(CcrRelation.class));
    }

    @Test
    void bind_并发唯一键冲突_同目标幂等() {
        // 判重查询(null)→insert 撞唯一键→并发复查(同目标) → 幂等返回,不抛异常
        when(relationMapper.selectOne(any())).thenReturn(null, relation("CUST001", null));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_relation_cert"))
                .when(relationMapper).insert(any(CcrRelation.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) controller.bind(bindBody("CUST001", null)).getData();
        assertFalse((Boolean) r.get("created"));
    }

    @Test
    void bind_并发唯一键冲突_不同目标阻断() {
        when(relationMapper.selectOne(any())).thenReturn(null, relation("CUST002", null));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_relation_cert"))
                .when(relationMapper).insert(any(CcrRelation.class));
        assertThrows(ServiceException.class, () -> controller.bind(bindBody("CUST001", null)));
    }

    @Test
    void bind_集团场景_绑groupNo() {
        when(relationMapper.selectOne(any())).thenReturn(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) controller.bind(bindBody(null, "GROUP001")).getData();
        assertTrue((Boolean) r.get("created"));
        verify(relationMapper).insert(any(CcrRelation.class));
    }

    @Test
    void bind_提供applicationId_取申请主客户() {
        when(relationMapper.selectOne(any())).thenReturn(null);
        CcrApplication app = new CcrApplication();
        app.setId(9L);
        app.setApplicationNo("APP20260801-0001");
        app.setCustomerScope("CORPORATE_SINGLE");
        app.setCustomerNo("CUST003");
        app.setOrgId(1001L);
        when(applicationMapper.selectById(9L)).thenReturn(app);

        Map<String, Object> body = bindBody(null, null);
        body.put("applicationId", 9L);
        controller.bind(body);
        verify(relationMapper).insert(any(CcrRelation.class));
    }

    @Test
    void applicationRelations_按客户与集团反查() {
        when(applicationMapper.selectById(9L)).thenReturn(appWith("CUST001", "GROUP001"));
        when(relationMapper.selectList(any())).thenReturn(List.of(relation("CUST001", null)));
        List<CcrRelation> list = controller.applicationRelations(9L).getData();
        assertFalse(list.isEmpty());
    }

    private CcrApplication appWith(String customerNo, String groupNo) {
        CcrApplication app = new CcrApplication();
        app.setId(9L);
        app.setCustomerNo(customerNo);
        app.setGroupNo(groupNo);
        return app;
    }
}
