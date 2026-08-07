package com.ccr.application.service;

import com.ccr.application.domain.CcrApplication;
import com.ccr.application.dto.RoutePreviewResponse;
import com.ccr.application.dto.SubmitCheckResponse;
import com.ccr.application.dto.SubmitResponse;

/**
 * 申请提交编排(§7.1 步骤7-11、§7.6 关联重提、§13.1 路由预览/提交校验)
 * 分工边界:本服务只负责把分项置 ROUTING + 首节点 BRANCH_MANAGER;
 * 进入 ROUTING 后的审批/表决/决议/承诺由审批域负责
 */
public interface ApplicationSubmitService {

    /**
     * 路由预览:对申请每个定价分项计算路由(集团场景金额定档取集团批复总额度,§B18)
     */
    RoutePreviewResponse routePreview(Long id);

    /**
     * 提交前校验:数仓最新批次与基线比对差异 + 质量预校验 + 硬边界校验(前端据此弹确认)
     */
    SubmitCheckResponse submitCheck(Long id);

    /**
     * 提交:状态守卫→完整性/额度/一合同一有效分项/硬边界校验→快照采集冻结→
     * 冻结 LPR/规则/生效日期→逐分项路由置 ROUTING→主单 ROUTING+提交时间。
     * 全部在事务内;幂等:重复提交返回既有结果
     */
    SubmitResponse submit(Long id);

    /**
     * 关联重提(§7.6):基于终态原申请创建新草稿;已批准分项沿用原决议(inheritFlag=Y),
     * 被否决/其他分项重新生成 DRAFT 分项重走路由
     */
    CcrApplication reapply(Long id);
}
