package com.ccr.application.dto;

import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrApplicationCommitment;
import com.ccr.application.domain.CcrApplicationMember;
import com.ccr.application.domain.CcrGuaranteeMeasure;
import com.ccr.application.domain.CcrGuaranteePackage;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.domain.CcrPricingItemContractRel;
import com.ccr.application.domain.CcrPricingItemDepositRel;
import lombok.Data;

import java.util.List;

/**
 * 申请详情聚合(§13.1 GET /ccr/applications/{id}:主单+成员+分项+合同/账户关系+担保组合+承诺)
 */
@Data
public class ApplicationDetailResponse {

    /** 主单 */
    private CcrApplication application;

    /** 集团涉及成员 */
    private List<CcrApplicationMember> members;

    /** 定价分项 */
    private List<CcrPricingItem> pricingItems;

    /** 分项与贷款合同关系 */
    private List<CcrPricingItemContractRel> contractRelations;

    /** 分项与存款账户关系 */
    private List<CcrPricingItemDepositRel> depositRelations;

    /** 担保组合(含措施) */
    private List<GuaranteePackageDetail> guaranteePackages;

    /** 拟达成贡献度承诺 */
    private List<CcrApplicationCommitment> commitments;

    /**
     * 担保组合及其措施
     */
    @Data
    public static class GuaranteePackageDetail {

        /** 担保组合 */
        private CcrGuaranteePackage guaranteePackage;

        /** 担保措施 */
        private List<CcrGuaranteeMeasure> measures;
    }
}
