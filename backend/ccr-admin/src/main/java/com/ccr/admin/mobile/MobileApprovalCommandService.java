package com.ccr.admin.mobile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.approval.controller.ApprovalController;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** 移动整单提交：版本复核、调价校验与既有审批操作在同一事务内完成。 */
@Service
public class MobileApprovalCommandService {
    @Resource private JdbcTemplate jdbc;
    @Resource private ApplicationAccessService objects;
    @Resource private CcrPricingItemMapper items;
    @Resource private ApprovalController approval;

    @Transactional(rollbackFor = Exception.class)
    public R<?> approve(String key, MobileApprovalController.Approval body) {
        lockVersion(body);
        if (body.rateAdjustments() != null && !body.rateAdjustments().isEmpty()) {
            String businessType = jdbc.queryForObject("SELECT business_type FROM ccr_application WHERE id=?", String.class, body.applicationId());
            if ("SECRETARY".equals(body.nodeCode()) || !"LOAN".equals(businessType))
                throw new ServiceException(403, "当前节点或业务类型不允许移动调价");
            var rows = items.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                    .eq(CcrPricingItem::getApplicationId, body.applicationId()).eq(CcrPricingItem::getDelFlag, "0"));
            for (var entry : body.rateAdjustments().entrySet()) {
                var item = rows.stream().filter(i -> i.getId().equals(entry.getKey())).findFirst()
                        .orElseThrow(() -> new ServiceException(403, "调价分项不属于本申请"));
                MobileApprovalController.validateBp(item.getCurrentApprovalRate() == null
                        ? item.getRequestedRate() : item.getCurrentApprovalRate(), entry.getValue());
            }
        }
        R<?> result = approval.approve(key, command(body));
        advanceVersion(body.applicationId());
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public R<?> reject(String key, MobileApprovalController.Approval body) {
        lockVersion(body);
        R<?> result = approval.reject(key, command(body));
        advanceVersion(body.applicationId());
        return result;
    }

    private void lockVersion(MobileApprovalController.Approval body) {
        objects.requireView(body.applicationId());
        var versions = jdbc.queryForList("SELECT version_no FROM ccr_application WHERE id=? AND del_flag='0' FOR UPDATE",
                Integer.class, body.applicationId());
        if (versions.isEmpty()) throw new ServiceException(404, "申请不存在");
        if (!versions.get(0).equals(body.versionNo()))
            throw new ServiceException(ErrorCode.DATA_VERSION_CONFLICT.getCode(), "申请已变更，请重新打开详情后办理");
    }

    private void advanceVersion(Long id) {
        jdbc.update("UPDATE ccr_application SET version_no=version_no+1 WHERE id=?", id);
    }

    private Map<String, Object> command(MobileApprovalController.Approval body) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", body.applicationId()); data.put("nodeCode", body.nodeCode());
        data.put("versionNo", body.versionNo()); data.put("comment", body.comment());
        // 空集合表示未调价，避免触发既有服务的调价重算分支。
        if (body.rateAdjustments() != null && !body.rateAdjustments().isEmpty()) data.put("rateAdjustments", body.rateAdjustments());
        return data;
    }
}
