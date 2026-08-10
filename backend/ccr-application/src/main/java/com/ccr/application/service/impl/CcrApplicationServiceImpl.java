package com.ccr.application.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrApplicationCommitment;
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
import com.ccr.application.read.SysUserRead;
import com.ccr.application.support.AppLoginUser;
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
    private DataWarehouseService dataWarehouseService;

    @Resource
    private AppLoginUser appLoginUser;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrApplication createDraft(CcrApplication request) {
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
        applicationMapper.insert(entity);

        // 集团场景:写入涉及成员(逐成员真实金额/币种/角色,成员额度从数仓回填)
        saveMembers(entity, request.getMembers(), groupScope);

        // 创建定价分项:贷款按担保切分,存款按结构化存款字段
        List<CcrPricingItem> createdItems = createItemsByBusinessType(entity, request, businessType, groupScope);

        // 拟达成贡献度承诺(供审批通过后生成正式承诺计划读取)
        saveCommitments(entity.getId(), request.getCommitments(), createdItems);
        return entity;
    }

    /**
     * 按申请人机构解析所属支行编码(§5.4 数据权限 DEPT 级前缀过滤数据基础):
     * ccr_sys_dept.id → org_code → sys_org.branch_code;支行/网点有 branch_code,
     * 总行部门为 NULL(打标 NULL,前缀过滤对该类申请不命中);解析失败不阻断创建
     */
    private String resolveBranchCode(Long orgId) {
        if (orgId == null) {
            return null;
        }
        try {
            List<String> codes = jdbcTemplate.queryForList(
                    "SELECT so.branch_code FROM ccr_sys_dept d JOIN sys_org so ON so.org_code = d.org_code "
                            + "WHERE d.id = ? AND d.del_flag = '0' AND so.del_flag = '0' AND so.branch_code IS NOT NULL",
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
            pi.setCreditTrancheRef(strVal(g.get("creditTrancheRef")));
            pricingItemMapper.insert(pi);
            created.add(pi);

            // 分项与贷款合同关系(现有合同号或拟签合同标识)
            String contractBusinessKey = StrUtil.blankToDefault(strVal(g.get("contractBusinessKey")), strVal(g.get("contractNo")));
            // 拟签合同标识留空时自动按分项编号生成(新增授信审批后回填正式合同号,§5.6)
            if (StrUtil.isBlank(contractBusinessKey)
                    && "Y".equals(StrUtil.blankToDefault(strVal(g.get("plannedContractFlag")), "N"))) {
                contractBusinessKey = "PLANNED-" + pi.getPricingItemNo();
            }
            if (StrUtil.isNotBlank(contractBusinessKey)) {
                CcrPricingItemContractRel rel = new CcrPricingItemContractRel();
                rel.setApplicationId(entity.getId());
                rel.setPricingItemId(pi.getId());
                rel.setContractBusinessKey(contractBusinessKey);
                rel.setPlannedContractFlag(StrUtil.blankToDefault(strVal(g.get("plannedContractFlag")), "N"));
                if (!"Y".equals(rel.getPlannedContractFlag())) {
                    rel.setLoanContractNo(contractBusinessKey);
                }
                contractRelMapper.insert(rel);
            }

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
                // 手工补录真实账号:密文+哈希落库,哈希用于与数仓账户关联
                rel.setDepositAccountNoCipher("CIPHER_" + d.getDepositAccountNo());
                rel.setDepositAccountHash(DigestUtil.sha256Hex(d.getDepositAccountNo()));
            } else if (StrUtil.isNotBlank(d.getDepositAccountHash())) {
                // 直接选择数仓账户:以数仓查询哈希绑定(数仓侧账号密文存储,无法回填明文)
                rel.setDepositAccountHash(d.getDepositAccountHash());
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
        if (!groupScope && StrUtil.isBlank(entity.getCustomerNo())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "单户场景创建分项前必须填写客户号(customerNo)");
        }
        CcrPricingItem pi = new CcrPricingItem();
        pi.setApplicationId(entity.getId());
        pi.setPricingItemNo("PI-" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        pi.setPricingCustomerNo(groupScope ? memberCustomerNo : entity.getCustomerNo());
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
    private void saveCommitments(Long applicationId, List<CommitmentInput> commitments, List<CcrPricingItem> createdItems) {
        if (commitments == null) {
            return;
        }
        Map<String, Long> itemNoToId = new HashMap<>();
        for (CcrPricingItem pi : createdItems) {
            itemNoToId.put(pi.getPricingItemNo(), pi.getId());
        }
        // 承诺指标-分项关联兜底(契约缺口修复,§十三 13.2-6):单分项申请未指定 pricingItemNo 时关联唯一分项;
        // 多分项未指定时保持 null(无法确定归属,承诺计划侧按申请兜底不适用,须由前端按分项编号提交)
        Long soleItemId = createdItems.size() == 1 ? createdItems.get(0).getId() : null;
        for (CommitmentInput c : commitments) {
            if (c == null || StrUtil.isBlank(c.getMetricCode()) || StrUtil.isBlank(c.getTargetType())
                    || c.getTargetValue() == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "承诺缺少必填项(metricCode/targetType/targetValue)");
            }
            Long resolvedItemId = null;
            if (StrUtil.isNotBlank(c.getPricingItemNo())) {
                resolvedItemId = itemNoToId.get(c.getPricingItemNo());
            } else if (soleItemId != null) {
                resolvedItemId = soleItemId;
            }
            CcrApplicationCommitment commitment = new CcrApplicationCommitment();
            commitment.setApplicationId(applicationId);
            commitment.setPricingItemId(resolvedItemId);
            commitment.setMetricCode(c.getMetricCode());
            commitment.setTargetType(c.getTargetType());
            commitment.setBaselineValue(c.getBaselineValue());
            commitment.setTargetValue(c.getTargetValue());
            commitment.setUnit(StrUtil.blankToDefault(c.getUnit(), "WAN_YUAN"));
            commitment.setMetricScope(StrUtil.blankToDefault(c.getMetricScope(), "PUBLIC"));
            commitment.setMemberCustomerNo(c.getMemberCustomerNo());
            commitmentMapper.insert(commitment);
        }
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

        applicationMemberMapper.delete(new LambdaQueryWrapper<CcrApplicationMember>()
                .eq(CcrApplicationMember::getApplicationId, applicationId));
        if (inheritedIds.isEmpty()) {
            commitmentMapper.delete(new LambdaQueryWrapper<CcrApplicationCommitment>()
                    .eq(CcrApplicationCommitment::getApplicationId, applicationId));
            contractRelMapper.delete(new LambdaQueryWrapper<CcrPricingItemContractRel>()
                    .eq(CcrPricingItemContractRel::getApplicationId, applicationId));
            depositRelMapper.delete(new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                    .eq(CcrPricingItemDepositRel::getApplicationId, applicationId));
        } else {
            // 承诺:保留绑定沿用分项的记录(申请级承诺 pricing_item_id 为空,随载荷重建)
            commitmentMapper.delete(new LambdaQueryWrapper<CcrApplicationCommitment>()
                    .eq(CcrApplicationCommitment::getApplicationId, applicationId)
                    .and(w -> w.isNull(CcrApplicationCommitment::getPricingItemId)
                            .or().notIn(CcrApplicationCommitment::getPricingItemId, inheritedIds)));
            contractRelMapper.delete(new LambdaQueryWrapper<CcrPricingItemContractRel>()
                    .eq(CcrPricingItemContractRel::getApplicationId, applicationId)
                    .notIn(CcrPricingItemContractRel::getPricingItemId, inheritedIds));
            depositRelMapper.delete(new LambdaQueryWrapper<CcrPricingItemDepositRel>()
                    .eq(CcrPricingItemDepositRel::getApplicationId, applicationId)
                    .notIn(CcrPricingItemDepositRel::getPricingItemId, inheritedIds));
        }
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
        return detail;
    }

    private void copyForCreate(CcrApplication target, CcrApplication src) {
        target.setBusinessType(src.getBusinessType());
        target.setCustomerScope(src.getCustomerScope());
        target.setCustomerNo(src.getCustomerNo());
        target.setGroupNo(src.getGroupNo());
        target.setApplicantUserId(src.getApplicantUserId());
        target.setApplicantOrgId(src.getApplicantOrgId());
        target.setSourceApplicationId(src.getSourceApplicationId());
        target.setBusinessNo(IdUtil.getSnowflakeNextIdStr());
        target.setOrgId(src.getOrgId());
        target.setApplicationRemark(src.getApplicationRemark());
    }

    private void copyForUpdate(CcrApplication target, CcrApplication src) {
        if (StrUtil.isNotBlank(src.getBusinessType())) target.setBusinessType(src.getBusinessType());
        if (StrUtil.isNotBlank(src.getCustomerScope())) target.setCustomerScope(src.getCustomerScope());
        if (StrUtil.isNotBlank(src.getCustomerNo())) target.setCustomerNo(src.getCustomerNo());
        if (StrUtil.isNotBlank(src.getGroupNo())) target.setGroupNo(src.getGroupNo());
        if (src.getApplicantUserId() != null) target.setApplicantUserId(src.getApplicantUserId());
        if (src.getApplicantOrgId() != null) target.setApplicantOrgId(src.getApplicantOrgId());
        if (StrUtil.isNotBlank(src.getApplicationRemark())) target.setApplicationRemark(src.getApplicationRemark());
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
        } else if (AppLoginUser.ROLE_BRANCH_MANAGER.equals(role) || AppLoginUser.ROLE_DEPT_GM.equals(role)
                || AppLoginUser.ROLE_VICE_PRESIDENT.equals(role)) {
            // 支行行长/部门总经理/副行长:按本人机构过滤
            wrapper.eq(CcrApplication::getOrgId, user.getOrgId());
        } else {
            // 客户经理及其他角色:仅本人申请
            wrapper.eq(CcrApplication::getApplicantUserId, user.getId());
        }
        return applicationMapper.selectList(wrapper);
    }
}
