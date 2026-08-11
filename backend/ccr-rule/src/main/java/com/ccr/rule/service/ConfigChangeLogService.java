package com.ccr.rule.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONUtil;
import com.ccr.rule.domain.CcrConfigChangeLog;
import com.ccr.rule.mapper.CcrConfigChangeLogMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 配置变更审计日志服务(§8A.2)
 * LPR/权限矩阵/利率规则集/产品边界四类配置的 create/submit/publish/disable/reject
 * 统一写变更日志(old/new 值 JSON + 操作人 + 时间 + 版本),供审计查询
 */
@Service
public class ConfigChangeLogService {

    /** 配置域:LPR 版本 */
    public static final String TYPE_LPR = "LPR";

    /** 配置域:权限矩阵 */
    public static final String TYPE_MATRIX = "MATRIX";

    /** 配置域:利率规则集 */
    public static final String TYPE_RULE_SET = "RULE_SET";

    /** 配置域:产品硬边界 */
    public static final String TYPE_PRODUCT_LIMIT = "PRODUCT_LIMIT";

    /** 配置域:产品目录 */
    public static final String TYPE_PRODUCT = "PRODUCT";

    /** 配置域:产品审批链路 */
    public static final String TYPE_PRODUCT_ROUTE = "PRODUCT_ROUTE";

    /** 动作:新增草稿 */
    public static final String ACTION_CREATE = "CREATE";

    /** 动作:送审 */
    public static final String ACTION_SUBMIT = "SUBMIT";

    /** 动作:复核发布 */
    public static final String ACTION_PUBLISH = "PUBLISH";

    /** 动作:停用 */
    public static final String ACTION_DISABLE = "DISABLE";

    /** 动作:复核驳回 */
    public static final String ACTION_REJECT = "REJECT";

    @Resource
    private CcrConfigChangeLogMapper changeLogMapper;

    /**
     * 写一条配置变更日志
     *
     * @param configType 配置域({@link #TYPE_LPR} 等)
     * @param configId   配置记录主键
     * @param versionNo  配置记录版本号
     * @param action     动作({@link #ACTION_CREATE} 等)
     * @param oldObj     变更前实体快照(新增传 null)
     * @param newObj     变更后实体快照
     * @param opinion    复核/驳回意见(可空,驳回必填由调用方校验)
     */
    public void record(String configType, Long configId, Integer versionNo, String action,
                       Object oldObj, Object newObj, String opinion) {
        recordJson(configType, configId, versionNo, action,
                oldObj == null ? null : JSONUtil.toJsonStr(oldObj),
                newObj == null ? null : JSONUtil.toJsonStr(newObj), opinion);
    }

    /**
     * 写一条配置变更日志(调用方自行序列化快照,适用于"先取旧快照再原位修改"的场景)
     *
     * @param configType 配置域({@link #TYPE_LPR} 等)
     * @param configId   配置记录主键
     * @param versionNo  配置记录版本号
     * @param action     动作({@link #ACTION_CREATE} 等)
     * @param oldJson    变更前快照 JSON(新增传 null)
     * @param newJson    变更后快照 JSON
     * @param opinion    复核/驳回意见(可空,驳回必填由调用方校验)
     */
    public void recordJson(String configType, Long configId, Integer versionNo, String action,
                           String oldJson, String newJson, String opinion) {
        CcrConfigChangeLog log = new CcrConfigChangeLog();
        log.setConfigType(configType);
        log.setConfigId(configId);
        log.setAction(action);
        log.setOldJson(oldJson);
        log.setNewJson(newJson);
        log.setOpinion(opinion);
        log.setOperatorId(currentUserId());
        log.setOperateTime(LocalDateTime.now());
        // version_no 记录配置版本号(insertFill 仅在为空时兜底 1)
        log.setVersionNo(versionNo == null ? 1 : versionNo);
        changeLogMapper.insert(log);
    }

    /** 当前登录用户(未登录兜底 0,与 MybatisPlusConfig 填充口径一致) */
    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
