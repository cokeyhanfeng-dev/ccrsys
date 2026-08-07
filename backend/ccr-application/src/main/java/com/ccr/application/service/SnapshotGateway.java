package com.ccr.application.service;

import com.ccr.application.dto.SnapshotBundleResult;
import com.ccr.application.dto.SnapshotRecordInput;
import com.ccr.application.dto.SnapshotRelationInput;

import java.util.List;

/**
 * 快照端口(提交编排采集/冻结快照的唯一出口)
 * 说明:ccr-snapshot 已依赖 ccr-application(SnapshotServiceImpl 使用 CcrApplicationMapper),
 * 本模块反向依赖会形成 Maven 循环依赖,故定义本端口,由启动模块(ccr-admin)适配器
 * 桥接到 ccr-snapshot 的 SnapshotService(注入对方已存在的 Service 接口)。
 */
public interface SnapshotGateway {

    /**
     * 创建快照包(状态 FREEZING)
     *
     * @return 快照包主键
     */
    Long createBundle(Long applicationId);

    /**
     * 添加快照记录
     *
     * @return 快照记录主键(用于关系登记与合同/账户关系回填)
     */
    Long addRecord(Long bundleId, SnapshotRecordInput record);

    /**
     * 批量登记快照关系(集团→成员→额度→分项→合同→借据,§A.6)
     */
    void addRelations(Long bundleId, List<SnapshotRelationInput> relations);

    /**
     * 数据质量校验(§10.5)
     *
     * @return PASS/WARN/BLOCK
     */
    String validate(Long bundleId);

    /**
     * 冻结快照包并绑定到申请;质量 BLOCK 抛 QUALITY_BLOCK 回滚(§7.1 步骤11)
     */
    SnapshotBundleResult freeze(Long bundleId);
}
