package com.ccr.approval.service;

import com.ccr.application.domain.CcrPricingItem;
import com.ccr.approval.dto.ApprovalResult;

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
     * 普通节点通过(可调价)
     *
     * @param pricingItemId  定价分项
     * @param nodeCode       当前节点(必须等于分项当前节点且登录人具备该节点角色)
     * @param adjustRate     触发分项调价后利率(可为空=不调价;调价不得突破本节点权限边界与产品硬边界)
     * @param comment        意见
     * @param versionNo      分项乐观锁版本号(必传,防并发覆盖)
     * @param idempotencyKey 幂等键(可空,重复抛 IDEMPOTENCY_REPEAT)
     * @param rateAdjustments 同申请其余分项(随整单推进的 sibling)调价利率:分项id→调整后利率,
     *                        仅收录相对当前利率有变化的分项;调价分项按新利率重算矩阵路由并按新链路推进
     * @return 流转去向结果(terminal 终审结束 / nextNodeCode 下一节点,供前端提交成功提示)
     */
    ApprovalResult approve(Long pricingItemId, String nodeCode, BigDecimal adjustRate, String comment,
                 Integer versionNo, String idempotencyKey, Map<Long, BigDecimal> rateAdjustments);

    /**
     * 普通节点否决(§7.3 否决原因必填)
     */
    void reject(Long pricingItemId, String nodeCode, String comment, Integer versionNo, String idempotencyKey);

    /**
     * 已办列表(§11.4):当前登录人办理过的任务(审批动作轨迹,含计票/行长决策留痕)
     */
    List<Map<String, Object>> listDone();

    /**
     * 历史审批分页(§13.2/§14.4):客户经理看本人申请、审批人看本人审批过、行长/审计看全部
     *
     * @return {total, records}
     */
    Map<String, Object> pageHistory(int pageNum, int pageSize);

    /**
     * 申请审批档案(§14.4):申请+成员+分项+快照包与质量结果+审批轨迹+调价记录+
     * 表决汇总(不含票据明细)+行长决议+决议执行核验+承诺计划
     */
    Map<String, Object> historyDetail(Long applicationId);
}
