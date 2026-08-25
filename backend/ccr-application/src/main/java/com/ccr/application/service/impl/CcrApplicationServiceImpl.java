package com.ccr.application.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ccr.common.core.util.ContributionMerger;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrApplicationCommitment;
import com.ccr.application.domain.CcrApplicationCreditSummary;
import com.ccr.application.domain.CcrApplicationOtherLoan;
import com.ccr.application.domain.CcrApplicationRelatedPerson;
import com.ccr.application.mapper.CcrApplicationRelatedPersonMapper;
import com.ccr.application.mapper.CcrApplicationOtherLoanMapper;
import com.ccr.application.mapper.CcrApplicationCreditSummaryMapper;
import com.ccr.application.domain.CcrApplicationMember;
import com.ccr.application.domain.CcrGuaranteeMeasure;
import com.ccr.application.domain.CcrGuaranteePackage;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.domain.CcrPricingItemContractRel;
import com.ccr.application.domain.CcrPricingItemDepositRel;
import com.ccr.application.dto.ApplicationDetailResponse;
import com.ccr.application.dto.CommitmentInput;
import com.ccr.application.dto.DepositItemInput;
import com.ccr.application.dto.MemberInput;
import com.ccr.application.enums.ApplicationStatus;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrApplicationCommitmentMapper;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrApplicationMemberMapper;
import com.ccr.application.mapper.CcrGuaranteeMeasureMapper;
import com.ccr.application.mapper.CcrGuaranteePackageMapper;
import com.ccr.application.mapper.CcrPricingItemContractRelMapper;
import com.ccr.application.mapper.CcrPricingItemDepositRelMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.application.service.CcrApplicationService;
import com.ccr.application.service.DataWarehouseService;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.application.read.SysUserRead;
import com.ccr.application.support.AppLoginUser;
import com.ccr.application.support.CustomerNoUtil;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 申请域服务实现
 * 草稿阶段(§12.1 DRAFT)可编辑;创建按 businessType 分流生成贷款/存款定价分项
 */
@Slf4j
@Service
public class CcrApplicationServiceImpl implements CcrApplicationService {

    @Resource
    private CcrApplicationMapper applicationMapper;

    @Resource
    private CcrApplicationMemberMapper applicationMemberMapper;

    @Resource
    private CcrPricingItemMapper pricingItemMapper;

    @Resource
    private CcrPricingItemContractRelMapper contractRelMapper;

    @Resource
    private CcrPricingItemDepositRelMapper depositRelMapper;

    @Resource
    private CcrGuaranteePackageMapper guaranteePackageMapper;

    @Resource
    private CcrGuaranteeMeasureMapper guaranteeMeasureMapper;

    @Resource
    private CcrApplicationCommitmentMapper commitmentMapper;
    @Resource
    private CcrApplicationOtherLoanMapper otherLoanMapper;
    @Resource
    private CcrApplicationCreditSummaryMapper creditSummaryMapper;
    @Resource
    private CcrApplicationRelatedPersonMapper relatedPersonMapper;

    @Resource
    private DataWarehouseService dataWarehouseService;

    @Resource
    private AppLoginUser appLoginUser;

    @Resource
    private ApplicationAccessService applicationAccessService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrApplication createDraft(CcrApplication request) {
        applicationAccessService.requireCustomerManager();
        // customerScope 守卫:非 GROUP 传 members 拒绝,GROUP 缺 groupNo 拒绝
        String businessType = request.getBusinessType();
        if (!"LOAN".equals(businessType) && !"DEPOSIT".equals(businessType)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "业务类型必填且仅支持 LOAN/DEPOSIT");
        }
        String customerScope = request.getCustomerScope();
        boolean groupScope = "GROUP".equals(customerScope);
        if (!groupScope && !"INDIVIDUAL".equals(customerScope) && !"CORPORATE_SINGLE".equals(customerScope)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "客户范围必填且仅支持 INDIVIDUAL/CORPORATE_SINGLE/GROUP");
        }
        if (groupScope && StrUtil.isBlank(request.getGroupNo())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "集团场景集团客户编号(groupNo)必填");
        }
        if (!groupScope && request.getMembers() != null && !request.getMembers().isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "非集团场景不允许传涉及成员(members)");
        }

        CcrApplication entity = new CcrApplication();
        copyForCreate(entity, request);
        // 申请人/机构来自登录上下文(§5.4:不信任前端传参,防越权;applicant_user_id/org_id 均 NOT NULL)
        SysUserRead loginUser = appLoginUser.requireCurrentUser();
        entity.setApplicantUserId(loginUser.getId());
        entity.setApplicantOrgId(loginUser.getOrgId());
        // 按申请人机构解析所属支行编码(§5.4 数据权限 DEPT 级前缀过滤数据基础,不信任前端传参)
        entity.setApplyBranchCode(resolveBranchCode(loginUser.getOrgId()));
        // 生成申请号:CCR + yyyyMMdd + 4位随机
        entity.setApplicationNo("CCR" + cn.hutool.core.date.DateUtil.format(new java.util.Date(), "yyyyMMdd")
                + IdUtil.fastSimpleUUID().substring(0, 4).toUpperCase());
        entity.setStatus(ApplicationStatus.DRAFT.getCode());
        // 数据日期基线(§7.1 步骤9:提交时与最新成功批次比对)
        entity.setDataBaselineJson(buildBaselineJson(businessType, customerScope));
        if (entity.getVersionNo() == null) {
            entity.setVersionNo(1); // 与 DB DEFAULT 一致,保证返回体携带版本号供后续 PUT 乐观锁
        }
        applicationMapper.insert(entity);

        // 集团场景:写入涉及成员(逐成员真实金额/币种/角色,成员额度从数仓回填)
        saveMembers(entity, request.getMembers(), groupScope);

        // 创建定价分项:贷款按担保切分,存款按结构化存款字段
        List<CcrPricingItem> createdItems = createItemsByBusinessType(entity, request, businessType, groupScope);

        // 拟达成贡献度承诺(供审批通过后生成正式承诺计划读取)
        saveCommitments(entity.getId(), request.getCommitments(), createdItems);
        // 人工补录他行融资(§7.1 步骤6,审批详情随申请展示)
        saveOtherLoans(entity.getId(), request.getOtherLoans());
        // 他行融资概要(数仓带出可编辑快照,§2026-08-25)
        saveCreditSummary(entity.getId(), request.getCreditSummary());
        // 关联人(§12.4④,按客户经理实际录入保存并展示)
        saveRelatedPersons(entity.getId(), request.getRelatedPersons());
        return entity;
    }

    /**
     * 按申请人机构解析所属支行编码(§5.4 数据权限 DEPT 级前缀过滤数据基础):
     * ccr_sys_dept.id → branch_code;支行/网点有 branch_code,
     * 总行部门为 NULL(打标 NULL,前缀过滤对该类申请不命中);解析失败不阻断创建
     */
    private String resolveBranchCode(Long orgId) {
        if (orgId == null) {
            return null;
        }
        try {
            List<String> codes = jdbcTemplate.queryForList(
                    "SELECT branch_code FROM ccr_sys_dept "
                            + "WHERE id = ? AND del_flag = '0' AND branch_code IS NOT NULL",
                    String.class, orgId);
            return codes.isEmpty() ? null : codes.get(0);
        } catch (DataAccessException e) {
            log.warn("解析申请支行编码失败,orgId={}: {}", orgId, e.getMessage());
            return null;
        }
    }

    /** 成员/分项/承诺子表写入(createDraft 与 saveDraft 共用) */
    private void saveMembers(CcrApplication entity, List<MemberInput> members, boolean groupScope) {
        if (members == null) {
            return;
        }
        Map<String, Object> groupCredit = groupScope ? loadGroupCredit(entity.getGroupNo()) : null;
        for (MemberInput m : members) {
            if (m == null || StrUtil.isBlank(m.getMemberCustomerNo())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "涉及成员客户号必填");
            }
            if (m.getRequestAmount() == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "成员[" + m.getMemberCustomerNo() + "]本次申请金额必填");
            }
            CcrApplicationMember member = new CcrApplicationMember();
            member.setApplicationId(entity.getId());
            member.setMemberCustomerNo(m.getMemberCustomerNo());
            member.setRequestAmount(m.getRequestAmount());
            member.setCurrency(StrUtil.blankToDefault(m.getCurrency(), "CNY"));
            member.setMemberRole(resolveMemberRole(entity.getGroupNo(), m));
            // 成员额度从 dw_member_credit_limit_snapshot 回填
            if (groupCredit != null) {
                Map<String, Object> limit = loadMemberLimit(
                        String.valueOf(groupCredit.get("group_credit_no")), m.getMemberCustomerNo());
                if (limit != null) {
                    member.setMemberLimitRef(String.valueOf(limit.get("member_limit_no")));
                    member.setMemberLimitAmount(toBigDecimal(limit.get("allocated_amount")));
                }
            }
            applicationMemberMapper.insert(member);
        }
    }

    /** 按业务类型分流创建定价分项(贷款按担保切分,存款按结构化存款字段) */
    private List<CcrPricingItem> createItemsByBusinessType(CcrApplication entity, CcrApplication request,
                                                           String businessType, boolean groupScope) {
        if ("LOAN".equals(businessType)) {
            return createLoanItems(entity, request, groupScope);
        }
        if ("DEPOSIT".equals(businessType)) {
            return createDepositItems(entity, request, groupScope);
        }
        return new ArrayList<>();
    }

    // ---------- createDraft 私有 ----------

    /** 贷款分项:按担保切分(每条 guarantee 一个分项),产品码/期限/币种从请求取值 */
    private List<CcrPricingItem> createLoanItems(CcrApplication entity, CcrApplication request, boolean groupScope) {
        List<CcrPricingItem> created = new ArrayList<>();
        if (request.getGuarantees() == null) {
            return created;
        }
        int index = 0;
        for (Map<String, Object> g : request.getGuarantees()) {
            index++;
            if (g == null) {
                continue;
            }
            BigDecimal requestedRate = toBigDecimal(g.get("requestedRate"));
            String productCode = strVal(g.get("productCode"));
            Integer termValue = toInteger(g.get("termValue"));
            String termUnit = strVal(g.get("termUnit"));
            if (requestedRate == null || StrUtil.isBlank(productCode) || termValue == null || StrUtil.isBlank(termUnit)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "第" + index + "条担保分项缺少必填项(requestedRate/productCode/termValue/termUnit)");
            }
            // 申请利率范围兜底(§bug 2026-08-25):落库列 DECIMAL(9,6) 整数上限 999,超范围报 MySQL out of range 晦涩错误;
            // 此处用 0~100 合理利率范围,报清晰中文错误替代
            if (requestedRate.compareTo(BigDecimal.ZERO) <= 0 || requestedRate.compareTo(new BigDecimal("100")) > 0) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "第" + index + "条担保分项申请利率须在 0~100 之间(当前 " + requestedRate + ")");
            }
            BigDecimal pricingAmount = toBigDecimal(g.get("amount"));
            if (pricingAmount == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "第" + index + "条担保分项金额(amount)必填");
            }
            String memberCustomerNo = strVal(g.get("memberCustomerNo"));
            CcrPricingItem pi = newPricingItem(entity, groupScope, memberCustomerNo);
            pi.setPricingCarrierType("LOAN_CONTRACT");
            pi.setProductCode(productCode);
            pi.setTermValue(termValue);
            pi.setTermUnit(termUnit);
            pi.setPricingAmount(pricingAmount);
            pi.setCurrency(StrUtil.blankToDefault(strVal(g.get("currency")), "CNY"));
            pi.setOriginalRate(toBigDecimal(g.get("originalRate")));
            pi.setRequestedRate(requestedRate);
            pi.setCurrentApprovalRate(requestedRate);
            pi.setRateDirection("LOWER_BETTER");
            pi.setSourceSplitNo(strVal(g.get("sourceSplitNo")));
            pricingItemMapper.insert(pi);
            created.add(pi);

            // 需求②:新增/存量统一纯拆分项——不建合同关系(ccr_pricing_item_contract_rel)、不回填/补全合同号;
            // 存量调息来源拆分项编号随 pi 落库(source_split_no 溯源/防重),新增为空
            // 担保组合(一分项一组合,pricing_item_id 唯一)+ 担保措施
            String guaranteeType = strVal(g.get("guaranteeType"));
            if (StrUtil.isNotBlank(guaranteeType)) {
                CcrGuaranteePackage pkg = new CcrGuaranteePackage();
                pkg.setPackageNo("GP-" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
                pkg.setPricingItemId(pi.getId());
                pkg.setPackageVersion(1);
                pkg.setMainGuaranteeType(guaranteeType);
                @SuppressWarnings("unchecked")
                Map<String, Object> ext = g.get("extJson") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                pkg.setExtJson(ext);
                guaranteePackageMapper.insert(pkg);
                pi.setGuaranteePackageId(pkg.getId());
                pricingItemMapper.updateById(pi);
                saveGuaranteeMeasures(pkg.getId(), g.get("measures"));
            }
        }
        return created;
    }

    /** 存款分项:从结构化存款字段生成(修复"存款申请 0 分项",不再依赖 guarantees 列表) */
    private List<CcrPricingItem> createDepositItems(CcrApplication entity, CcrApplication request, boolean groupScope) {
        List<CcrPricingItem> created = new ArrayList<>();
        if (request.getDepositItems() == null) {
            return created;
        }
        int index = 0;
        for (DepositItemInput d : request.getDepositItems()) {
            index++;
            if (d == null) {
                continue;
            }
            if (d.getRequestedRate() == null || StrUtil.isBlank(d.getProductCode())
                    || d.getTermValue() == null || StrUtil.isBlank(d.getTermUnit())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "第" + index + "条存款分项缺少必填项(requestedRate/productCode/termValue/termUnit)");
            }
            // 申请利率范围兜底(同贷款分项,§bug 2026-08-25)
            if (d.getRequestedRate().compareTo(BigDecimal.ZERO) <= 0
                    || d.getRequestedRate().compareTo(new BigDecimal("100")) > 0) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "第" + index + "条存款分项申请利率须在 0~100 之间(当前 " + d.getRequestedRate() + ")");
            }
            boolean planned = "Y".equals(d.getPlannedAccountFlag()) || StrUtil.isBlank(d.getDepositAccountNo());
            if (d.getAmount() == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "第" + index + "条存款分项金额(amount)必填");
            }
            CcrPricingItem pi = newPricingItem(entity, groupScope, d.getMemberCustomerNo());
            pi.setPricingCarrierType("DEPOSIT_ACCOUNT");
            pi.setProductCode(d.getProductCode());
            pi.setTermValue(d.getTermValue());
            pi.setTermUnit(d.getTermUnit());
            pi.setPricingAmount(d.getAmount());
            pi.setCurrency(StrUtil.blankToDefault(d.getCurrency(), "CNY"));
            pi.setOriginalRate(d.getOriginalRate());
            pi.setRequestedRate(d.getRequestedRate());
            pi.setCurrentApprovalRate(d.getRequestedRate());
            pi.setRateDirection("HIGHER_BETTER");
            pricingItemMapper.insert(pi);
            created.add(pi);

            // 分项与存款账户关系(拟开户账号可空)
            CcrPricingItemDepositRel rel = new CcrPricingItemDepositRel();
            rel.setApplicationId(entity.getId());
            rel.setPricingItemId(pi.getId());
            rel.setPlannedAccountFlag(planned ? "Y" : "N");
            if (StrUtil.isNotBlank(d.getDepositAccountNo())) {
                // 明文账号直接落库(手工补录或选择数仓账户均以明文账号关联)
                rel.setDepositAccountNo(d.getDepositAccountNo().trim());
            }
            depositRelMapper.insert(rel);
        }
        return created;
    }

    /** 分项公共字段;集团场景必须取真实成员客户号(禁止字面量占位) */
    private CcrPricingItem newPricingItem(CcrApplication entity, boolean groupScope, String memberCustomerNo) {
        if (groupScope && StrUtil.isBlank(memberCustomerNo)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "集团场景定价分项必须指定成员客户号(memberCustomerNo)");
        }
        String pricingCustomerNo;
        if (groupScope) {
            pricingCustomerNo = memberCustomerNo;
        } else {
            pricingCustomerNo = entity.getCustomerNo();
            // 新增客户无客户号:按证件号生成占位号兜底 NOT NULL(pricing_customer_no),提交时反查数仓回填真实号(2026-08-20 #017)
            if (StrUtil.isBlank(pricingCustomerNo)) {
                String certNo = CustomerNoUtil.certNoFromInfoJson(entity.getCustomerInfoJson(), entity.getCustomerScope());
                pricingCustomerNo = CustomerNoUtil.placeholderCustomerNo(certNo);
                if (StrUtil.isBlank(pricingCustomerNo)) {
                    throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "单户场景创建分项前必须填写客户号或证件号(customerNo/certNo)");
                }
            }
        }
        CcrPricingItem pi = new CcrPricingItem();
        pi.setApplicationId(entity.getId());
        pi.setPricingItemNo("PI-" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        pi.setPricingCustomerNo(pricingCustomerNo);
        pi.setMemberCustomerNo(groupScope ? memberCustomerNo : null);
        pi.setStatus(PricingItemStatus.DRAFT.getCode());
        pi.setInheritFlag("N");
        return pi;
    }

    private void saveGuaranteeMeasures(Long packageId, Object measures) {
        if (!(measures instanceof List<?> list)) {
            return;
        }
        int seq = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            seq++;
            CcrGuaranteeMeasure measure = new CcrGuaranteeMeasure();
            measure.setPackageId(packageId);
            measure.setMeasureNo("GM-" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
            measure.setMeasureType(StrUtil.blankToDefault(strVal(m.get("measureType")), "CREDIT"));
            measure.setGuarantorCustomerNo(strVal(m.get("guarantorCustomerNo")));
            measure.setCollateralNo(strVal(m.get("collateralNo")));
            BigDecimal amount = toBigDecimal(m.get("guaranteeAmount"));
            measure.setGuaranteeAmount(amount == null ? BigDecimal.ZERO : amount);
            measure.setCurrency(StrUtil.blankToDefault(strVal(m.get("currency")), "CNY"));
            @SuppressWarnings("unchecked")
            Map<String, Object> ext = m.get("extJson") instanceof Map<?, ?> e ? (Map<String, Object>) e : null;
            measure.setExtJson(ext);
            guaranteeMeasureMapper.insert(measure);
        }
    }

    /** 承诺落库;pricingItemNo 在本次新建分项中解析为分项主键 */
    /** 关联人落库(空行过滤;随 saveDraft 整表重建) */
    private void saveRelatedPersons(Long applicationId, List<CcrApplicationRelatedPerson> persons) {
        if (persons == null || persons.isEmpty()) {
            return;
        }
        for (CcrApplicationRelatedPerson rp : persons) {
            if (rp == null || StrUtil.isBlank(rp.getPersonName())) {
                continue;
            }
            rp.setId(null);
            rp.setApplicationId(applicationId);
            relatedPersonMapper.insert(rp);
        }
    }

    /** 人工补录他行融资落库(空行过滤;整表重建随 saveDraft 语义) */
    private void saveOtherLoans(Long applicationId, List<CcrApplicationOtherLoan> otherLoans) {
        if (otherLoans == null || otherLoans.isEmpty()) {
            return;
        }
        for (CcrApplicationOtherLoan loan : otherLoans) {
            if (loan == null || StrUtil.isBlank(loan.getLenderName())) {
                continue;
            }
            loan.setId(null);
            loan.setApplicationId(applicationId);
            loan.setInputMode(StrUtil.blankToDefault(loan.getInputMode(), "MANUAL"));
            otherLoanMapper.insert(loan);
        }
    }

    /** 他行融资概要落库(随申请单条快照;数仓带出可编辑,§2026-08-25;整表重建随 saveDraft 语义) */
    private void saveCreditSummary(Long applicationId, List<CcrApplicationCreditSummary> creditSummary) {
        if (creditSummary == null || creditSummary.isEmpty()) {
            return;
        }
        CcrApplicationCreditSummary summary = creditSummary.get(0);
        if (summary == null) {
            return;
        }
        summary.setId(null);
        summary.setApplicationId(applicationId);
        creditSummaryMapper.insert(summary);
    }

    private void saveCommitments(Long applicationId, List<CommitmentInput> commitments, List<CcrPricingItem> createdItems) {
        if (commitments == null) {
            return;
        }
        // 基线后端自动取数回填需主客户号(集团申请 customer_no 为空;成员级按成员客户号取数)
        String customerNo = null;
        List<Map<String, Object>> appRows = jdbcTemplate.queryForList(
                "SELECT customer_no customerNo FROM ccr_application WHERE id = ?", applicationId);
        if (!appRows.isEmpty() && appRows.get(0).get("customerNo") != null) {
            customerNo = appRows.get(0).get("customerNo").toString();
        }
        Map<String, Long> itemNoToId = new HashMap<>();
        for (CcrPricingItem pi : createdItems) {
            itemNoToId.put(pi.getPricingItemNo(), pi.getId());
        }
        for (CommitmentInput c : commitments) {
            if (c == null || StrUtil.isBlank(c.getMetricCode()) || StrUtil.isBlank(c.getTargetType())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "承诺缺少必填项(metricCode/targetType)");
            }
            // 承诺类型"其它"(§6.4):无数值目标(target_value 可空),以 commitment_desc 手工描述为准;
            // 其余类型目标值必填
            boolean isOther = "OTHER".equals(c.getMetricCode());
            if (isOther) {
                if (StrUtil.isBlank(c.getCommitmentDesc())) {
                    throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                            "承诺类型'其它'须录入手工目标描述(commitmentDesc)");
                }
            } else if (c.getTargetValue() == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "承诺缺少必填项(targetValue)");
            }
            // 承诺按申请关联(§十三 13.2-6):分项显式指定 pricingItemNo 时按分项绑定,否则恒为申请级(pricing_item_id 空)
            Long resolvedItemId = null;
            if (StrUtil.isNotBlank(c.getPricingItemNo())) {
                resolvedItemId = itemNoToId.get(c.getPricingItemNo());
            }
            CcrApplicationCommitment commitment = new CcrApplicationCommitment();
            commitment.setApplicationId(applicationId);
            commitment.setPricingItemId(resolvedItemId);
            commitment.setMetricCode(c.getMetricCode());
            commitment.setTargetType(c.getTargetType());
            commitment.setBaselineValue(resolveBaseline(c, customerNo, applicationId));
            commitment.setTargetValue(isOther ? null : c.getTargetValue());
            commitment.setUnit(StrUtil.blankToDefault(c.getUnit(), "WAN_YUAN"));
            commitment.setMetricScope(StrUtil.blankToDefault(c.getMetricScope(), "PUBLIC"));
            commitment.setMemberCustomerNo(c.getMemberCustomerNo());
            commitment.setCommitmentDesc(c.getCommitmentDesc());
            commitment.setEndDate(c.getEndDate());
            commitmentMapper.insert(commitment);
        }
    }

    /**
     * 承诺基线(§基线=申请时点当前值,没有就是空的):前端传入值优先;
     * 为空时后端按主客户(成员级取成员客户号)数仓最近批次回填,并归并关联人同码值(§关联人贡献度归并);
     * 无数据/OTHER 手工承诺保持空。
     */
    private BigDecimal resolveBaseline(CommitmentInput c, String customerNo, Long applicationId) {
        if (c.getBaselineValue() != null) {
            return c.getBaselineValue();
        }
        String scopeCustNo = StrUtil.isNotBlank(c.getMemberCustomerNo()) ? c.getMemberCustomerNo() : customerNo;
        if (StrUtil.isBlank(scopeCustNo) || "OTHER".equals(c.getMetricCode())) {
            return null;
        }
        // 主客户该指标最近批次值(无数据构造空行供归并)
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT metric_value metricValue, value_type valueType FROM dw_contribution_metric"
                        + " WHERE cust_no = ? AND metric_code = ?"
                        + " AND data_dt = (SELECT MAX(d2.data_dt) FROM dw_contribution_metric d2"
                        + "   WHERE d2.cust_no = dw_contribution_metric.cust_no AND d2.metric_code = dw_contribution_metric.metric_code)"
                        + " LIMIT 1", scopeCustNo, c.getMetricCode());
        Map<String, Object> mainRow = rows.isEmpty() ? new HashMap<>() : new HashMap<>(rows.get(0));
        if (mainRow.isEmpty()) {
            mainRow.put("metricCode", c.getMetricCode());
        }
        List<Map<String, Object>> contribution = new ArrayList<>();
        contribution.add(mainRow);
        // 成员级承诺按成员客户号单独取数,不归并关联人;其余归并申请录入关联人同码值
        if (StrUtil.isBlank(c.getMemberCustomerNo()) && applicationId != null) {
            List<Map<String, Object>> relations = jdbcTemplate.queryForList(
                    "SELECT related_customer_no relatedCustomerNo FROM ccr_application_related_person"
                            + " WHERE application_id = ? AND del_flag = '0'", applicationId);
            Set<String> relatedNos = new LinkedHashSet<>();
            for (Map<String, Object> rel : relations) {
                Object no = rel.get("relatedCustomerNo");
                if (no != null && !no.toString().isBlank()) {
                    relatedNos.add(no.toString());
                }
            }
            ContributionMerger.mergeRelatedContributions(jdbcTemplate, contribution, relatedNos);
        }
        Object value = contribution.get(0).get("metricValue");
        return value == null ? null : new BigDecimal(value.toString());
    }

    /** 数据日期基线(数仓不可用时容忍,提交校验按无基线处理) */
    private String buildBaselineJson(String businessType, String customerScope) {
        try {
            return JSONUtil.toJsonStr(dataWarehouseService.latestDataDates(
                    DataWarehouseService.relevantDatasets(businessType, customerScope)));
        } catch (DataAccessException e) {
            return null;
        }
    }

    private Map<String, Object> loadGroupCredit(String groupNo) {
        try {
            return dataWarehouseService.findGroupCredit(groupNo);
        } catch (DataAccessException e) {
            return null;
        }
    }

    private Map<String, Object> loadMemberLimit(String groupCreditNo, String memberCustomerNo) {
        try {
            return dataWarehouseService.findMemberLimit(groupCreditNo, memberCustomerNo);
        } catch (DataAccessException e) {
            return null;
        }
    }

    /** 成员角色:请求优先,缺省按数仓成员快照回填 */
    private String resolveMemberRole(String groupNo, MemberInput m) {
        if (StrUtil.isNotBlank(m.getMemberRole())) {
            return m.getMemberRole();
        }
        try {
            Map<String, Object> row = dataWarehouseService.findGroupMember(groupNo, m.getMemberCustomerNo());
            if (row != null && row.get("member_role") != null) {
                return String.valueOf(row.get("member_role"));
            }
        } catch (DataAccessException ignored) {
            // 数仓不可用时不回填角色
        }
        return null;
    }

    static BigDecimal toBigDecimal(Object v) {
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    static Integer toInteger(Object v) {
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(v.toString()).intValue();
        } catch (Exception e) {
            return null;
        }
    }

    static String strVal(Object v) {
        return v == null ? null : StrUtil.blankToDefault(v.toString(), null);
    }

    // ---------- 保存/查询 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrApplication saveDraft(Long id, CcrApplication request) {
        applicationAccessService.requireOwner(id);
        CcrApplication exist = applicationMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(404, "申请不存在");
        }
        if (!ApplicationStatus.DRAFT.getCode().equals(exist.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "仅草稿状态可编辑");
        }
        // 乐观锁:请求必须携带读取时的版本号,冲突抛 DATA_VERSION_CONFLICT
        if (request.getVersionNo() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "保存草稿必须携带数据版本号(versionNo)");
        }
        if (!request.getVersionNo().equals(exist.getVersionNo())) {
            throw new ServiceException(ErrorCode.DATA_VERSION_CONFLICT.getCode(),
                    "数据已被他人修改,请刷新后重试(版本 " + request.getVersionNo() + "→" + exist.getVersionNo() + ")");
        }
        copyForUpdate(exist, request);
        int updated = applicationMapper.updateById(exist);
        if (updated == 0) {
            throw new ServiceException(ErrorCode.DATA_VERSION_CONFLICT.getCode(), "数据已被他人修改,请刷新后重试");
        }
        // 子表(成员/定价分项及关联/承诺)按请求体全量重建,保证提交内容为最新
        rebuildChildren(exist, request);
        return exist;
    }

    /** 按请求体全量重建子表:先删除旧子表数据,再复用创建逻辑重新插入(pricing_item_no 重新生成) */
    private void rebuildChildren(CcrApplication entity, CcrApplication request) {
        deleteChildren(entity.getId());
        boolean groupScope = "GROUP".equals(entity.getCustomerScope());
        // 成员守卫与 createDraft 一致:非集团不允许 members,集团必须有 groupNo
        if (!groupScope && request.getMembers() != null && !request.getMembers().isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "非集团场景不允许传涉及成员(members)");
        }
        if (groupScope && StrUtil.isBlank(entity.getGroupNo())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "集团场景集团客户编号(groupNo)必填");
        }
        saveMembers(entity, request.getMembers(), groupScope);
        List<CcrPricingItem> createdItems = createItemsByBusinessType(
                entity, request, entity.getBusinessType(), groupScope);
        saveCommitments(entity.getId(), request.getCommitments(), createdItems);
        saveOtherLoans(entity.getId(), request.getOtherLoans());
        saveCreditSummary(entity.getId(), request.getCreditSummary());
        saveRelatedPersons(entity.getId(), request.getRelatedPersons());
    }

    /**
     * 删除申请旧子表数据(承诺/成员/分项及合同关系/账户关系/担保组合/担保措施)
     * inherit_flag='Y' 的沿用分项不在前端编辑载荷中,其分项/载体关系/担保/绑定承诺必须保留(D18b)
     */
    private void deleteChildren(Long applicationId) {
        List<CcrPricingItem> existingItems = pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, applicationId));
        List<Long> inheritedIds = existingItems.stream()
                .filter(i -> "Y".equals(i.getInheritFlag()))
                .map(CcrPricingItem::getId).toList();
        List<Long> removableIds = existingItems.stream()
                .filter(i -> !"Y".equals(i.getInheritFlag()))
                .map(CcrPricingItem::getId).toList();

        otherLoanMapper.delete(new LambdaQueryWrapper<CcrApplicationOtherLoan>()
                .eq(CcrApplicationOtherLoan::getApplicationId, applicationId));
        creditSummaryMapper.delete(new LambdaQueryWrapper<CcrApplicationCreditSummary>()
                .eq(CcrApplicationCreditSummary::getApplicationId, applicationId));
        relatedPersonMapper.delete(new LambdaQueryWrapper<CcrApplicationRelatedPerson>()
                .eq(CcrApplicationRelatedPerson::getApplicationId, applicationId));
        // 成员:物理删除(uk_app_member(application_id, member_customer_no) 不含 del_flag,MP 逻辑删除 del_flag 0→1
        // 时旧行仍占唯一键,重建 INSERT 撞键报 Duplicate entry→「重复提交」;成员随载荷全量重建,物理删除根治)
        applicationMemberMapper.deletePhysical(applicationId);
        if (inheritedIds.isEmpty()) {
            commitmentMapper.delete(new LambdaQueryWrapper<CcrApplicationCommitment>()
                    .eq(CcrApplicationCommitment::getApplicationId, applicationId));
        } else {
            // 承诺:保留绑定沿用分项的记录(申请级承诺 pricing_item_id 为空,随载荷重建)
            commitmentMapper.delete(new LambdaQueryWrapper<CcrApplicationCommitment>()
                    .eq(CcrApplicationCommitment::getApplicationId, applicationId)
                    .and(w -> w.isNull(CcrApplicationCommitment::getPricingItemId)
                            .or().notIn(CcrApplicationCommitment::getPricingItemId, inheritedIds)));
        }
        // 合同/账户关系:物理删除(MP 逻辑删除 del_flag 0→1 会撞含 del_flag 的唯一键 uk_app_contract/uk_app_deposit,草稿重建无历史价值)
        contractRelMapper.deletePhysical(applicationId, inheritedIds);
        depositRelMapper.deletePhysical(applicationId, inheritedIds);
        if (!removableIds.isEmpty()) {
            List<Long> packageIds = guaranteePackageMapper.selectList(new LambdaQueryWrapper<CcrGuaranteePackage>()
                            .in(CcrGuaranteePackage::getPricingItemId, removableIds))
                    .stream().map(CcrGuaranteePackage::getId).toList();
            if (!packageIds.isEmpty()) {
                guaranteeMeasureMapper.delete(new LambdaQueryWrapper<CcrGuaranteeMeasure>()
                        .in(CcrGuaranteeMeasure::getPackageId, packageIds));
            }
            guaranteePackageMapper.delete(new LambdaQueryWrapper<CcrGuaranteePackage>()
                    .in(CcrGuaranteePackage::getPricingItemId, removableIds));
        }
        pricingItemMapper.delete(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, applicationId)
                .ne(CcrPricingItem::getInheritFlag, "Y"));
    }

    @Override
    public CcrApplication getApplication(Long id) {
        applicationAccessService.requireView(id);
        CcrApplication exist = applicationMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(404, "申请不存在");
        }
        return exist;
    }

    @Override
    public ApplicationDetailResponse getApplicationDetail(Long id) {
        ApplicationDetailResponse detail = new ApplicationDetailResponse();
        detail.setApplication(getApplication(id));
        detail.setMembers(applicationMemberMapper.selectList(new LambdaQueryWrapper<CcrApplicationMember>()
                .eq(CcrApplicationMember::getApplicationId, id)
                .orderByAsc(CcrApplicationMember::getId)));
        List<CcrPricingItem> items = pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, id)
                .orderByAsc(CcrPricingItem::getId));
        detail.setPricingItems(items);
        detail.setContractRelations(contractRelMapper.selectList(new LambdaQueryWrapper<CcrPricingItemContractRel>()
                .eq(CcrPricingItemContractRel::getApplicationId, id)
                .orderByAsc(CcrPricingItemContractRel::getId)));
        detail.setDepositRelations(depositRelMapper.selectList(new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                .eq(CcrPricingItemDepositRel::getApplicationId, id)
                .orderByAsc(CcrPricingItemDepositRel::getId)));
        List<Long> itemIds = items.stream().map(CcrPricingItem::getId).toList();
        List<ApplicationDetailResponse.GuaranteePackageDetail> packages = new ArrayList<>();
        if (!itemIds.isEmpty()) {
            List<CcrGuaranteePackage> pkgs = guaranteePackageMapper.selectList(new LambdaQueryWrapper<CcrGuaranteePackage>()
                    .in(CcrGuaranteePackage::getPricingItemId, itemIds)
                    .orderByAsc(CcrGuaranteePackage::getId));
            for (CcrGuaranteePackage pkg : pkgs) {
                ApplicationDetailResponse.GuaranteePackageDetail pd = new ApplicationDetailResponse.GuaranteePackageDetail();
                pd.setGuaranteePackage(pkg);
                pd.setMeasures(guaranteeMeasureMapper.selectList(new LambdaQueryWrapper<CcrGuaranteeMeasure>()
                        .eq(CcrGuaranteeMeasure::getPackageId, pkg.getId())
                        .orderByAsc(CcrGuaranteeMeasure::getId)));
                packages.add(pd);
            }
        }
        detail.setGuaranteePackages(packages);
        detail.setCommitments(commitmentMapper.selectList(new LambdaQueryWrapper<CcrApplicationCommitment>()
                .eq(CcrApplicationCommitment::getApplicationId, id)
                .orderByAsc(CcrApplicationCommitment::getId)));
        detail.setOtherLoans(otherLoanMapper.selectList(new LambdaQueryWrapper<CcrApplicationOtherLoan>()
                .eq(CcrApplicationOtherLoan::getApplicationId, id)
                .orderByAsc(CcrApplicationOtherLoan::getId)));
        detail.setCreditSummary(creditSummaryMapper.selectList(new LambdaQueryWrapper<CcrApplicationCreditSummary>()
                .eq(CcrApplicationCreditSummary::getApplicationId, id)
                .orderByAsc(CcrApplicationCreditSummary::getId)));
        detail.setRelatedPersons(relatedPersonMapper.selectList(new LambdaQueryWrapper<CcrApplicationRelatedPerson>()
                .eq(CcrApplicationRelatedPerson::getApplicationId, id)
                .orderByAsc(CcrApplicationRelatedPerson::getId)));
        return detail;
    }

    private void copyForCreate(CcrApplication target, CcrApplication src) {
        target.setBusinessType(src.getBusinessType());
        target.setCustomerScope(src.getCustomerScope());
        target.setCustomerNo(src.getCustomerNo());
        target.setGroupNo(src.getGroupNo());
        target.setBusinessNo(IdUtil.getSnowflakeNextIdStr());
        target.setApplicationRemark(src.getApplicationRemark());
        target.setCustomerInfoJson(src.getCustomerInfoJson());
        target.setCreditInfoJson(src.getCreditInfoJson());
        // 集团补录/申请额度快照(集团申请,§docs/19 §4.5;创建时随申请上下文保存多条并存)
        target.setGroupInfoJson(src.getGroupInfoJson());
    }

    private void copyForUpdate(CcrApplication target, CcrApplication src) {
        if (StrUtil.isNotBlank(src.getBusinessType())) target.setBusinessType(src.getBusinessType());
        if (StrUtil.isNotBlank(src.getCustomerScope())) target.setCustomerScope(src.getCustomerScope());
        if (StrUtil.isNotBlank(src.getCustomerNo())) target.setCustomerNo(src.getCustomerNo());
        if (StrUtil.isNotBlank(src.getGroupNo())) target.setGroupNo(src.getGroupNo());
        if (StrUtil.isNotBlank(src.getApplicationRemark())) target.setApplicationRemark(src.getApplicationRemark());
        if (StrUtil.isNotBlank(src.getCustomerInfoJson())) target.setCustomerInfoJson(src.getCustomerInfoJson());
        if (StrUtil.isNotBlank(src.getCreditInfoJson())) target.setCreditInfoJson(src.getCreditInfoJson());
        if (StrUtil.isNotBlank(src.getGroupInfoJson())) target.setGroupInfoJson(src.getGroupInfoJson());
    }

    @Override
    public List<CcrApplication> listApplications(String status) {
        // 数据权限(§5.4):申请人/机构过滤取服务端登录人,不信任前端传参
        SysUserRead user = appLoginUser.requireCurrentUser();
        LambdaQueryWrapper<CcrApplication> wrapper = new LambdaQueryWrapper<CcrApplication>()
                .eq(CcrApplication::getDelFlag, "0")
                .eq(status != null && !status.isBlank(), CcrApplication::getStatus, status)
                .orderByDesc(CcrApplication::getCreateTime);
        String role = user.getRoleCode();
        if (AppLoginUser.ROLE_PRESIDENT.equals(role) || AppLoginUser.ROLE_AUDITOR.equals(role)
                || AppLoginUser.ROLE_ADMIN.equals(role)) {
            // 行长/审计/管理员:全量
        } else if (AppLoginUser.ROLE_BRANCH_MANAGER.equals(role)) {
            // 支行行长:所属支行及下辖网点申请。
            String branchCode = resolveBranchCode(user.getOrgId());
            if (branchCode == null) {
                return List.of();
            }
            wrapper.eq(CcrApplication::getApplyBranchCode, branchCode);
        } else if (AppLoginUser.ROLE_DEPT_GM.equals(role)
                || AppLoginUser.ROLE_VICE_PRESIDENT.equals(role)
                || AppLoginUser.ROLE_COMMITTEE_MEMBER.equals(role)) {
            // 审批角色:仅本人已经参与过的申请；当前待办走审批任务接口。
            wrapper.inSql(CcrApplication::getId,
                    "SELECT pi.application_id FROM ccr_approval_action aa "
                            + "JOIN ccr_pricing_item pi ON pi.id = aa.pricing_item_id "
                            + "WHERE aa.del_flag = '0' AND aa.operator_id = " + user.getId()
                            + " UNION SELECT vr.application_id FROM ccr_vote_assignment va "
                            + "JOIN ccr_vote_round vr ON vr.id = va.round_id "
                            + "WHERE va.del_flag = '0' AND va.status <> 'REPLACED' "
                            + "AND vr.del_flag = '0' AND va.voter_user_id = " + user.getId());
        } else {
            // 客户经理及其他角色:仅本人申请
            wrapper.eq(CcrApplication::getApplicantUserId, user.getId());
        }
        List<CcrApplication> list = applicationMapper.selectList(wrapper);
        enrichNodeProgress(list);
        return list;
    }

    /** 审批轨迹增强:补在途分项当前节点文本与到达当前节点时间(该节点最早动作时间;首节点回退提交时间) */
    private void enrichNodeProgress(List<CcrApplication> list) {
        if (list.isEmpty()) {
            return;
        }
        StringBuilder inSb = new StringBuilder();
        for (CcrApplication app : list) {
            if (inSb.length() > 0) {
                inSb.append(',');
            }
            inSb.append(app.getId());
        }
        Map<Long, LinkedHashSet<String>> nodeByApp = new HashMap<>();
        Map<Long, String> reachByApp = new HashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT pi.application_id applicationId, pi.current_node_code nodeCode,"
                        + " COALESCE(a.operation_time, ap.submit_time) reachTime"
                        + " FROM ccr_pricing_item pi JOIN ccr_application ap ON ap.id = pi.application_id"
                        + " LEFT JOIN (SELECT pricing_item_id, node_code, MIN(operation_time) operation_time"
                        + "   FROM ccr_approval_action WHERE del_flag = '0' GROUP BY pricing_item_id, node_code) a"
                        + "   ON a.pricing_item_id = pi.id AND a.node_code = pi.current_node_code"
                        + " WHERE pi.application_id IN (" + inSb + ") AND pi.del_flag = '0'"
                        + " AND pi.current_node_code IS NOT NULL AND pi.status != 'REJECTED'")) {
            Long appId = ((Number) r.get("applicationId")).longValue();
            Object node = r.get("nodeCode");
            if (node == null) {
                continue;
            }
            nodeByApp.computeIfAbsent(appId, k -> new LinkedHashSet<>()).add(node.toString());
            if (r.get("reachTime") != null) {
                String t = r.get("reachTime").toString();
                reachByApp.merge(appId, t, (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
        }
        for (CcrApplication app : list) {
            // 草稿未提交不展示节点进度
            if ("DRAFT".equals(app.getStatus())) {
                continue;
            }
            LinkedHashSet<String> ns = nodeByApp.get(app.getId());
            if (ns != null && !ns.isEmpty()) {
                app.setCurrentNodeText(String.join("、", ns));
            }
            app.setNodeReachTime(reachByApp.get(app.getId()));
        }
    }
}
