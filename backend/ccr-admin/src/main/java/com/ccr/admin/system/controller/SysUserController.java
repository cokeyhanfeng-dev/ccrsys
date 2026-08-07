package com.ccr.admin.system.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户管理(基础系统功能)
 */
@RestController
@RequestMapping("/system/users")
public class SysUserController {

    @Resource
    private CcrSysUserMapper userMapper;

    /** 用户列表(按用户名/角色过滤) */
    @GetMapping
    public R<List<CcrSysUser>> list(@RequestParam(required = false) String username,
                                    @RequestParam(required = false) String roleCode) {
        List<CcrSysUser> list = userMapper.selectList(new LambdaQueryWrapper<CcrSysUser>()
                .eq(CcrSysUser::getDelFlag, "0")
                .like(StrUtil.isNotBlank(username), CcrSysUser::getUsername, username)
                .eq(StrUtil.isNotBlank(roleCode), CcrSysUser::getRoleCode, roleCode)
                .orderByAsc(CcrSysUser::getCreateTime));
        // 脱敏密码
        list.forEach(u -> u.setPassword(null));
        return R.ok(list);
    }

    /** 新建用户 */
    @PostMapping
    public R<CcrSysUser> create(@RequestBody CcrSysUser user) {
        if (StrUtil.isBlank(user.getUsername()) || StrUtil.isBlank(user.getRoleCode())) {
            throw new ServiceException(400, "用户名与角色必填");
        }
        Long dup = userMapper.selectCount(new LambdaQueryWrapper<CcrSysUser>()
                .eq(CcrSysUser::getUsername, user.getUsername()));
        if (dup != null && dup > 0) {
            throw new ServiceException("用户名已存在");
        }
        user.setTenantId("000000");
        user.setStatus(StrUtil.isBlank(user.getStatus()) ? "ENABLE" : user.getStatus());
        user.setDelFlag("0");
        user.setCreateTime(LocalDateTime.now());
        // 密码 BCrypt 加密存储(不落明文)
        if (StrUtil.isNotBlank(user.getPassword())) {
            user.setPassword(BCrypt.hashpw(user.getPassword(), BCrypt.gensalt()));
        }
        userMapper.insert(user);
        user.setPassword(null);
        return R.ok(user);
    }

    /** 编辑用户(空密码保留原值) */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody CcrSysUser user) {
        CcrSysUser exist = userMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(404, "用户不存在");
        }
        if (StrUtil.isBlank(user.getPassword())) {
            user.setPassword(exist.getPassword()); // 保留原密码
        }
        user.setId(id);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        return R.ok();
    }

    /** 启停用户 */
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestBody CcrSysUser user) {
        CcrSysUser exist = userMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(404, "用户不存在");
        }
        exist.setStatus(user.getStatus());
        exist.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(exist);
        return R.ok();
    }

    /** 删除用户(逻辑) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        CcrSysUser exist = userMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(404, "用户不存在");
        }
        exist.setDelFlag("1");
        exist.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(exist);
        return R.ok();
    }
}
