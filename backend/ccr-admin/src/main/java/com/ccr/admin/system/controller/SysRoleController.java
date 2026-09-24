package com.ccr.admin.system.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.admin.system.domain.CcrSysRole;
import com.ccr.admin.system.mapper.CcrSysRoleMapper;
import com.ccr.common.core.domain.R;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 权限管理(基础系统功能:角色 CRUD + 菜单权限)
 */
@cn.dev33.satoken.annotation.SaCheckRole("admin")
@RestController
@RequestMapping("/system/roles")
public class SysRoleController {

    @Resource
    private CcrSysRoleMapper roleMapper;
    @Resource private com.ccr.admin.system.service.MenuService menuService;
    @Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void validate(CcrSysRole role) {
        if (role.getRoleCode() == null || !role.getRoleCode().matches("[a-z][a-z0-9_]{0,31}") ||
            role.getRoleName() == null || role.getRoleName().isBlank() || role.getRoleName().length() > 64 ||
            role.getRemark() != null && role.getRemark().length() > 200)
            throw new ServiceException(400, "请检查角色编码、名称和备注长度");
        if (role.getStatus() == null) role.setStatus("ENABLE");
        if (!java.util.Set.of("ENABLE", "DISABLE").contains(role.getStatus())) throw new ServiceException(400,"角色状态无效");
        role.setMenuIds(menuService.validateGrants(role.getRoleCode(), role.getMenuIds()));
    }

    /** 角色列表 */
    @GetMapping
    public R<List<CcrSysRole>> list() {
        return R.ok(roleMapper.selectList(new LambdaQueryWrapper<CcrSysRole>()
                .eq(CcrSysRole::getDelFlag, "0")
                .orderByAsc(CcrSysRole::getCreateTime)));
    }

    /** 新建角色(含菜单权限 menuIds) */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping
    public R<CcrSysRole> create(@RequestBody CcrSysRole role) {
        menuService.lockConfiguration();
        validate(role);
        role.setId(null); role.setUpdateTime(null);
        if (StrUtil.isBlank(role.getRoleCode()) || StrUtil.isBlank(role.getRoleName())) {
            throw new ServiceException(400, "角色编码与名称必填");
        }
        Long dup = roleMapper.selectCount(new LambdaQueryWrapper<CcrSysRole>()
                .eq(CcrSysRole::getRoleCode, role.getRoleCode()));
        if (dup != null && dup > 0) {
            throw new ServiceException("角色编码已存在");
        }
        role.setTenantId("000000");
        role.setStatus(StrUtil.isBlank(role.getStatus()) ? "ENABLE" : role.getStatus());
        role.setDelFlag("0");
        role.setCreateTime(LocalDateTime.now());
        roleMapper.insert(role);
        return R.ok(role);
    }

    /** 编辑角色(含菜单权限配置) */
    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody CcrSysRole role) {
        menuService.lockConfiguration();
        CcrSysRole exist = roleMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(404, "角色不存在");
        }
        if (role.getRoleCode() != null && !exist.getRoleCode().equals(role.getRoleCode()))
            throw new ServiceException(400,"角色编码不可修改");
        role.setRoleCode(exist.getRoleCode()); validate(role);
        if ("admin".equals(exist.getRoleCode()) && !"ENABLE".equals(role.getStatus()))
            throw new ServiceException(400,"管理员角色不能停用");
        exist.setRoleName(role.getRoleName().trim()); exist.setRemark(role.getRemark());
        exist.setMenuIds(role.getMenuIds()); exist.setStatus(role.getStatus());
        exist.setUpdateTime(LocalDateTime.now());
        roleMapper.updateById(exist);
        return R.ok();
    }

    /** 删除角色 */
    @org.springframework.transaction.annotation.Transactional
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        menuService.lockConfiguration();
        CcrSysRole exist = roleMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(404, "角色不存在");
        }
        if (java.util.Set.of("admin","customer_manager","branch_manager","dept_gm","vice_president","secretary",
                "committee_member","president","auditor","config_reviewer","resolution_query","contract_operator").contains(exist.getRoleCode()))
            throw new ServiceException(400,"内置业务角色不能删除");
        Integer refs = jdbc.queryForObject("SELECT COUNT(*) FROM ccr_sys_user WHERE role_code=? AND del_flag='0'", Integer.class, exist.getRoleCode());
        Integer posts = jdbc.queryForObject("SELECT COUNT(*) FROM ccr_sys_user_post WHERE post_code=? AND del_flag='0'", Integer.class, exist.getRoleCode());
        if ((refs != null && refs > 0) || (posts != null && posts > 0)) throw new ServiceException(409,"角色仍被用户或岗位绑定引用");
        // delFlag 为 MP 全局逻辑删除字段(logic-delete-field:delFlag),updateById 会排除该字段更新,
        // 直接 setDelFlag 不生效;须用 deleteById 触发逻辑删除(UPDATE del_flag='1')
        roleMapper.deleteById(id);
        return R.ok();
    }
}
