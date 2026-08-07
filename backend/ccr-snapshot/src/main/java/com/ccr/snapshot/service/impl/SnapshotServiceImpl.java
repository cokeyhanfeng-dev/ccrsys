package com.ccr.snapshot.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.snapshot.domain.CcrSnapshotBundle;
import com.ccr.snapshot.domain.CcrSnapshotQualityResult;
import com.ccr.snapshot.domain.CcrSnapshotRecord;
import com.ccr.snapshot.domain.CcrSnapshotRelation;
import com.ccr.snapshot.dto.SnapshotBundleContent;
import com.ccr.snapshot.mapper.CcrSnapshotBundleMapper;
import com.ccr.snapshot.mapper.CcrSnapshotQualityResultMapper;
import com.ccr.snapshot.mapper.CcrSnapshotRecordMapper;
import com.ccr.snapshot.mapper.CcrSnapshotRelationMapper;
import com.ccr.snapshot.service.SnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据快照服务实现
 * 校验规则(§10.5):必填字段、记录数、BLOCK 级规则阻断提交
 * 首期子集之外补充:客户唯一性、数据时效、贡献度统计区间一致性、集团成员有效性
 */
@Service
public class SnapshotServiceImpl implements SnapshotService {

    /** 快照包状态:冻结中 */
    private static final String STATUS_FREEZING = "FREEZING";
    /** 快照包状态:已冻结 */
    private static final String STATUS_FROZEN = "FROZEN";
    /** 客户类主体类型(客户主数据快照) */
    private static final Set<String> CUSTOMER_SUBJECT_TYPES = Set.of("INDIVIDUAL", "CORPORATE", "CUSTOMER");
    /** 失效记录状态(§10.2 数据集统一字段 record_status) */
    private static final Set<String> INVALID_RECORD_STATUS = Set.of("INACTIVE", "DELETED");

    /** 内容哈希规范化序列化(Map 按键排序,保证同一内容哈希稳定) */
    private static final ObjectMapper HASH_MAPPER =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Resource
    private CcrSnapshotBundleMapper bundleMapper;
    @Resource
    private CcrSnapshotRecordMapper recordMapper;
    @Resource
    private CcrSnapshotRelationMapper relationMapper;
    @Resource
    private CcrSnapshotQualityResultMapper qualityMapper;
    @Resource
    private CcrApplicationMapper applicationMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 数据时效容忍天数:来源 data_dt 距当前超过该天数判 BLOCK(§9.4 默认 3 个自然日,阻断提交) */
    @Value("${ccr.snapshot.data-stale-days:3}")
    private int dataStaleDays;

    /** 贡献度勾稽容差(§9.2 D13:TOTAL行与明细行加总差额 ≤ 容差 WARN,> 容差 BLOCK) */
    @Value("${ccr.snapshot.reconcile-tolerance:0.01}")
    private java.math.BigDecimal reconcileTolerance;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrSnapshotBundle createBundle(Long applicationId) {
        CcrSnapshotBundle bundle = new CcrSnapshotBundle();
        bundle.setBundleNo("SB" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        bundle.setApplicationId(applicationId);
        bundle.setRecordCount(0);
        bundle.setStatus(STATUS_FREEZING);
        bundleMapper.insert(bundle);
        return bundle;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addRecord(Long bundleId, CcrSnapshotRecord record) {
        CcrSnapshotBundle bundle = requireFreezing(bundleId, "追加记录");
        if (record == null || StrUtil.isBlank(record.getDatasetCode())
                || StrUtil.isBlank(record.getSubjectType()) || StrUtil.isBlank(record.getSubjectId())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "快照记录缺少必填字段");
        }
        if (record.getCoreJson() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "快照记录 core_json 不能为空(§A.6)");
        }
        record.setId(null);
        record.setBundleId(bundleId);
        if (record.getDatasetVersionId() == null) {
            record.setDatasetVersionId(1L); // 默认数据集版本
        }
        record.setPayloadHash(payloadHash(record));
        recordMapper.insert(record);
        bundle.setRecordCount((bundle.getRecordCount() == null ? 0 : bundle.getRecordCount()) + 1);
        bundleMapper.updateById(bundle);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addRelation(Long bundleId, Long parentRecordId, Long childRecordId, String relationType, Integer sequenceNo) {
        requireFreezing(bundleId, "登记关系");
        if (parentRecordId == null || childRecordId == null || StrUtil.isBlank(relationType)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "快照关系缺少必填字段(父/子记录、关系类型)");
        }
        requireBundleRecord(bundleId, parentRecordId);
        requireBundleRecord(bundleId, childRecordId);
        CcrSnapshotRelation relation = new CcrSnapshotRelation();
        relation.setBundleId(bundleId);
        relation.setParentRecordId(parentRecordId);
        relation.setChildRecordId(childRecordId);
        relation.setRelationType(relationType);
        relation.setSequenceNo(sequenceNo == null ? 1 : sequenceNo);
        relationMapper.insert(relation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addRelations(Long bundleId, List<CcrSnapshotRelation> relations) {
        if (relations == null || relations.isEmpty()) {
            return;
        }
        for (CcrSnapshotRelation r : relations) {
            addRelation(bundleId, r.getParentRecordId(), r.getChildRecordId(), r.getRelationType(), r.getSequenceNo());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String validate(Long bundleId) {
        CcrSnapshotBundle bundle = requireFreezing(bundleId, "质量校验");
        // 幂等:重复校验先清理旧质量结果再重算
        qualityMapper.delete(new LambdaQueryWrapper<CcrSnapshotQualityResult>()
                .eq(CcrSnapshotQualityResult::getBundleId, bundleId));

        List<CcrSnapshotRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<CcrSnapshotRecord>().eq(CcrSnapshotRecord::getBundleId, bundleId));
        int block = 0;
        int warn = 0;
        // 规则1:快照包非空
        addQuality(bundle, "SNAPSHOT_NOT_EMPTY", null, null,
                records.isEmpty() ? "BLOCK" : "PASS", "快照记录数", String.valueOf(records.size()), null);
        if (records.isEmpty()) block++;
        // 规则2:每条记录必须带来源主键与数据日期
        for (CcrSnapshotRecord r : records) {
            boolean complete = StrUtil.isNotBlank(r.getSourceRecordId()) && r.getSourceDataDt() != null;
            addQuality(bundle, "RECORD_SOURCE_COMPLETE", r.getSubjectType(), r.getSubjectId(),
                    complete ? "PASS" : "BLOCK", "来源主键+数据日期", r.getSubjectId(), null);
            if (!complete) block++;
        }
        // 规则3:客户快照 core_json 必须存在
        boolean hasCustomer = records.stream().anyMatch(r -> CUSTOMER_SUBJECT_TYPES.contains(r.getSubjectType()));
        addQuality(bundle, "CUSTOMER_SNAPSHOT_REQUIRED", null, null,
                hasCustomer ? "PASS" : "WARN", "客户快照", hasCustomer ? "存在" : "未找到客户快照", null);
        if (!hasCustomer) warn++;

        // 规则4:客户唯一性——同 subject_id 多条 ACTIVE 客户主数据判 BLOCK(§10.5)
        Map<String, Long> activeCustomerCount = records.stream()
                .filter(r -> CUSTOMER_SUBJECT_TYPES.contains(r.getSubjectType()))
                .filter(r -> r.getStatus() == null || "ACTIVE".equals(r.getStatus()))
                .collect(Collectors.groupingBy(CcrSnapshotRecord::getSubjectId, Collectors.counting()));
        List<String> duplicated = activeCustomerCount.entrySet().stream()
                .filter(e -> e.getValue() > 1).map(Map.Entry::getKey).sorted().toList();
        if (duplicated.isEmpty()) {
            addQuality(bundle, "CUSTOMER_UNIQUENESS", null, null, "PASS",
                    "同客户仅一条 ACTIVE 主数据", "通过", null);
        } else {
            for (String subjectId : duplicated) {
                addQuality(bundle, "CUSTOMER_UNIQUENESS", "CUSTOMER", subjectId, "BLOCK",
                        "同客户仅一条 ACTIVE 主数据", "重复 " + activeCustomerCount.get(subjectId) + " 条",
                        "同一客户存在多条有效主数据快照");
            }
            block += duplicated.size();
        }

        // 规则5:数据时效——来源 data_dt 距当前超过配置天数判 BLOCK(§9.4:超时阻断提交)
        LocalDate staleBefore = LocalDate.now().minusDays(dataStaleDays);
        List<CcrSnapshotRecord> stale = records.stream()
                .filter(r -> r.getSourceDataDt() != null && r.getSourceDataDt().isBefore(staleBefore))
                .toList();
        if (stale.isEmpty()) {
            addQuality(bundle, "DATA_TIMELINESS", null, null, "PASS",
                    "data_dt 不早于 " + staleBefore, "通过", null);
        } else {
            for (CcrSnapshotRecord r : stale) {
                addQuality(bundle, "DATA_TIMELINESS", r.getSubjectType(), r.getSubjectId(), "BLOCK",
                        "data_dt 不早于 " + staleBefore, String.valueOf(r.getSourceDataDt()),
                        "数据源数据日期过期,请联系数据中心刷新(容忍 " + dataStaleDays + " 个自然日)");
            }
            block += stale.size();
        }

        // 规则6:贡献度统计区间一致性(§10.3 stat_start/stat_end/calc_version,空跑容忍)
        warn += checkContributionConsistency(bundle, records);

        // 规则7:集团成员有效性——GROUP_TO_MEMBER 成员快照缺失或失效判 WARN(§10.5,空跑通过)
        warn += checkGroupMemberValid(bundle, records);

        // 规则8:贡献度勾稽(§9.2 D13)——TOTAL 行与明细行加总比对,差额>容差判 BLOCK
        int[] reconcile = checkContributionReconcile(bundle, records);
        block += reconcile[0];
        warn += reconcile[1];

        return block > 0 ? "BLOCK" : (warn > 0 ? "WARN" : "PASS");
    }

    @Override
    public Map<String, List<CcrSnapshotQualityResult>> qualityResults(Long bundleId) {
        CcrSnapshotBundle bundle = bundleMapper.selectById(bundleId);
        if (bundle == null) {
            throw new ServiceException(404, "快照包不存在");
        }
        List<CcrSnapshotQualityResult> results = qualityMapper.selectList(
                new LambdaQueryWrapper<CcrSnapshotQualityResult>()
                        .eq(CcrSnapshotQualityResult::getBundleId, bundleId)
                        .orderByAsc(CcrSnapshotQualityResult::getRuleCode)
                        .orderByAsc(CcrSnapshotQualityResult::getId));
        Map<String, List<CcrSnapshotQualityResult>> grouped = new LinkedHashMap<>();
        grouped.put("PASS", new ArrayList<>());
        grouped.put("WARN", new ArrayList<>());
        grouped.put("BLOCK", new ArrayList<>());
        for (CcrSnapshotQualityResult r : results) {
            grouped.computeIfAbsent(r.getRuleLevel(), k -> new ArrayList<>()).add(r);
        }
        return grouped;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrSnapshotBundle freeze(Long bundleId) {
        CcrSnapshotBundle bundle = requireFreezing(bundleId, "冻结");
        String level = validate(bundleId);
        if ("BLOCK".equals(level)) {
            throw new ServiceException(ErrorCode.QUALITY_BLOCK.getCode(), "数据质量阻断,不能冻结提交");
        }
        // 整包哈希:全部记录 payload_hash 排序拼接后 SHA-256
        List<CcrSnapshotRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<CcrSnapshotRecord>().eq(CcrSnapshotRecord::getBundleId, bundleId));
        String joined = records.stream()
                .map(CcrSnapshotRecord::getPayloadHash)
                .filter(StrUtil::isNotBlank)
                .sorted()
                .collect(Collectors.joining(":"));
        bundle.setBundleHash(DigestUtil.sha256Hex(joined));
        bundle.setFreezeTime(LocalDateTime.now());
        bundle.setStatus(STATUS_FROZEN);
        bundleMapper.updateById(bundle);

        // 绑定申请快照包(不可变)
        CcrApplication application = applicationMapper.selectById(bundle.getApplicationId());
        if (application != null) {
            application.setSnapshotBundleId(bundleId);
            applicationMapper.updateById(application);
        }
        return bundle;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrSnapshotBundle submitSnapshot(Long applicationId, List<CcrSnapshotRecord> records) {
        CcrSnapshotBundle bundle = createBundle(applicationId);
        if (records != null) {
            for (CcrSnapshotRecord r : records) {
                addRecord(bundle.getId(), r);
            }
        }
        return freeze(bundle.getId());
    }

    // ---------- 快照查询出口(§11.7) ----------

    @Override
    public SnapshotBundleContent bundleContent(Long bundleId) {
        CcrSnapshotBundle bundle = bundleMapper.selectById(bundleId);
        if (bundle == null) {
            throw new ServiceException(404, "快照包不存在");
        }
        List<CcrSnapshotRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<CcrSnapshotRecord>()
                        .eq(CcrSnapshotRecord::getBundleId, bundleId)
                        .orderByAsc(CcrSnapshotRecord::getId));
        List<CcrSnapshotRelation> relations = relationMapper.selectList(
                new LambdaQueryWrapper<CcrSnapshotRelation>()
                        .eq(CcrSnapshotRelation::getBundleId, bundleId)
                        .orderByAsc(CcrSnapshotRelation::getSequenceNo)
                        .orderByAsc(CcrSnapshotRelation::getId));

        SnapshotBundleContent content = new SnapshotBundleContent();
        content.setBundle(bundle);
        content.setRecords(records);
        content.setRelationTree(buildRelationTree(records, relations));
        return content;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrSnapshotQualityResult confirmQualityResult(Long id, Long operatorId) {
        CcrSnapshotQualityResult result = qualityMapper.selectById(id);
        if (result == null) {
            throw new ServiceException(404, "质量校验结果不存在");
        }
        result.setConfirmStatus("CONFIRMED");
        result.setConfirmBy(operatorId);
        result.setConfirmTime(LocalDateTime.now());
        qualityMapper.updateById(result);
        return result;
    }

    /** 关系树:根=不作为任何关系子节点的记录;沿 parent→child 递归,环路防御 */
    private List<SnapshotBundleContent.RelationNode> buildRelationTree(List<CcrSnapshotRecord> records,
                                                                       List<CcrSnapshotRelation> relations) {
        Map<Long, List<CcrSnapshotRelation>> byParent = relations.stream()
                .collect(Collectors.groupingBy(CcrSnapshotRelation::getParentRecordId,
                        LinkedHashMap::new, Collectors.toList()));
        Set<Long> childIds = relations.stream()
                .map(CcrSnapshotRelation::getChildRecordId)
                .collect(Collectors.toSet());
        List<SnapshotBundleContent.RelationNode> roots = new ArrayList<>();
        for (CcrSnapshotRecord record : records) {
            if (!childIds.contains(record.getId())) {
                roots.add(buildNode(record.getId(), null, null, byParent, new ArrayList<>()));
            }
        }
        return roots;
    }

    private SnapshotBundleContent.RelationNode buildNode(Long recordId, String relationType, Integer sequenceNo,
                                                         Map<Long, List<CcrSnapshotRelation>> byParent,
                                                         List<Long> path) {
        SnapshotBundleContent.RelationNode node = new SnapshotBundleContent.RelationNode();
        node.setRecordId(recordId);
        node.setRelationType(relationType);
        node.setSequenceNo(sequenceNo);
        node.setChildren(new ArrayList<>());
        if (path.contains(recordId)) {
            return node; // 环路防御:异常数据不构成死循环
        }
        path.add(recordId);
        for (CcrSnapshotRelation rel : byParent.getOrDefault(recordId, List.of())) {
            node.getChildren().add(buildNode(rel.getChildRecordId(), rel.getRelationType(),
                    rel.getSequenceNo(), byParent, path));
        }
        path.remove(path.size() - 1);
        return node;
    }

    // ---------- 私有 ----------

    /**
     * 快照包状态门禁:仅 FREEZING 允许变更/校验/冻结,FROZEN 一律拒绝(§A.6 快照不可变)
     */
    private CcrSnapshotBundle requireFreezing(Long bundleId, String action) {
        CcrSnapshotBundle bundle = bundleMapper.selectById(bundleId);
        if (bundle == null) {
            throw new ServiceException(404, "快照包不存在");
        }
        if (!STATUS_FREEZING.equals(bundle.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "快照包已冻结,禁止" + action + "(§A.6)");
        }
        return bundle;
    }

    /**
     * 校验记录存在且属于当前快照包
     */
    private void requireBundleRecord(Long bundleId, Long recordId) {
        CcrSnapshotRecord record = recordMapper.selectById(recordId);
        if (record == null || !bundleId.equals(record.getBundleId())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "快照记录不存在或不属于当前快照包:" + recordId);
        }
    }

    /**
     * 记录内容哈希:core_json+ext_json 经 Jackson 规范化(键排序)序列化后 SHA-256
     */
    private String payloadHash(CcrSnapshotRecord record) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("core", record.getCoreJson());
            payload.put("ext", record.getExtJson());
            return DigestUtil.sha256Hex(HASH_MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "快照内容序列化失败,无法计算内容哈希");
        }
    }

    /**
     * 贡献度统计区间一致性:同客户同批次 stat_start/stat_end/calc_version 不一致判 WARN(§10.3/§10.5)
     * dw_contribution_metric 由数仓落地,表暂无数据或列未就绪时规则空跑通过
     */
    private int checkContributionConsistency(CcrSnapshotBundle bundle, List<CcrSnapshotRecord> records) {
        List<String> customerIds = records.stream()
                .filter(r -> CUSTOMER_SUBJECT_TYPES.contains(r.getSubjectType()))
                .map(CcrSnapshotRecord::getSubjectId)
                .distinct().sorted().toList();
        if (customerIds.isEmpty()) {
            addQuality(bundle, "CONTRIBUTION_STAT_CONSISTENCY", null, null, "PASS",
                    "同客户同批次统计区间/折算版本一致", "无客户快照,空跑通过", null);
            return 0;
        }
        int warn = 0;
        try {
            for (String custNo : customerIds) {
                Integer versions = jdbcTemplate.queryForObject("""
                        SELECT COUNT(DISTINCT stat_start, stat_end, calc_version)
                        FROM dw_contribution_metric
                        WHERE cust_no = ?
                          AND data_dt = (SELECT MAX(data_dt) FROM dw_contribution_metric WHERE cust_no = ?)
                        """, Integer.class, custNo, custNo);
                if (versions != null && versions > 1) {
                    addQuality(bundle, "CONTRIBUTION_STAT_CONSISTENCY", "CUSTOMER", custNo, "WARN",
                            "同客户同批次统计区间/折算版本一致", "存在 " + versions + " 种口径",
                            "同客户同批次贡献度统计区间或折算版本不一致(§10.3)");
                    warn++;
                }
            }
            if (warn == 0) {
                addQuality(bundle, "CONTRIBUTION_STAT_CONSISTENCY", null, null, "PASS",
                        "同客户同批次统计区间/折算版本一致", "通过", null);
            }
        } catch (DataAccessException e) {
            // 数仓表/列未就绪:规则空跑通过
            addQuality(bundle, "CONTRIBUTION_STAT_CONSISTENCY", null, null, "PASS",
                    "同客户同批次统计区间/折算版本一致", "数据源未就绪,空跑通过", null);
        }
        return warn;
    }

    /**
     * 贡献度勾稽(§9.2 D13):按客户取最新批次 dw_contribution_metric,
     * TOTAL 行(CONTRIBUTION_AMOUNT 口径)与明细行加总比对——差额=0 PASS;≤容差 WARN;>容差 BLOCK 并写明差额
     * 表/列未就绪或无数据时规则空跑 PASS
     *
     * @return [block数, warn数]
     */
    private int[] checkContributionReconcile(CcrSnapshotBundle bundle, List<CcrSnapshotRecord> records) {
        String expected = "TOTAL行=明细行加总(容差 " + reconcileTolerance + ")";
        List<String> customerIds = records.stream()
                .filter(r -> "dw_contribution_metric".equals(r.getDatasetCode()))
                .map(CcrSnapshotRecord::getSubjectId)
                .distinct().sorted().toList();
        if (customerIds.isEmpty()) {
            addQuality(bundle, "CONTRIBUTION_RECONCILE", null, null, "PASS",
                    expected, "无贡献度快照记录,空跑通过", null);
            return new int[]{0, 0};
        }
        int block = 0;
        int warn = 0;
        boolean anyChecked = false;
        try {
            for (String custNo : customerIds) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT metric_code, metric_value FROM dw_contribution_metric
                        WHERE cust_no = ?
                          AND data_dt = (SELECT MAX(data_dt) FROM dw_contribution_metric WHERE cust_no = ?)
                        """, custNo, custNo);
                java.math.BigDecimal total = null;
                java.math.BigDecimal detailSum = java.math.BigDecimal.ZERO;
                boolean hasDetail = false;
                for (Map<String, Object> row : rows) {
                    java.math.BigDecimal value = toBigDecimal(row.get("metric_value"));
                    if (value == null) {
                        continue;
                    }
                    if ("TOTAL".equals(String.valueOf(row.get("metric_code")))) {
                        total = value;
                    } else {
                        detailSum = detailSum.add(value);
                        hasDetail = true;
                    }
                }
                if (total == null || !hasDetail) {
                    continue; // 无 TOTAL 行或无明细行:无数据空跑
                }
                anyChecked = true;
                java.math.BigDecimal diff = total.subtract(detailSum).abs();
                if (diff.compareTo(java.math.BigDecimal.ZERO) == 0) {
                    addQuality(bundle, "CONTRIBUTION_RECONCILE", "CUSTOMER", custNo, "PASS",
                            expected, "差额 0", null);
                } else if (diff.compareTo(reconcileTolerance) <= 0) {
                    addQuality(bundle, "CONTRIBUTION_RECONCILE", "CUSTOMER", custNo, "WARN",
                            expected, "差额 " + diff,
                            "贡献度勾稽差额在容差内(TOTAL=" + total + ",明细合计=" + detailSum + ")");
                    warn++;
                } else {
                    addQuality(bundle, "CONTRIBUTION_RECONCILE", "CUSTOMER", custNo, "BLOCK",
                            expected, "差额 " + diff,
                            "贡献度勾稽不符:TOTAL=" + total + ",明细合计=" + detailSum
                                    + ",差额 " + diff + " 超过容差 " + reconcileTolerance + "(§9.2 D13)");
                    block++;
                }
            }
            if (!anyChecked) {
                addQuality(bundle, "CONTRIBUTION_RECONCILE", null, null, "PASS",
                        expected, "无贡献度数据,空跑通过", null);
            }
        } catch (DataAccessException e) {
            // 数仓表/列未就绪:规则空跑通过
            addQuality(bundle, "CONTRIBUTION_RECONCILE", null, null, "PASS",
                    expected, "数据源未就绪,空跑通过", null);
            return new int[]{0, 0};
        }
        return new int[]{block, warn};
    }

    private static java.math.BigDecimal toBigDecimal(Object v) {
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(v.toString());
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * 集团成员有效性:GROUP_TO_MEMBER 关系的成员快照缺失或失效(record_status/valid_to,§10.2)判 WARN
     * 无集团关系时规则空跑通过
     */
    private int checkGroupMemberValid(CcrSnapshotBundle bundle, List<CcrSnapshotRecord> records) {
        List<CcrSnapshotRelation> relations = relationMapper.selectList(
                new LambdaQueryWrapper<CcrSnapshotRelation>()
                        .eq(CcrSnapshotRelation::getBundleId, bundle.getId())
                        .eq(CcrSnapshotRelation::getRelationType, "GROUP_TO_MEMBER"));
        if (relations.isEmpty()) {
            addQuality(bundle, "GROUP_MEMBER_VALID", null, null, "PASS",
                    "集团成员快照有效", "无集团成员关系,空跑通过", null);
            return 0;
        }
        Map<Long, CcrSnapshotRecord> recordMap = records.stream()
                .collect(Collectors.toMap(CcrSnapshotRecord::getId, r -> r, (a, b) -> a));
        int warn = 0;
        List<Long> checked = new ArrayList<>();
        for (CcrSnapshotRelation rel : relations) {
            Long memberRecordId = rel.getChildRecordId();
            if (checked.contains(memberRecordId)) {
                continue;
            }
            checked.add(memberRecordId);
            CcrSnapshotRecord member = recordMap.get(memberRecordId);
            if (member == null) {
                addQuality(bundle, "GROUP_MEMBER_VALID", "MEMBER", String.valueOf(memberRecordId), "WARN",
                        "集团成员快照有效", "成员快照缺失", "GROUP_TO_MEMBER 关系指向的成员记录不在快照包内");
                warn++;
                continue;
            }
            String invalidReason = memberInvalidReason(member.getCoreJson());
            if (invalidReason != null) {
                addQuality(bundle, "GROUP_MEMBER_VALID", member.getSubjectType(), member.getSubjectId(), "WARN",
                        "集团成员快照有效", invalidReason, "集团成员在成员快照中已失效");
                warn++;
            }
        }
        if (warn == 0) {
            addQuality(bundle, "GROUP_MEMBER_VALID", null, null, "PASS", "集团成员快照有效", "通过", null);
        }
        return warn;
    }

    /**
     * 成员快照失效判定:record_status 为 INACTIVE/DELETED,或 valid_to 早于当前日期(§10.2 统一字段)
     */
    private String memberInvalidReason(Map<String, Object> coreJson) {
        if (coreJson == null) {
            return null;
        }
        Object recordStatus = coreJson.get("record_status");
        if (recordStatus != null && INVALID_RECORD_STATUS.contains(String.valueOf(recordStatus))) {
            return "record_status=" + recordStatus;
        }
        Object validTo = coreJson.get("valid_to");
        if (validTo != null && StrUtil.isNotBlank(String.valueOf(validTo))) {
            try {
                LocalDate validToDate = LocalDate.parse(String.valueOf(validTo).substring(0, 10));
                if (validToDate.isBefore(LocalDate.now())) {
                    return "valid_to=" + validToDate;
                }
            } catch (Exception ignored) {
                // valid_to 格式异常不做失效判定
            }
        }
        return null;
    }

    private void addQuality(CcrSnapshotBundle bundle, String ruleCode, String subjectType, String subjectId,
                            String level, String expected, String actual, String message) {
        CcrSnapshotQualityResult q = new CcrSnapshotQualityResult();
        q.setBundleId(bundle.getId());
        q.setApplicationId(bundle.getApplicationId());
        q.setRuleCode(ruleCode);
        q.setSubjectType(subjectType);
        q.setSubjectId(subjectId);
        q.setRuleLevel(level);
        q.setExpectedValue(expected);
        q.setActualValue(actual);
        q.setMessage(message);
        q.setCheckedTime(LocalDateTime.now());
        qualityMapper.insert(q);
    }
}
