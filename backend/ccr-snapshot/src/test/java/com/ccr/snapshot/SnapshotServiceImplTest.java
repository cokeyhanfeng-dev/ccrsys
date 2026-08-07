package com.ccr.snapshot;

import cn.hutool.crypto.digest.DigestUtil;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.common.exception.ServiceException;
import com.ccr.snapshot.domain.CcrSnapshotBundle;
import com.ccr.snapshot.domain.CcrSnapshotRecord;
import com.ccr.snapshot.mapper.CcrSnapshotBundleMapper;
import com.ccr.snapshot.mapper.CcrSnapshotQualityResultMapper;
import com.ccr.snapshot.mapper.CcrSnapshotRecordMapper;
import com.ccr.snapshot.mapper.CcrSnapshotRelationMapper;
import com.ccr.snapshot.service.impl.SnapshotServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    /** 问题6:数据时效——来源 data_dt 超过容忍天数 → WARN */
    @Test
    void validateWarnsStaleData() {
        ReflectionTestUtils.setField(service, "dataStaleDays", 7);
        stubFreezingBundle();
        CcrSnapshotRecord r1 = record(10L, "C001", Map.of("a", 1), null);
        r1.setSourceDataDt(LocalDate.now().minusDays(30));
        when(recordMapper.selectList(any())).thenReturn(List.of(r1));
        when(relationMapper.selectList(any())).thenReturn(List.of());
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(1);

        assertEquals("WARN", service.validate(1L));
    }
}
