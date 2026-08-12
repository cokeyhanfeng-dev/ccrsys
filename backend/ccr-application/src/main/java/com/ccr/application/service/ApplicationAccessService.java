package com.ccr.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.application.read.SysUserRead;
import com.ccr.application.support.AppLoginUser;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 申请业务对象统一授权服务。
 *
 * <p>所有详情、附件、提交和审批详情都通过本服务校验服务端登录身份，避免各控制器
 * 分散拼接数据权限条件。对象权限口径：客户经理仅本人申请；支行行长仅所属支行；
 * 部门总经理、分管行长仅本人当前任务或本人历史经办；委员仅本人表决批次；
 * 合同经办岗可查看已签发决议关联申请；行长、审计、管理员可查看全量。</p>
 */
@Service
public class ApplicationAccessService {

    @Resource
    private AppLoginUser appLoginUser;

    @Resource
    private CcrApplicationMapper applicationMapper;

    @Resource
    private CcrPricingItemMapper pricingItemMapper;

    @Resource
    private NodeAssigneeResolver nodeAssigneeResolver;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** PC 申请创建入口仅允许客户经理。 */
    public void requireCustomerManager() {
        SysUserRead user = appLoginUser.requireCurrentUser();
        if (!AppLoginUser.ROLE_CUSTOMER_MANAGER.equals(user.getRoleCode())) {
            deny("仅客户经理可维护利率申请");
        }
    }

    /** 编辑、预览、校验、提交和重提要求客户经理本人持有申请。 */
    public void requireOwner(Long applicationId) {
        CcrApplication application = requireApplication(applicationId);
        SysUserRead user = appLoginUser.requireCurrentUser();
        if (!AppLoginUser.ROLE_CUSTOMER_MANAGER.equals(user.getRoleCode())
                || !user.getId().equals(application.getApplicantUserId())) {
            deny("无权维护该申请");
        }
    }

    /** 附件上传额外要求申请仍处于草稿状态。 */
    public void requireDraftOwner(Long applicationId) {
        CcrApplication application = requireApplication(applicationId);
        SysUserRead user = appLoginUser.requireCurrentUser();
        if (!AppLoginUser.ROLE_CUSTOMER_MANAGER.equals(user.getRoleCode())
                || !user.getId().equals(application.getApplicantUserId())) {
            deny("无权维护该申请");
        }
        if (!"DRAFT".equals(application.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "仅草稿状态可上传附件");
        }
    }

    /** 申请聚合详情、附件列表和下载的对象级查看权限。 */
    public void requireView(Long applicationId) {
        authorizeView(requireApplication(applicationId), null);
    }

    /** 审批分项详情的对象级查看权限。 */
    public void requirePricingItemView(Long pricingItemId) {
        CcrPricingItem item = pricingItemMapper.selectById(pricingItemId);
        if (item == null || "1".equals(item.getDelFlag())) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "定价分项不存在");
        }
        authorizeView(requireApplication(item.getApplicationId()), item);
    }

    private CcrApplication requireApplication(Long applicationId) {
        CcrApplication application = applicationMapper.selectById(applicationId);
        if (application == null || "1".equals(application.getDelFlag())) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "申请不存在");
        }
        return application;
    }

    private void authorizeView(CcrApplication application, CcrPricingItem requestedItem) {
        SysUserRead user = appLoginUser.requireCurrentUser();
        String role = user.getRoleCode();
        if (AppLoginUser.ROLE_ADMIN.equals(role) || AppLoginUser.ROLE_AUDITOR.equals(role)
                || AppLoginUser.ROLE_PRESIDENT.equals(role)) {
            return;
        }
        if (AppLoginUser.ROLE_CUSTOMER_MANAGER.equals(role)
                && user.getId().equals(application.getApplicantUserId())) {
            return;
        }
        if (AppLoginUser.ROLE_BRANCH_MANAGER.equals(role) && inSameBranch(user, application)) {
            return;
        }
        if ((AppLoginUser.ROLE_DEPT_GM.equals(role) || AppLoginUser.ROLE_VICE_PRESIDENT.equals(role))
                && (isCurrentNodeAssignee(user, application, requestedItem)
                || hasHistoricalParticipation(application.getId(), user.getId()))) {
            return;
        }
        if (AppLoginUser.ROLE_COMMITTEE_MEMBER.equals(role)
                && hasVoteAssignment(application.getId(), user.getId())) {
            return;
        }
        if (AppLoginUser.ROLE_CONTRACT_OPERATOR.equals(role)
                && hasResolution(application.getId())) {
            return;
        }
        deny("无权查看该申请");
    }

    private boolean inSameBranch(SysUserRead user, CcrApplication application) {
        List<String> branchCodes = jdbcTemplate.queryForList("""
                SELECT branch_code
                FROM ccr_sys_dept
                WHERE id = ? AND status = 'ENABLE' AND del_flag = '0'
                  AND branch_code IS NOT NULL
                """, String.class, user.getOrgId());
        return !branchCodes.isEmpty() && branchCodes.get(0).equals(application.getApplyBranchCode());
    }

    private boolean isCurrentNodeAssignee(SysUserRead user, CcrApplication application,
                                          CcrPricingItem requestedItem) {
        String expectedNode = AppLoginUser.ROLE_DEPT_GM.equals(user.getRoleCode())
                ? "DEPT_GENERAL_MANAGER" : "VICE_PRESIDENT";
        List<CcrPricingItem> items = requestedItem == null
                ? pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                        .eq(CcrPricingItem::getApplicationId, application.getId())
                        .eq(CcrPricingItem::getDelFlag, "0")
                        .eq(CcrPricingItem::getCurrentNodeCode, expectedNode))
                : List.of(requestedItem);
        for (CcrPricingItem item : items) {
            if (!expectedNode.equals(item.getCurrentNodeCode())) {
                continue;
            }
            List<Long> assignees = nodeAssigneeResolver.resolveUserIds(
                    expectedNode, application.getApplicantOrgId(), item.getDeptCode());
            if (assignees.isEmpty() || assignees.contains(user.getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasVoteAssignment(Long applicationId, Long userId) {
        return exists("""
                SELECT COUNT(*)
                FROM ccr_vote_assignment va
                JOIN ccr_vote_round vr ON vr.id = va.round_id AND vr.del_flag = '0'
                WHERE vr.application_id = ? AND va.voter_user_id = ?
                  AND va.status <> 'REPLACED' AND va.del_flag = '0'
                """, applicationId, userId);
    }

    private boolean hasHistoricalParticipation(Long applicationId, Long userId) {
        return exists("""
                SELECT COUNT(*)
                FROM ccr_approval_action aa
                JOIN ccr_pricing_item pi ON pi.id = aa.pricing_item_id AND pi.del_flag = '0'
                WHERE pi.application_id = ? AND aa.operator_id = ? AND aa.del_flag = '0'
                """, applicationId, userId);
    }

    private boolean hasResolution(Long applicationId) {
        return exists("""
                SELECT COUNT(*)
                FROM ccr_resolution r
                JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id AND pi.del_flag = '0'
                WHERE pi.application_id = ? AND r.del_flag = '0'
                """, applicationId);
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private void deny(String message) {
        throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), message);
    }
}
