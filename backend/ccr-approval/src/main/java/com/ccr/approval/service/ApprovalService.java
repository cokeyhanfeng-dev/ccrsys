package com.ccr.approval.service;

import com.ccr.application.domain.CcrPricingItem;
import com.ccr.approval.dto.ApprovalResult;
import com.ccr.approval.dto.AutoBackfillResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 普通节点审批服务(§7.2/§7.3)
 * 身份口径:操作人取 Sa-Token 登录人,不接受 operatorId 传参;
 * 六人小组节点不走普通审批通道(上送小组自动合批表决);存款分项仅支行行长过手后直接上会。
 * 通过可携带调价利率;权限内→终审,超权限→保留利率自动上送;否决→分项否决。
 */
public interface ApprovalService {

    /**
     * 待办列表:按登录人角色映射审批节点过滤(不再接收 nodeCode/operatorId 传参);
     * admin 看全部审批中分项,无节点角色(如客户经理)返回空
     */
    List<CcrPricingItem> listTodo();

    /**
     * 普通节点通过(可调价;整单交付改造 2026-08-29:按申请整单审批,一次动作即整单推进/终审)
     *
     * @param applicationId  申请主键
     * @param nodeCode       当前节点(必须等于申请当前节点且登录人具备该节点角色)
     * @param adjustRate     整单统一调价后利率(可为空=不调价;应用到全部在途分项,不得突破本节点权限边界与产品硬边界)
     * @param comment        意见
     * @param versionNo      兼容参数(整单化后防重复靠节点动作守卫,不强制乐观锁)
     * @param idempotencyKey 幂等键(可空,重复抛 IDEMPOTENCY_REPEAT)
     * @param rateAdjustments 兼容逐分项调价:分项id→调整后利率(adjustRate 为空时生效;整单化后建议用整单统一利率)
     * @return 流转去向结果(terminal 整单终审结束 / nextNodeCode 下一节点,供前端提交成功提示)
     */
    ApprovalResult approve(Long applicationId, String nodeCode, BigDecimal adjustRate, String comment,
                 Integer versionNo, String idempotencyKey, Map<Long, BigDecimal> rateAdjustments);

    /**
     * 普通节点否决(§7.3 否决原因必填;整单交付改造 2026-08-29:任一节点一次否决即整单否决)
     *
     * @param applicationId  申请主键
     * @param nodeCode       当前节点(必须等于申请当前节点且登录人具备该节点角色)
     * @param comment        否决原因(必填)
     * @param versionNo      兼容参数(整单化后防重复靠节点动作守卫,不强制乐观锁)
     * @param idempotencyKey 幂等键(可空,重复抛 IDEMPOTENCY_REPEAT)
     * @return 流转去向结果(terminal 整单否决流程结束),供前端返回列表
     */
    ApprovalResult reject(Long applicationId, String nodeCode, String comment, Integer versionNo, String idempotencyKey);

    /**
     * 已办列表(§11.4):当前登录人办理过的任务(审批动作轨迹,含计票/行长决策留痕)
     */
    List<Map<String, Object>> listDone();

    /**
     * 历史审批分页(§13.2/§14.4):客户经理看本人申请、审批人看本人审批过、行长/审计看全部
     *
     * @param applicationNo 申请号模糊(可空)
     * @param status        状态筛选,逗号分隔多状态(可空;工作台「审批中/否决」聚合跳转)
     * @param keyword       客户/集团名称模糊(可空,匹配 JSON 快照)
     * @return {total, records}
     */
    Map<String, Object> pageHistory(int pageNum, int pageSize, String applicationNo, String status, String keyword);

    /**
     * 申请审批档案(§14.4):申请+成员+分项+快照包与质量结果+审批轨迹+调价记录+
     * 表决汇总(不含票据明细)+行长决议+决议执行核验+承诺计划
     */
    Map<String, Object> historyDetail(Long applicationId);

    /** 授信协议历史审批申请(§2026-09-01 存量授信展示:按 credit_info_json.agreementNo 查同协议历史申请) */
    List<Map<String, Object>> agreementHistory(String agreementNo);

    /**
     * 审批中客户号回填(2026-08-20 #017):新增客户提交时数仓未收录 → 占位号(NEW+证件后6位);
     * 审批中数仓已收录后,由申请人/审批人回填真实客户号。仅占用位号的分项可回填,
     * 回填同步 ccr_application.customer_no + ccr_pricing_item.pricing_customer_no +
     * customer_info_json.customerNo + 已冻结快照记录(subject_id / core_json.cust_no)。
     *
     * @param pricingItemId 定价分项
     * @param customerNo    真实客户号(与 certNo 二选一,优先)
     * @param certNo        证件号(按证件号反查数仓真实客户号)
     */
    void backfillCustomerNo(Long pricingItemId, String customerNo, String certNo);

    /**
     * §2026-09-02 节点进入自动回填(决策二):单户占位申请(主单为空或 NEW 占位)进入审批详情时,
     * 按 customer_info_json 证件号反查数仓主档,命中即整单占位→真实并级联(主单 customer_no、
     * 分项 pricing_customer_no、快照 subject_id/core_json.cust_no、ccr_relation 绑定主体、
     * ccr_application_related_person.related_customer_no);未命中不写库、不阻塞流程。
     * 幂等:主单已是真实号直接返回。
     *
     * @param applicationId 申请主键
     * @return 自动回填结果(applicable/backfilled/customerNo)
     */
    AutoBackfillResult autoBackfillCustomerNo(Long applicationId);
}
