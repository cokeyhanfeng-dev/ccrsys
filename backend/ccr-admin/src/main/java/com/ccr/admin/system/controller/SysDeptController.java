package com.ccr.admin.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.admin.system.domain.CcrSysDept;
import com.ccr.admin.system.mapper.CcrSysDeptMapper;
import com.ccr.common.core.domain.R;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机构管理(数据权限控制基础)
 */
@RestController
@RequestMapping("/system/depts")
public class SysDeptController {

    @Resource
    private CcrSysDeptMapper deptMapper;

    /** 机构列表(扁平) */
    @GetMapping
    public R<List<CcrSysDept>> list() {
        return R.ok(deptMapper.selectList(new LambdaQueryWrapper<CcrSysDept>()
                .eq(CcrSysDept::getDelFlag, "0")
                .orderByAsc(CcrSysDept::getSortNo)));
    }

    /** 机构树(总行→分行/部门→支行) */
    @GetMapping("/tree")
    public R<List<Map<String, Object>>> tree() {
        List<CcrSysDept> all = deptMapper.selectList(new LambdaQueryWrapper<CcrSysDept>()
                .eq(CcrSysDept::getDelFlag, "0")
                .orderByAsc(CcrSysDept::getSortNo));
        List<Map<String, Object>> tree = new ArrayList<>();
        for (CcrSysDept d : all) {
            if (d.getParentId() == null || d.getParentId() == 0) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", d.getId());
                node.put("deptCode", d.getDeptCode());
                node.put("deptName", d.getDeptName());
                node.put("orgType", d.getOrgType());
                node.put("children", buildChildren(all, d.getId()));
                tree.add(node);
            }
        }
        return R.ok(tree);
    }

    private List<Map<String, Object>> buildChildren(List<CcrSysDept> all, Long parentId) {
        List<Map<String, Object>> children = new ArrayList<>();
        for (CcrSysDept d : all) {
            if (parentId.equals(d.getParentId())) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", d.getId());
                node.put("deptCode", d.getDeptCode());
                node.put("deptName", d.getDeptName());
                node.put("orgType", d.getOrgType());
                node.put("children", buildChildren(all, d.getId()));
                children.add(node);
            }
        }
        return children;
    }
}
