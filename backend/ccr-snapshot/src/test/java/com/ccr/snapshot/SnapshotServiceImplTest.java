package com.ccr.snapshot;

import cn.hutool.crypto.digest.DigestUtil;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.common.exception.ServiceException;
import com.ccr.snapshot.domain.CcrSnapshotBundle;
import com.ccr.snapshot.domain.CcrSnapshotQualityResult;
import com.ccr.snapshot.domain.CcrSnapshotRecord;
import com.ccr.snapshot.domain.CcrSnapshotRelation;
import com.ccr.snapshot.mapper.CcrSnapshotBundleMapper;
import com.ccr.snapshot.mapper.CcrSnapshotQualityResultMapper;
import com.ccr.snapshot.mapper.CcrSnapshotRecordMapper;
import com.ccr.snapshot.mapper.CcrSnapshotRelationMapper;
import com.ccr.snapshot.service.impl.SnapshotServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据快照服务单元测试(§10.5 质量规则、§A.6 快照不可变)
 */
@ExtendWith(MockitoExtension.class)
class SnapshotServiceImplTest {

    @Mock
    private CcrSnapshotBundleMapper bundleMapper;
    @Mock
    private CcrSnapshotRecordMapper recordMapper;
    @Mock
    @SuppressWarnings("unused")
    private CcrSnapshotRelationMapper relationMapper;
    @Mock
    private CcrSnapshotQualityResultMapper qualityMapper;
    @Mock
    @SuppressWarnings("unused")
    private CcrApplicationMapper applicationMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private SnapshotServiceImpl service;

    private CcrSnapshotBundle bundle(Long id, String status) {
        CcrSnapshotBundle b = new CcrSnapshotBundle();
        b.setId(id);
        b.setBundleNo("SB00000001");
        b.setApplicationId(100L);
        b.setRecordCount(0);
        b.setStatus(status);
        return b;
    }

    private CcrSnapshotRecord record(Long id, String subjectId, Map<String, Object> core, Map<String, Object> ext) {
        CcrSnapshotRecord r = new CcrSnapshotRecord();
        r.setId(id);
        r.setBundleId(1L);
        r.setDatasetCode("CORP_BASIC");
        r.setSubjectType("CORPORATE");
        r.setSubjectId(subjectId);
        r.setSourceSystemCode("DW");
        r.setSourceRecordId("SRC-" + subjectId);
        r.setSourceDataDt(LocalDate.now());
        r.setCoreJson(core);
        r.setExtJson(ext);
        return r;
    }

    private void stubFreezingBundle() {
        when(bundleMapper.selectById(1L)).thenReturn(bundle(1L, "FREEZING"));
    }

    @Test
    void createBundleBindsApplicationForDatabaseUniqueness() {
        service.createBundle(100L);

        ArgumentCaptor<CcrSnapshotBundle> captor = ArgumentCaptor.forClass(CcrSnapshotBundle.class);
        verify(bundleMapper).insert(captor.capture());
        assertEquals(100L, captor.getValue().getApplicationId());
        assertEquals("FREEZING", captor.getValue().getStatus());
    }

    /** 问题3:core_json 为 null 时抛业务校验异常而不是数据库 NOT NULL 异常 */
    @Test
    void addRecordRejectsNullCoreJson() {
        stubFreezingBundle();
        CcrSnapshotRecord r = record(null, "C001", null, null);
        assertThrows(ServiceException.class, () -> service.addRecord(1L, r));
    }

    /** 问题3:payload_hash 覆盖 core_json+ext_json,且 Map 键序不影响哈希 */
    @Test
    void payloadHashIsCanonicalAndCoversExtJson() {
        stubFreezingBundle();
        Map<String, Object> core1 = new LinkedHashMap<>();
        core1.put("b", 2);
        core1.put("a", 1);
        Map<String, Object> core2 = new LinkedHashMap<>();
        core2.put("a", 1);
        core2.put("b", 2);

        CcrSnapshotRecord r1 = record(null, "C001", core1, Map.of("x", "1"));
        CcrSnapshotRecord r2 = record(null, "C001", core2, Map.of("x", "1"));
        service.addRecord(1L, r1);
        service.addRecord(1L, r2);
        assertEquals(r1.getPayloadHash(), r2.getPayloadHash(), "键序不同的相同内容应得到相同哈希");

        CcrSnapshotRecord r3 = record(null, "C001", core1, Map.of("x", "2"));
        service.addRecord(1L, r3);
        assertNotEquals(r1.getPayloadHash(), r3.getPayloadHash(), "ext_json 不同应得到不同哈希");
    }

    /** 问题1/7:FROZEN 后登记关系拒绝 */
    @Test
    void addRelationRejectsFrozenBundle() {
        when(bundleMapper.selectById(1L)).thenReturn(bundle(1L, "FROZEN"));
        assertThrows(ServiceException.class, () -> service.addRelation(1L, 10L, 11L, "GROUP_TO_MEMBER", 1));
    }

    /** 问题1:关系两端记录必须属于当前快照包 */
    @Test
    void addRelationRejectsForeignRecord() {
        stubFreezingBundle();
        CcrSnapshotRecord foreign = record(10L, "C001", Map.of("a", 1), null);
        foreign.setBundleId(999L);
        when(recordMapper.selectById(10L)).thenReturn(foreign);
        assertThrows(ServiceException.class, () -> service.addRelation(1L, 10L, 11L, "GROUP_TO_MEMBER", 1));
    }

    /** 问题2/7:FROZEN 包再次 freeze 抛 FLOW_STATUS_CONFLICT,不再重写哈希 */
    @Test
    void freezeRejectsFrozenBundle() {
        when(bundleMapper.selectById(1L)).thenReturn(bundle(1L, "FROZEN"));
        assertThrows(ServiceException.class, () -> service.freeze(1L));
    }

    /** 问题2:validate 幂等——重复调用先按 bundle_id 清理旧质量结果 */
    @Test
    void validateIsIdempotent() {
        stubFreezingBundle();
        when(recordMapper.selectList(any())).thenReturn(List.of(
                record(10L, "C001", Map.of("a", 1), null)));
        when(relationMapper.selectList(any())).thenReturn(List.of());
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(1);

        service.validate(1L);
        service.validate(1L);
        verify(qualityMapper, times(2)).delete(any());
    }

    /** 问题3:bundle_hash = 全部记录 payload_hash 排序拼接后 SHA-256 */
    @Test
    void freezeComputesBundleHashFromSortedPayloadHashes() {
        stubFreezingBundle();
        CcrSnapshotRecord r1 = record(10L, "C001", Map.of("a", 1), null);
        r1.setPayloadHash("bbb");
        CcrSnapshotRecord r2 = record(11L, "C002", Map.of("a", 1), null);
        r2.setPayloadHash("aaa");
        when(recordMapper.selectList(any())).thenReturn(List.of(r1, r2));
        when(relationMapper.selectList(any())).thenReturn(List.of());
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(1);

        CcrSnapshotBundle frozen = service.freeze(1L);
        assertEquals(DigestUtil.sha256Hex("aaa:bbb"), frozen.getBundleHash());
        assertEquals("FROZEN", frozen.getStatus());
    }

    /** 问题6:同 subject_id 多条 ACTIVE 客户主数据 → BLOCK */
    @Test
    void validateBlocksDuplicatedCustomer() {
        stubFreezingBundle();
        CcrSnapshotRecord r1 = record(10L, "C001", Map.of("a", 1), null);
        r1.setStatus("ACTIVE");
        CcrSnapshotRecord r2 = record(11L, "C001", Map.of("a", 2), null);
        r2.setStatus("ACTIVE");
        when(recordMapper.selectList(any())).thenReturn(List.of(r1, r2));
        when(relationMapper.selectList(any())).thenReturn(List.of());
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(1);

        assertEquals("BLOCK", service.validate(1L));
    }

    /** 问题6+任务5:数据时效——来源 data_dt 超过容忍天数(默认3个自然日)→ BLOCK 阻断提交(§9.4) */
    @Test
    void validateBlocksStaleData() {
        ReflectionTestUtils.setField(service, "dataStaleDays", 3);
        stubFreezingBundle();
        CcrSnapshotRecord r1 = record(10L, "C001", Map.of("a", 1), null);
        r1.setSourceDataDt(LocalDate.now().minusDays(30));
        when(recordMapper.selectList(any())).thenReturn(List.of(r1));
        when(relationMapper.selectList(any())).thenReturn(List.of());
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(1);

        assertEquals("BLOCK", service.validate(1L));
    }

    // ---------- 任务6:贡献度勾稽 CONTRIBUTION_RECONCILE(§9.2 D13) ----------

    private CcrSnapshotRecord contributionRecord(Long id, String custNo) {
        CcrSnapshotRecord r = record(id, custNo, Map.of("cust_no", custNo), null);
        r.setDatasetCode("dw_contribution_metric");
        r.setSubjectType("CONTRIBUTION");
        return r;
    }

    private void stubReconcile(List<Map<String, Object>> contributionRows) {
        ReflectionTestUtils.setField(service, "reconcileTolerance", new java.math.BigDecimal("0.01"));
        stubFreezingBundle();
        // 客户快照(避免规则3 WARN 干扰勾稽断言) + 贡献度快照记录
        when(recordMapper.selectList(any())).thenReturn(List.of(
                record(10L, "C001", Map.of("a", 1), null), contributionRecord(11L, "C001")));
        when(relationMapper.selectList(any())).thenReturn(List.of());
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(1);
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object.class), any(Object.class)))
                .thenReturn(contributionRows);
    }

    private Map<String, Object> metricRow(String code, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric_code", code);
        row.put("metric_value", new java.math.BigDecimal(value));
        return row;
    }

    @Test
    void reconcilePassesWhenTotalEqualsDetailSum() {
        stubReconcile(List.of(metricRow("TOTAL", "130.30"), metricRow("M1", "100.30"), metricRow("M2", "30.00")));
        assertEquals("PASS", service.validate(1L));
    }

    @Test
    void reconcileWarnsWhenDiffWithinTolerance() {
        stubReconcile(List.of(metricRow("TOTAL", "100.005"), metricRow("M1", "100.00")));
        assertEquals("WARN", service.validate(1L));
    }

    @Test
    void reconcileBlocksWhenDiffExceedsTolerance() {
        stubReconcile(List.of(metricRow("TOTAL", "130.30"), metricRow("M1", "100.00"), metricRow("M2", "20.00")));

        assertEquals("BLOCK", service.validate(1L));
        // BLOCK 结果写明差额
        ArgumentCaptor<CcrSnapshotQualityResult> captor = ArgumentCaptor.forClass(CcrSnapshotQualityResult.class);
        verify(qualityMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        CcrSnapshotQualityResult blockResult = captor.getAllValues().stream()
                .filter(q -> "CONTRIBUTION_RECONCILE".equals(q.getRuleCode()) && "BLOCK".equals(q.getRuleLevel()))
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(blockResult.getMessage().contains("10.3"),
                "BLOCK 提示应写明差额:" + blockResult.getMessage());
    }

    @Test
    void reconcileSkipsPassWhenNoContributionData() {
        stubReconcile(List.of()); // 数仓无该客户贡献度数据:空跑 PASS
        assertEquals("PASS", service.validate(1L));
    }

    // ---------- 任务10:快照查询出口 + 质量确认 ----------

    @Test
    void bundleContentBuildsRelationTree() {
        when(bundleMapper.selectById(1L)).thenReturn(bundle(1L, "FROZEN"));
        CcrSnapshotRecord r1 = record(10L, "G001", Map.of("a", 1), null);
        CcrSnapshotRecord r2 = record(11L, "M001", Map.of("a", 1), null);
        CcrSnapshotRecord r3 = record(12L, "L001", Map.of("a", 1), null);
        when(recordMapper.selectList(any())).thenReturn(List.of(r1, r2, r3));
        CcrSnapshotRelation rel1 = new CcrSnapshotRelation();
        rel1.setId(100L);
        rel1.setBundleId(1L);
        rel1.setParentRecordId(10L);
        rel1.setChildRecordId(11L);
        rel1.setRelationType("GROUP_TO_MEMBER");
        rel1.setSequenceNo(1);
        CcrSnapshotRelation rel2 = new CcrSnapshotRelation();
        rel2.setId(101L);
        rel2.setBundleId(1L);
        rel2.setParentRecordId(11L);
        rel2.setChildRecordId(12L);
        rel2.setRelationType("MEMBER_TO_LIMIT");
        rel2.setSequenceNo(1);
        when(relationMapper.selectList(any())).thenReturn(List.of(rel1, rel2));

        var content = service.bundleContent(1L);

        assertEquals(3, content.getRecords().size());
        assertEquals(1, content.getRelationTree().size(), "仅集团记录为根");
        var root = content.getRelationTree().get(0);
        assertEquals(10L, root.getRecordId());
        var member = root.getChildren().get(0);
        assertEquals(11L, member.getRecordId());
        assertEquals("GROUP_TO_MEMBER", member.getRelationType());
        assertEquals(12L, member.getChildren().get(0).getRecordId());
        assertEquals("MEMBER_TO_LIMIT", member.getChildren().get(0).getRelationType());
    }

    @Test
    void bundleContentRejectsMissingBundle() {
        when(bundleMapper.selectById(99L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.bundleContent(99L));
    }

    @Test
    void confirmQualityResultWritesConfirmFields() {
        CcrSnapshotQualityResult result = new CcrSnapshotQualityResult();
        result.setId(5L);
        result.setBundleId(1L);
        result.setRuleCode("DATA_TIMELINESS");
        result.setRuleLevel("BLOCK");
        when(qualityMapper.selectById(5L)).thenReturn(result);

        CcrSnapshotQualityResult confirmed = service.confirmQualityResult(5L, 42L);

        assertEquals("CONFIRMED", confirmed.getConfirmStatus());
        assertEquals(42L, confirmed.getConfirmBy());
        org.junit.jupiter.api.Assertions.assertNotNull(confirmed.getConfirmTime());
        verify(qualityMapper).updateById(result);
    }

    @Test
    void confirmQualityResultRejectsMissing() {
        when(qualityMapper.selectById(99L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.confirmQualityResult(99L, 42L));
    }
}
