package com.ccr.application.service;

import com.ccr.application.domain.CcrApplication;
import com.ccr.application.dto.ApplicationDetailResponse;

/**
 * 申请域服务(§13.1 PC 申请接口)
 */
public interface CcrApplicationService {

    /**
     * 创建草稿(POST /ccr/applications)
     */
    CcrApplication createDraft(CcrApplication request);

    /**
     * 保存草稿(PUT /ccr/applications/{id};携带 versionNo 做乐观锁校验)
     */
    CcrApplication saveDraft(Long id, CcrApplication request);

    /**
     * 查询申请(GET /ccr/applications/{id})
     */
    CcrApplication getApplication(Long id);

    /**
     * 申请详情聚合(主单+成员+分项+合同/账户关系+担保组合+承诺)
     */
    ApplicationDetailResponse getApplicationDetail(Long id);

    /**
     * 申请列表(本人申请,按机构/状态过滤)
     */
    java.util.List<CcrApplication> listApplications(Long orgId, String status, Long applicantId);
}
