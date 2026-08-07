package com.ccr.admin.system.support;

import com.ccr.admin.system.domain.CcrSysDept;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysDeptMapper;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import com.ccr.common.datascope.DataScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * 数据权限范围计算单测(详设 §5.4)
 */
@ExtendWith(MockitoExtension.class)
class DataScopeHelperTest {

    @Mock
    private CcrSysUserMapper userMapper;

    @Mock
    private CcrSysDeptMapper deptMapper;

    @InjectMocks
    private DataScopeHelper dataScopeHelper;

    private CcrSysUser user(Long id, String roleCode, Long orgId) {
        CcrSysUser u = new CcrSysUser();
        u.setId(id);
        u.setRoleCode(roleCode);
        u.setOrgId(orgId);
        u.setDelFlag("0");
        return u;
    }

    private CcrSysDept dept(Long id, String orgCode, String branchCode) {
        CcrSysDept d = new CcrSysDept();
        d.setId(id);
        d.setOrgCode(orgCode);
        d.setBranchCode(branchCode);
        return d;
    }

    @Test
    void allLevelRoles() {
        for (String role : new String[]{"president", "auditor", "admin"}) {
            DataScope scope = dataScopeHelper.compute(user(1003L, role, 1000L));
            assertEquals(DataScope.LEVEL_ALL, scope.getLevel());
            assertNull(scope.getOrgCodePrefix());
        }
    }

    @Test
    void branchManagerDeptByBranchPrefix() {
        // 城东支行(id=1001, org_code=100201, branch_code=100201)
        when(deptMapper.selectById(1001L)).thenReturn(dept(1001L, "100201", "100201"));
        DataScope scope = dataScopeHelper.compute(user(1001L, "branch_manager", 1001L));
        assertEquals(DataScope.LEVEL_DEPT, scope.getLevel());
        assertEquals("100201", scope.getOrgCodePrefix());
    }

    @Test
    void branchManagerAtNetworkUsesParentBranchCode() {
        // 网点(id=1009, org_code=10020101, branch_code=100201)→ 仍按所属支行前缀
        when(deptMapper.selectById(1009L)).thenReturn(dept(1009L, "10020101", "100201"));
        DataScope scope = dataScopeHelper.compute(user(1001L, "branch_manager", 1009L));
        assertEquals(DataScope.LEVEL_DEPT, scope.getLevel());
        assertEquals("100201", scope.getOrgCodePrefix());
    }

    @Test
    void deptGmByOwnDeptPrefix() {
        // 公司金融部(id=1003, org_code=100101)
        when(deptMapper.selectById(1003L)).thenReturn(dept(1003L, "100101", null));
        DataScope scope = dataScopeHelper.compute(user(1010L, "dept_gm", 1003L));
        assertEquals(DataScope.LEVEL_DEPT, scope.getLevel());
        assertEquals("100101", scope.getOrgCodePrefix());
    }

    @Test
    void customerManagerSelf() {
        DataScope scope = dataScopeHelper.compute(user(1000L, "customer_manager", 1001L));
        assertEquals(DataScope.LEVEL_SELF, scope.getLevel());
        assertEquals(1000L, scope.getUserId());
        assertNull(scope.getOrgCodePrefix());
    }

    @Test
    void unknownUserFallsBackToSelf() {
        when(userMapper.selectById(9999L)).thenReturn(null);
        DataScope scope = dataScopeHelper.compute(9999L);
        assertEquals(DataScope.LEVEL_SELF, scope.getLevel());
    }
}
