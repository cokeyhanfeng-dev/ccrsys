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
@RestController
@RequestMapping("/system/roles")
public class SysRoleController {

    @Resource
    private CcrSysRoleMapper roleMapper;

    /** 角色列表 */
    @GetMapping
    public R<List<CcrSysRole>> list() {
        return R.ok(roleMapper.selectList(new LambdaQueryWrapper<CcrSysRole>()
                .eq(CcrSysRole::getDelFlag, "0")
                .orderByAsc(CcrSysRole::getCreateTime)));
    }

    /** 新建角色(含菜单权限 menuIds) */
    @PostMapping
    public R<CcrSysRole> create(@RequestBody CcrSysRole role) {
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
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody CcrSysRole role) {
        CcrSysRole exist = roleMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(404, "角色不存在");
        }
        role.setId(id);
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.updateById(role);
        return R.ok();
    }

    /** 删除角色 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        CcrSysRole exist = roleMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(404, "角色不存在");
        }
        exist.setDelFlag("1");
        exist.setUpdateTime(LocalDateTime.now());
        roleMapper.updateById(exist);
        return R.ok();
    }
}
