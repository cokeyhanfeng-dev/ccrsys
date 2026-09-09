package com.ccr.admin.mobile;

import cn.dev33.satoken.stp.StpUtil;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.*;

/** 移动端准入独立校验；业务对象及节点授权继续由原领域服务执行。 */
@Service
public class MobileAccessService {
    public static final String CLIENT = "ccr-mobile";
    private static final Set<String> ROLES = Set.of("branch_manager", "dept_gm", "vice_president", "secretary", "committee_member", "president");
    @Resource private CcrSysUserMapper users;
    @Resource private NodeAssigneeResolver assignees;

    public List<String> requireEligible(CcrSysUser user) {
        if (user == null || !"ENABLE".equals(user.getStatus()) || "1".equals(user.getDelFlag()))
            throw new ServiceException(401, "账号已停用或不存在");
        // 客户经理及管理/审计账号不得凭兼岗配置获得移动准入。
        if (!ROLES.contains(Objects.toString(user.getRoleCode(), "")))
            throw new ServiceException(403, "当前账号未开通移动审批权限");
        List<String> roles = new ArrayList<>(List.of(user.getRoleCode()));
        if (!roles.contains("committee_member") && assignees.isUserInAssignees("SIX_PEOPLE_GROUP", user.getId())) roles.add("committee_member");
        if (!roles.contains("secretary") && assignees.isUserInAssignees("SECRETARY", user.getId())) roles.add("secretary");
        return roles;
    }
    public CcrSysUser requireCurrent() {
        CcrSysUser user = users.selectById(StpUtil.getLoginIdAsLong());
        requireEligible(user);
        return user;
    }
}
