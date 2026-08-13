package com.ccr.message;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.domain.CcrNotificationRecipient;
import com.ccr.message.domain.CcrNotificationRule;
import com.ccr.message.mapper.CcrNotificationLogMapper;
import com.ccr.message.mapper.CcrNotificationRecipientMapper;
import com.ccr.message.mapper.CcrNotificationRuleMapper;
import com.ccr.message.service.dto.NotificationMessage;
import com.ccr.message.service.impl.NotificationServiceImpl;
import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import com.ccr.message.service.sender.MessageSender;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知服务单元测试(§11.4/§11.6)
 * 覆盖:幂等防重(message_key)、冷却期跳过、最大提醒次数、升级路径、发送重试、批量重试、回执
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private CcrNotificationRuleMapper ruleMapper;
    @Mock
    private CcrNotificationRecipientMapper recipientMapper;
    @Mock
    private CcrNotificationLogMapper logMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private NotificationServiceImpl service;

    private RecipientResolver mockResolver;
    private MessageSender mockSender;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrNotificationLog.class);
        TableInfoHelper.initTableInfo(assistant, CcrNotificationRule.class);
        TableInfoHelper.initTableInfo(assistant, CcrNotificationRecipient.class);
    }

    @BeforeEach
    void setUp() {
        // List<RecipientResolver> / List<MessageSender> 无法通过 @InjectMocks 注入,手动设置
        mockResolver = org.mockito.Mockito.mock(RecipientResolver.class);
        mockSender = org.mockito.Mockito.mock(MessageSender.class);
        lenient().when(mockResolver.supports(anyString())).thenReturn(true);
        lenient().when(mockSender.supports(anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "recipientResolvers", List.of(mockResolver));
        ReflectionTestUtils.setField(service, "messageSenders", List.of(mockSender));
        ReflectionTestUtils.setField(service, "maxRetry", 3);
    }

    // ---------- sendNotification:入参校验 ----------

    @Test
    void sendNotification_recipientType为空抛BAD_REQUEST() {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType(null);
        msg.setRecipientId("U001");

        ServiceException e = assertThrows(ServiceException.class, () -> service.sendNotification(msg));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    @Test
    void sendNotification_recipientType空白抛BAD_REQUEST() {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("  ");
        msg.setRecipientId("U001");

        ServiceException e = assertThrows(ServiceException.class, () -> service.sendNotification(msg));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    @Test
    void sendNotification_recipientId为空抛BAD_REQUEST() {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("USER");
        msg.setRecipientId(null);

        ServiceException e = assertThrows(ServiceException.class, () -> service.sendNotification(msg));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    // ---------- sendNotification:幂等防重 ----------

    @Test
    void sendNotification_messageKey已存在返回null() {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("USER");
        msg.setRecipientId("U001");
        msg.setMessageKey("CCR1-260101-WATCH-abc123");
        msg.setContent("test");

        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertNull(service.sendNotification(msg));
        verify(logMapper, never()).insert(any(CcrNotificationLog.class));
    }

    @Test
    void sendNotification_DuplicateKeyException返回null() {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("USER");
        msg.setRecipientId("U001");
        msg.setMessageKey("CCR1-260101-WATCH-abc123");
        msg.setContent("test");

        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(logMapper.insert(any(CcrNotificationLog.class))).thenThrow(new DuplicateKeyException("uk_message_key"));

        assertNull(service.sendNotification(msg));
    }

    // ---------- sendNotification:正常发送 ----------

    @Test
    void sendNotification_成功发送置SENT() throws Exception {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("USER");
        msg.setRecipientId("U001");
        msg.setMessageKey("KEY001");
        msg.setChannel("SYSTEM");
        msg.setContent("通知内容");

        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        CcrNotificationLog result = service.sendNotification(msg);

        assertNotNull(result);
        assertEquals("SUCCESS", result.getSendStatus());
        assertEquals("SENT", result.getStatus());
        assertEquals("U001", result.getRecipientId());
        assertEquals("SYSTEM", result.getChannel());
        assertEquals("KEY001", result.getMessageKey());
        assertNotNull(result.getSendTime());
        verify(mockSender).send(any());
    }

    @Test
    void sendNotification_channel为空默认SYSTEM() {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("USER");
        msg.setRecipientId("U001");
        msg.setMessageKey("KEY002");
        msg.setContent("通知");

        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        CcrNotificationLog result = service.sendNotification(msg);

        assertEquals("SYSTEM", result.getChannel());
    }

    @Test
    void sendNotification_messageKey为空自动生成() {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("USER");
        msg.setRecipientId("U001");
        msg.setMessageKey(null);
        msg.setContent("通知");

        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        CcrNotificationLog result = service.sendNotification(msg);

        assertNotNull(result.getMessageKey());
        assertEquals("MSG", result.getMessageKey().substring(0, 3)); // 自动生成以 MSG 开头
    }

    @Test
    void sendNotification_ruleVersionId为空默认0() {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("USER");
        msg.setRecipientId("U001");
        msg.setMessageKey("KEY003");
        msg.setContent("通知");

        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        CcrNotificationLog result = service.sendNotification(msg);

        assertEquals(Long.valueOf(0L), result.getRuleVersionId());
    }

    // ---------- sendNotification:ROLE 类型展开 ----------

    @Test
    void sendNotification_ROLE非数字展开为多个用户() throws Exception {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("ROLE");
        msg.setRecipientId("branch_manager"); // 非数字 → 角色编码
        msg.setMessageKey("KEY_ROLE");
        msg.setContent("通知");

        when(mockResolver.resolve(eq("ROLE"), eq("branch_manager"), any())).thenReturn(List.of("U001", "U002"));
        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        CcrNotificationLog result = service.sendNotification(msg);

        // 逐个发送(U001/U002 各一次),返回最后一条
        assertNotNull(result);
        verify(mockSender, times(2)).send(any());
    }

    // ---------- dispatch:发送失败 ----------

    @Test
    void sendNotification_渠道未接入置FAILED() {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("USER");
        msg.setRecipientId("U001");
        msg.setMessageKey("KEY_FAIL");
        msg.setChannel("SMS");

        // 仅一次重试上限:渠道未接入即 FAILED(retryCount 1 >= maxRetry 1)
        ReflectionTestUtils.setField(service, "maxRetry", 1);
        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(mockSender.supports("SMS")).thenReturn(false); // SMS 渠道未接入

        CcrNotificationLog result = service.sendNotification(msg);

        assertEquals("FAILED", result.getSendStatus());
        assertEquals("FAILED", result.getStatus());
        assertEquals(1, result.getRetryCount());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void sendNotification_发送异常重试次数累加() throws Exception {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("USER");
        msg.setRecipientId("U001");
        msg.setMessageKey("KEY_RETRY");
        msg.setChannel("SYSTEM");

        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        doThrow(new RuntimeException("网络超时")).when(mockSender).send(any());

        CcrNotificationLog result = service.sendNotification(msg);

        assertEquals("RETRYING", result.getSendStatus());
        assertEquals("FAILED", result.getStatus());
        assertEquals(1, result.getRetryCount());
        assertEquals("网络超时", result.getErrorMessage());
    }

    @Test
    void sendNotification_错误信息超500字符截断() throws Exception {
        NotificationMessage msg = new NotificationMessage();
        msg.setRecipientType("USER");
        msg.setRecipientId("U001");
        msg.setMessageKey("KEY_LONG");
        msg.setChannel("SYSTEM");

        String longError = "E".repeat(600);
        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        doThrow(new RuntimeException(longError)).when(mockSender).send(any());

        CcrNotificationLog result = service.sendNotification(msg);

        assertEquals(500, result.getErrorMessage().length());
    }

    // ---------- processPendingAndRetry ----------

    @Test
    void processPendingAndRetry_无待处理返回0() {
        when(logMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertEquals(0, service.processPendingAndRetry());
    }

    @Test
    void processPendingAndRetry_全部成功返回处理数() throws Exception {
        CcrNotificationLog log1 = new CcrNotificationLog();
        log1.setId(1L);
        log1.setChannel("SYSTEM");
        CcrNotificationLog log2 = new CcrNotificationLog();
        log2.setId(2L);
        log2.setChannel("SYSTEM");

        when(logMapper.selectList(any(Wrapper.class))).thenReturn(List.of(log1, log2));

        assertEquals(2, service.processPendingAndRetry());
        verify(mockSender, times(2)).send(any());
    }

    @Test
    void processPendingAndRetry_单条失败不中断整批() {
        CcrNotificationLog log1 = new CcrNotificationLog();
        log1.setId(1L);
        log1.setChannel("UNKNOWN"); // 无 sender → dispatch 内部 catch
        CcrNotificationLog log2 = new CcrNotificationLog();
        log2.setId(2L);
        log2.setChannel("SYSTEM");

        when(logMapper.selectList(any(Wrapper.class))).thenReturn(List.of(log1, log2));
        when(mockSender.supports("UNKNOWN")).thenReturn(false);

        // log1 dispatch 失败(SERVICE 层 dispatch 不会抛异常,内部 catch),log2 成功
        int processed = service.processPendingAndRetry();

        assertEquals(2, processed); // 即使发送失败,dispatch 本身执行了就算 processed
    }

    // ---------- markReceipt ----------

    @Test
    void markReceipt_不存在抛NOT_FOUND() {
        when(logMapper.selectById(999L)).thenReturn(null);

        ServiceException e = assertThrows(ServiceException.class, () -> service.markReceipt(999L));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void markReceipt_成功置RECEIVED() {
        CcrNotificationLog log = new CcrNotificationLog();
        log.setId(1L);
        log.setStatus("SENT");
        when(logMapper.selectById(1L)).thenReturn(log);

        CcrNotificationLog result = service.markReceipt(1L);

        assertEquals("RECEIVED", result.getStatus());
        assertNotNull(result.getReceiptTime());
        verify(logMapper).updateById(any(CcrNotificationLog.class));
    }

    // ---------- notifyEvaluation ----------

    @Test
    void notifyEvaluation_参数为空抛BAD_REQUEST() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.notifyEvaluation(null, 1L, "WATCH"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    @Test
    void notifyEvaluation_承诺计划不存在抛NOT_FOUND() {
        when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of());

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.notifyEvaluation(1L, 2L, "WATCH"));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void notifyEvaluation_无活跃规则返回空列表() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", 1L);
        plan.put("plan_no", "P001");
        plan.put("customer_no", "C001");
        Map<String, Object> evaluation = new HashMap<>();
        evaluation.put("id", 2L);
        evaluation.put("data_dt", "2026-08-12");

        when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of(plan));
        when(jdbcTemplate.queryForList(anyString(), eq(2L))).thenReturn(List.of(evaluation));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<CcrNotificationLog> result = service.notifyEvaluation(1L, 2L, "WATCH");

        assertEquals(0, result.size());
    }

    @Test
    void notifyEvaluation_冷却期内跳过接收人() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", 1L);
        plan.put("plan_no", "P001");
        plan.put("customer_no", "C001");
        Map<String, Object> evaluation = new HashMap<>();
        evaluation.put("id", 2L);
        evaluation.put("data_dt", "2026-08-12");

        CcrNotificationRule rule = new CcrNotificationRule();
        rule.setId(10L);
        rule.setRuleNo("NRL001");
        rule.setTriggerLevel("WATCH");
        rule.setChannel("SYSTEM");
        rule.setCoolDownHours(24);
        rule.setMaxRepeatCount(0); // 不限制重复次数,只测冷却

        CcrNotificationRecipient recipient = new CcrNotificationRecipient();
        recipient.setRuleId(10L);
        recipient.setRecipientType("USER");
        recipient.setRecipientValue("U001");

        when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of(plan));
        when(jdbcTemplate.queryForList(anyString(), eq(2L))).thenReturn(List.of(evaluation));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));
        when(recipientMapper.selectList(any(Wrapper.class))).thenReturn(List.of(recipient));
        when(mockResolver.resolve(eq("USER"), eq("U001"), any())).thenReturn(List.of("U001"));
        // 冷却期内已成功发送 → 跳过
        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        List<CcrNotificationLog> result = service.notifyEvaluation(1L, 2L, "WATCH");

        assertEquals(0, result.size());
        verify(logMapper, never()).insert(any(CcrNotificationLog.class));
    }

    @Test
    void notifyEvaluation_重复次数超限跳过接收人() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", 1L);
        plan.put("plan_no", "P001");
        plan.put("customer_no", "C001");
        Map<String, Object> evaluation = new HashMap<>();
        evaluation.put("id", 2L);
        evaluation.put("data_dt", "2026-08-12");

        CcrNotificationRule rule = new CcrNotificationRule();
        rule.setId(10L);
        rule.setRuleNo("NRL001");
        rule.setTriggerLevel("WATCH");
        rule.setChannel("SYSTEM");
        rule.setCoolDownHours(0); // 不冷却,只测重复次数
        rule.setMaxRepeatCount(3);

        CcrNotificationRecipient recipient = new CcrNotificationRecipient();
        recipient.setRuleId(10L);
        recipient.setRecipientType("USER");
        recipient.setRecipientValue("U001");

        when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of(plan));
        when(jdbcTemplate.queryForList(anyString(), eq(2L))).thenReturn(List.of(evaluation));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));
        when(recipientMapper.selectList(any(Wrapper.class))).thenReturn(List.of(recipient));
        when(mockResolver.resolve(eq("USER"), eq("U001"), any())).thenReturn(List.of("U001"));
        // 冷却期检查(count=0,不在冷却期),重复次数检查(count=3,已达上限)
        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 3L);

        List<CcrNotificationLog> result = service.notifyEvaluation(1L, 2L, "WATCH");

        assertEquals(0, result.size());
        verify(logMapper, never()).insert(any(CcrNotificationLog.class));
    }

    @Test
    void notifyEvaluation_正常发送含升级路径() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", 1L);
        plan.put("plan_no", "P001");
        plan.put("customer_no", "C001");
        Map<String, Object> evaluation = new HashMap<>();
        evaluation.put("id", 2L);
        evaluation.put("data_dt", "2026-08-12");

        CcrNotificationRule rule = new CcrNotificationRule();
        rule.setId(10L);
        rule.setRuleNo("NRL001");
        rule.setTriggerLevel("AT_RISK");
        rule.setChannel("SYSTEM");
        rule.setCoolDownHours(0);
        rule.setMaxRepeatCount(0);
        // 升级路径:AT_RISK → 追加 DEPT_GM
        Map<String, Object> upgrade = new HashMap<>();
        upgrade.put("AT_RISK", List.of("DEPT_GM"));
        rule.setUpgradeRuleJson(upgrade);

        CcrNotificationRecipient recipient = new CcrNotificationRecipient();
        recipient.setRuleId(10L);
        recipient.setRecipientType("USER");
        recipient.setRecipientValue("U001");

        when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of(plan));
        when(jdbcTemplate.queryForList(anyString(), eq(2L))).thenReturn(List.of(evaluation));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));
        // 生产代码会对返回列表追加升级接收人,须用可变 List
        when(recipientMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(List.of(recipient)));
        // USER → U001, DEPT_GM → U002
        when(mockResolver.resolve(eq("USER"), eq("U001"), any())).thenReturn(List.of("U001"));
        when(mockResolver.resolve(eq("DEPT_GM"), any(), any())).thenReturn(List.of("U002"));
        // 冷却期和重复次数检查均返回 0(不跳过)
        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        List<CcrNotificationLog> result = service.notifyEvaluation(1L, 2L, "AT_RISK");

        // U001(常规) + U002(升级) = 2 条
        assertEquals(2, result.size());
    }

    @Test
    void notifyEvaluation_模板渲染替换占位符() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", 1L);
        plan.put("plan_no", "P001");
        plan.put("customer_no", "C001");
        Map<String, Object> evaluation = new HashMap<>();
        evaluation.put("id", 2L);
        evaluation.put("data_dt", "2026-08-12");
        evaluation.put("result_status", "AT_RISK");

        CcrNotificationRule rule = new CcrNotificationRule();
        rule.setId(10L);
        rule.setTriggerLevel("AT_RISK");
        rule.setChannel("SYSTEM");
        rule.setCoolDownHours(0);
        rule.setMaxRepeatCount(0);
        rule.setMessageTemplate("计划{planNo}客户{customerNo}结果{resultStatus}");

        CcrNotificationRecipient recipient = new CcrNotificationRecipient();
        recipient.setRuleId(10L);
        recipient.setRecipientType("USER");
        recipient.setRecipientValue("U001");

        when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of(plan));
        when(jdbcTemplate.queryForList(anyString(), eq(2L))).thenReturn(List.of(evaluation));
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));
        when(recipientMapper.selectList(any(Wrapper.class))).thenReturn(List.of(recipient));
        when(mockResolver.resolve(eq("USER"), eq("U001"), any())).thenReturn(List.of("U001"));
        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        List<CcrNotificationLog> result = service.notifyEvaluation(1L, 2L, "AT_RISK");

        assertEquals(1, result.size());
        assertEquals("计划P001客户C001结果AT_RISK", result.get(0).getMessageContent());
    }
}
