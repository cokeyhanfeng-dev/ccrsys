package com.ccr.admin.system.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ccr.admin.system.domain.CcrSysDept;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.domain.CcrSysUserPost;
import com.ccr.admin.system.mapper.CcrSysDeptMapper;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import com.ccr.admin.system.mapper.CcrSysUserPostMapper;
import com.ccr.common.core.domain.R;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户管理(基础系统功能,详设 §11.12)
 */
@RestController
@RequestMapping("/system/users")
public class SysUserController {

    @Resource
    private CcrSysUserMapper userMapper;

    @Resource
    private CcrSysUserPostMapper userPostMapper;

    @Resource
    private CcrSysDeptMapper deptMapper;

    /** 用户列表(筛选:机构/角色/状态/关键字 + 分页,§11.12) */
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) String username,
                                       @RequestParam(required = false) String roleCode,
                                       @RequestParam(required = false) Long orgId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false, defaultValue = "1") Integer pageNum,
                                       @RequestParam(required = false, defaultValue = "10") Integer pageSize) {
        Page<CcrSysUser> page = userMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 200)),
                new LambdaQueryWrapper<CcrSysUser>()
                        .eq(CcrSysUser::getDelFlag, "0")
                        .like(StrUtil.isNotBlank(username), CcrSysUser::getUsername, username)
                        .eq(StrUtil.isNotBlank(roleCode), CcrSysUser::getRoleCode, roleCode)
                        .eq(orgId != null, CcrSysUser::getOrgId, orgId)
                        .eq(StrUtil.isNotBlank(status), CcrSysUser::getStatus, status)
                        .and(StrUtil.isNotBlank(keyword), w -> w
                                .like(CcrSysUser::getUsername, keyword)
                                .or().like(CcrSysUser::getNickName, keyword))
                        .orderByAsc(CcrSysUser::getCreateTime));
        // 脱敏密码
        page.getRecords().forEach(u -> u.setPassword(null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("records", page.getRecords());
        return R.ok(result);
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
        // delFlag 为 MP 全局逻辑删除字段(logic-delete-field:delFlag),updateById 会排除该字段更新,
        // 直接 setDelFlag 不生效;须用 deleteById 触发逻辑删除(UPDATE del_flag='1')
        userMapper.deleteById(id);
        // 连带逻辑删除机构-岗位绑定
        userPostMapper.delete(new LambdaQueryWrapper<CcrSysUserPost>().eq(CcrSysUserPost::getUserId, id));
        return R.ok();
    }

    /** 用户机构-岗位绑定列表(§11.12,含默认机构) */
    @GetMapping("/{id}/binding")
    public R<List<Map<String, Object>>> bindings(@PathVariable Long id) {
        CcrSysUser exist = userMapper.selectById(id);
        if (exist == null || "1".equals(exist.getDelFlag())) {
            throw new ServiceException(404, "用户不存在");
        }
        List<CcrSysUserPost> posts = userPostMapper.selectList(new LambdaQueryWrapper<CcrSysUserPost>()
                .eq(CcrSysUserPost::getUserId, id)
                .eq(CcrSysUserPost::getDelFlag, "0")
                .orderByDesc(CcrSysUserPost::getIsDefault)
                .orderByAsc(CcrSysUserPost::getCreateTime));
        Map<Long, CcrSysDept> deptMap = posts.isEmpty() ? Map.of()
                : deptMapper.selectList(new LambdaQueryWrapper<CcrSysDept>()
                        .in(CcrSysDept::getId, posts.stream().map(CcrSysUserPost::getOrgId).toList()))
                .stream().collect(Collectors.toMap(CcrSysDept::getId, Function.identity()));
        List<Map<String, Object>> result = posts.stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", p.getId());
            row.put("userId", p.getUserId());
            row.put("orgId", p.getOrgId());
            row.put("postCode", p.getPostCode());
            row.put("isDefault", p.getIsDefault());
            CcrSysDept dept = deptMap.get(p.getOrgId());
            row.put("orgName", dept == null ? null : dept.getDeptName());
            row.put("orgCode", dept == null ? null : dept.getOrgCode());
            return row;
        }).toList();
        return R.ok(result);
    }

    /**
     * 维护用户机构-岗位绑定(§11.12):多行整体替换;
     * 停用机构不可绑定;默认机构/岗位唯一;user+org+post 不重复
     */
    @PutMapping("/{id}/binding")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> saveBindings(@PathVariable Long id, @RequestBody List<CcrSysUserPost> bindings) {
        CcrSysUser exist = userMapper.selectById(id);
        if (exist == null || "1".equals(exist.getDelFlag())) {
            throw new ServiceException(404, "用户不存在");
        }
        if (bindings == null || bindings.isEmpty()) {
            throw new ServiceException(400, "至少保留一条机构-岗位绑定");
        }
        long defaults = bindings.stream().filter(b -> "1".equals(b.getIsDefault())).count();
        if (defaults != 1) {
            throw new ServiceException(400, "默认机构/岗位必须且仅能有一条");
        }
        Set<String> combo = new HashSet<>();
        for (CcrSysUserPost b : bindings) {
            if (b.getOrgId() == null || StrUtil.isBlank(b.getPostCode())) {
                throw new ServiceException(400, "绑定行机构与岗位必填");
            }
            if (!combo.add(b.getOrgId() + ":" + b.getPostCode())) {
                throw new ServiceException(400, "存在重复的机构-岗位绑定:" + b.getOrgId() + "/" + b.getPostCode());
            }
            CcrSysDept dept = deptMapper.selectById(b.getOrgId());
            if (dept == null || "1".equals(dept.getDelFlag())) {
                throw new ServiceException(400, "绑定机构不存在:" + b.getOrgId());
            }
            if (!"ENABLE".equals(dept.getStatus())) {
                throw new ServiceException(400, "机构已停用,不可绑定:" + dept.getDeptName());
            }
        }
        // 整体替换:物理删除旧绑定后重建(delete() 受全局逻辑删除拦截会变逻辑删,
        // 旧绑定 del_flag='1' 仍占 uk_user_org_post 唯一键,重建 insert 撞键报"重复提交",须用原生物理删)
        userPostMapper.physicalDeleteByUserId(id);
        LocalDateTime now = LocalDateTime.now();
        for (CcrSysUserPost b : bindings) {
            CcrSysUserPost row = new CcrSysUserPost();
            row.setTenantId("000000");
            row.setUserId(id);
            row.setOrgId(b.getOrgId());
            row.setPostCode(b.getPostCode());
            row.setIsDefault("1".equals(b.getIsDefault()) ? "1" : "0");
            row.setDelFlag("0");
            row.setCreateTime(now);
            userPostMapper.insert(row);
        }
        return R.ok();
    }
}
