package com.ccr.admin.system.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.admin.system.domain.CcrSysDept;
import com.ccr.admin.system.mapper.CcrSysDeptMapper;
import com.ccr.common.core.domain.R;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 机构管理(详设 §5.1.1/§11.12,数据权限控制基础)
 * 机构编码 org_code 为层级前缀数字码(唯一、禁改),机构范围按编码前缀匹配;
 * 新增机构默认停用;停用/删除前置校验存量业务(未完结申请/在途审批任务/未关闭承诺)
 */
@RestController
@RequestMapping("/system/depts")
public class SysDeptController {

    @Resource
    private CcrSysDeptMapper deptMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 机构列表(扁平) */
    @GetMapping
    public R<List<CcrSysDept>> list() {
        return R.ok(deptMapper.selectList(new LambdaQueryWrapper<CcrSysDept>()
                .eq(CcrSysDept::getDelFlag, "0")
                .orderByAsc(CcrSysDept::getSortNo)));
    }

    /** 机构树(总行→部门/支行→网点) */
    @GetMapping("/tree")
    public R<List<Map<String, Object>>> tree() {
        List<CcrSysDept> all = deptMapper.selectList(new LambdaQueryWrapper<CcrSysDept>()
                .eq(CcrSysDept::getDelFlag, "0")
                .orderByAsc(CcrSysDept::getSortNo));
        List<Map<String, Object>> tree = new ArrayList<>();
        for (CcrSysDept d : all) {
            if (d.getParentId() == null || d.getParentId() == 0) {
                tree.add(toNode(all, d));
            }
        }
        return R.ok(tree);
    }

    private Map<String, Object> toNode(List<CcrSysDept> all, CcrSysDept d) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", d.getId());
        node.put("orgCode", d.getOrgCode());
        node.put("branchCode", d.getBranchCode());
        node.put("deptName", d.getDeptName());
        node.put("orgType", d.getOrgType());
        node.put("status", d.getStatus());
        node.put("children", buildChildren(all, d.getId()));
        return node;
    }

    private List<Map<String, Object>> buildChildren(List<CcrSysDept> all, Long parentId) {
        List<Map<String, Object>> children = new ArrayList<>();
        for (CcrSysDept d : all) {
            if (parentId.equals(d.getParentId())) {
                children.add(toNode(all, d));
            }
        }
        return children;
    }

    /** 新增机构(§11.12):编码唯一校验;缺省编码按父机构前缀自动生成;新增默认停用(§5.1.1) */
    @PostMapping
    public R<CcrSysDept> create(@RequestBody CcrSysDept dept) {
        if (StrUtil.isBlank(dept.getDeptName()) || StrUtil.isBlank(dept.getOrgType())) {
            throw new ServiceException(400, "机构名称与机构类型必填");
        }
        CcrSysDept parent = loadParent(dept.getParentId());
        if (StrUtil.isBlank(dept.getOrgCode())) {
            dept.setOrgCode(generateChildCode(parent));
        } else {
            Long dup = deptMapper.selectCount(new LambdaQueryWrapper<CcrSysDept>()
                    .eq(CcrSysDept::getOrgCode, dept.getOrgCode()));
            if (dup != null && dup > 0) {
                throw new ServiceException(400, "机构编码已存在:" + dept.getOrgCode());
            }
            checkPrefixConsistency(parent, dept.getOrgCode());
        }
        fillHierarchy(dept, parent);
        dept.setTenantId("000000");
        dept.setStatus("DISABLE"); // 新增机构默认停用,启用后方可被绑定与业务选择
        dept.setDelFlag("0");
        dept.setCreateTime(LocalDateTime.now());
        if (dept.getSortNo() == null) {
            dept.setSortNo(1);
        }
        deptMapper.insert(dept);
        return R.ok(dept);
    }

    /** 修改机构(§11.12):禁止改编码;改上级校验成环与编码前缀一致性 */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody CcrSysDept dept) {
        CcrSysDept exist = deptMapper.selectById(id);
        if (exist == null || "1".equals(exist.getDelFlag())) {
            throw new ServiceException(404, "机构不存在");
        }
        // 编码唯一、禁改(§5.1.1)
        if (StrUtil.isNotBlank(dept.getOrgCode()) && !dept.getOrgCode().equals(exist.getOrgCode())) {
            throw new ServiceException(400, "机构编码(org_code)禁止修改");
        }
        exist.setDeptName(StrUtil.isBlank(dept.getDeptName()) ? exist.getDeptName() : dept.getDeptName());
        exist.setManager(dept.getManager());
        exist.setSortNo(dept.getSortNo() == null ? exist.getSortNo() : dept.getSortNo());
        if (StrUtil.isNotBlank(dept.getOrgType()) && !dept.getOrgType().equals(exist.getOrgType())) {
            exist.setOrgType(dept.getOrgType());
        }
        // 改上级:成环校验 + 编码前缀一致性校验,并重算祖先链/支行编码
        if (dept.getParentId() != null && !dept.getParentId().equals(exist.getParentId())) {
            CcrSysDept parent = loadParent(dept.getParentId());
            checkNotCycle(id, dept.getParentId());
            checkPrefixConsistency(parent, exist.getOrgCode());
            exist.setParentId(dept.getParentId());
            fillHierarchy(exist, parent);
        } else {
            // 上级未变:机构类型可能变化,按当前上级重算支行编码
            fillHierarchy(exist, loadParent(exist.getParentId()));
        }
        exist.setUpdateTime(LocalDateTime.now());
        deptMapper.updateById(exist);
        return R.ok();
    }

    /** 启用/停用(§11.12):停用前置校验存量业务(未完结申请/在途任务/未关闭承诺) */
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestBody CcrSysDept body) {
        CcrSysDept exist = deptMapper.selectById(id);
        if (exist == null || "1".equals(exist.getDelFlag())) {
            throw new ServiceException(404, "机构不存在");
        }
        if (!"ENABLE".equals(body.getStatus()) && !"DISABLE".equals(body.getStatus())) {
            throw new ServiceException(400, "状态仅支持 ENABLE/DISABLE");
        }
        if ("DISABLE".equals(body.getStatus())) {
            checkNoOpenBiz(id);
        }
        exist.setStatus(body.getStatus());
        exist.setUpdateTime(LocalDateTime.now());
        deptMapper.updateById(exist);
        return R.ok();
    }

    /** 删除机构(§11.12):有子机构/在用禁止;存量业务校验(§5.1.1) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        CcrSysDept exist = deptMapper.selectById(id);
        if (exist == null || "1".equals(exist.getDelFlag())) {
            throw new ServiceException(404, "机构不存在");
        }
        Long children = deptMapper.selectCount(new LambdaQueryWrapper<CcrSysDept>()
                .eq(CcrSysDept::getParentId, id)
                .eq(CcrSysDept::getDelFlag, "0"));
        if (children != null && children > 0) {
            throw new ServiceException(400, "存在下级机构,禁止删除");
        }
        Long boundUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_sys_user WHERE org_id = ? AND del_flag = '0'", Long.class, id);
        if (boundUsers != null && boundUsers > 0) {
            throw new ServiceException(400, "机构下存在在用用户(" + boundUsers + "人),禁止删除");
        }
        Long boundPosts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_sys_user_post WHERE org_id = ? AND del_flag = '0'", Long.class, id);
        if (boundPosts != null && boundPosts > 0) {
            throw new ServiceException(400, "机构下存在用户岗位绑定(" + boundPosts + "条),禁止删除");
        }
        checkNoOpenBiz(id);
        exist.setDelFlag("1");
        exist.setUpdateTime(LocalDateTime.now());
        deptMapper.updateById(exist);
        return R.ok();
    }

    /** 加载父机构(0=总行根,返回 null) */
    private CcrSysDept loadParent(Long parentId) {
        if (parentId == null || parentId == 0) {
            return null;
        }
        CcrSysDept parent = deptMapper.selectById(parentId);
        if (parent == null || "1".equals(parent.getDelFlag())) {
            throw new ServiceException(400, "父机构不存在");
        }
        return parent;
    }

    /** 按父机构编码前缀自动生成下级编码:无下级=父码+01,否则最大下级码+1 */
    private String generateChildCode(CcrSysDept parent) {
        String prefix = parent == null ? "" : parent.getOrgCode();
        List<CcrSysDept> children = deptMapper.selectList(new LambdaQueryWrapper<CcrSysDept>()
                .eq(CcrSysDept::getParentId, parent == null ? 0L : parent.getId())
                .eq(CcrSysDept::getDelFlag, "0"));
        long max = 0;
        for (CcrSysDept c : children) {
            if (StrUtil.isNotBlank(c.getOrgCode()) && c.getOrgCode().startsWith(prefix)) {
                try {
                    max = Math.max(max, Long.parseLong(c.getOrgCode().substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 非数字后缀不参与自动编码
                }
            }
        }
        return prefix + String.format("%02d", max + 1);
    }

    /** 编码前缀一致性:子机构编码必须以上级机构编码为前缀(§11.12) */
    private void checkPrefixConsistency(CcrSysDept parent, String orgCode) {
        if (parent == null || StrUtil.isBlank(parent.getOrgCode()) || StrUtil.isBlank(orgCode)) {
            return;
        }
        if (!orgCode.startsWith(parent.getOrgCode())) {
            throw new ServiceException(400,
                    "机构编码与上级编码前缀不一致(上级 " + parent.getOrgCode() + ",本机构 " + orgCode + ")");
        }
    }

    /** 成环校验:新上级链上不得包含本机构 */
    private void checkNotCycle(Long id, Long newParentId) {
        Set<Long> visited = new HashSet<>();
        Long cursor = newParentId;
        while (cursor != null && cursor != 0) {
            if (cursor.equals(id)) {
                throw new ServiceException(400, "上级机构不能选择本机构或其下级机构(成环)");
            }
            if (!visited.add(cursor)) {
                break;
            }
            CcrSysDept p = deptMapper.selectById(cursor);
            cursor = p == null ? 0L : p.getParentId();
        }
    }

    /** 祖先链/支行编码推导:BRANCH=自身orgCode;NETWORK=所属支行branchCode;DEPT/HEAD为空 */
    private void fillHierarchy(CcrSysDept dept, CcrSysDept parent) {
        if (parent == null) {
            dept.setParentId(0L);
            dept.setAncestors("0");
        } else {
            dept.setParentId(parent.getId());
            dept.setAncestors(parent.getAncestors() + "," + parent.getId());
        }
        String orgType = dept.getOrgType() == null ? "" : dept.getOrgType();
        switch (orgType) {
            case "BRANCH" -> dept.setBranchCode(dept.getOrgCode());
            case "NETWORK" -> dept.setBranchCode(parent == null ? null
                    : (StrUtil.isNotBlank(parent.getBranchCode()) ? parent.getBranchCode() : parent.getOrgCode()));
            default -> dept.setBranchCode(null);
        }
    }

    /**
     * 停用/删除前置校验(§5.1.1):本机构及全部下级(编码前缀匹配)存在
     * 未完结申请/在途审批任务/未关闭承诺时禁止,提示存量业务数
     */
    private void checkNoOpenBiz(Long id) {
        CcrSysDept dept = deptMapper.selectById(id);
        List<Long> orgIds = jdbcTemplate.queryForList(
                "SELECT id FROM ccr_sys_dept WHERE del_flag = '0' AND org_code LIKE CONCAT(?, '%')",
                Long.class, dept.getOrgCode());
        if (orgIds.isEmpty()) {
            orgIds = List.of(id);
        }
        StringBuilder in = new StringBuilder();
        for (Long orgId : orgIds) {
            in.append(in.length() == 0 ? "" : ",").append(orgId);
        }
        Long openApps = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_application WHERE del_flag = '0'"
                        + " AND (org_id IN (" + in + ") OR applicant_org_id IN (" + in + "))"
                        + " AND status NOT IN ('DRAFT','APPROVED','REJECTED','CLOSED')",
                Long.class);
        Long openTasks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_pricing_item p JOIN ccr_application a ON a.id = p.application_id"
                        + " WHERE p.del_flag = '0' AND a.del_flag = '0'"
                        + " AND (a.org_id IN (" + in + ") OR a.applicant_org_id IN (" + in + "))"
                        + " AND p.status IN ('ROUTING','LEVEL_APPROVAL','VOTING','PRESIDENT_DECISION')",
                Long.class);
        Long openCommitments = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_commitment_plan WHERE del_flag = '0'"
                        + " AND org_id IN (" + in + ")"
                        + " AND status NOT IN ('ACHIEVED','EXPIRED_UNMET','TERMINATED','SUPERSEDED')",
                Long.class);
        long apps = openApps == null ? 0 : openApps;
        long tasks = openTasks == null ? 0 : openTasks;
        long commitments = openCommitments == null ? 0 : openCommitments;
        if (apps + tasks + commitments > 0) {
            throw new ServiceException(400, "机构存在存量业务,禁止停用/删除:未完结申请" + apps
                    + "笔、在途审批任务" + tasks + "笔、未关闭承诺" + commitments + "笔");
        }
    }
}
