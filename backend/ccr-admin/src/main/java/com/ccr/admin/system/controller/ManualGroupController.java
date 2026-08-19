package com.ccr.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ccr.application.domain.CcrGroup;
import com.ccr.application.domain.CcrGroupMember;
import com.ccr.application.service.ManualGroupService;
import com.ccr.common.core.domain.R;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手工集团主数据维护(系统管理):集团/成员增删改查。
 * 数仓未统计的集团(ccr_group)与公司(ccr_group_member)在此维护,供集团申请页合并查询;
 * 手工集团批复总额度补录,提交时路由定档/额度勾稽使用。
 */
@RestController
@RequestMapping("/system/manual-group")
public class ManualGroupController {

    @Resource
    private ManualGroupService manualGroupService;

    /** 手工集团分页列表 */
    @SaCheckRole("admin")
    @GetMapping("/page")
    public R<Map<String, Object>> page(@RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        Page<CcrGroup> page = manualGroupService.listGroups(keyword, pageNum, pageSize);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", page.getTotal());
        data.put("records", page.getRecords());
        return R.ok(data);
    }

    /** 集团详情(含成员列表) */
    @SaCheckRole("admin")
    @GetMapping("/{groupNo}")
    public R<Map<String, Object>> detail(@PathVariable String groupNo) {
        return R.ok(manualGroupService.groupDetail(groupNo));
    }

    /** 新增/编辑手工集团(group_no 查重:ccr_group 内部 + 数仓集团) */
    @SaCheckRole("admin")
    @PostMapping
    public R<CcrGroup> save(@RequestBody CcrGroup group) {
        return R.ok(manualGroupService.saveGroup(group));
    }

    /** 删除手工集团(逻辑删除集团 + 级联物理删除成员) */
    @SaCheckRole("admin")
    @PostMapping("/{groupNo}/delete")
    public R<Void> delete(@PathVariable String groupNo) {
        manualGroupService.deleteGroup(groupNo);
        return R.ok();
    }

    /** 保存集团成员(全量替换:先物理删后插) */
    @SaCheckRole("admin")
    @PostMapping("/{groupNo}/members")
    public R<Void> saveMembers(@PathVariable String groupNo, @RequestBody List<CcrGroupMember> members) {
        manualGroupService.saveMembers(groupNo, members);
        return R.ok();
    }

    /** 删除单个成员 */
    @SaCheckRole("admin")
    @PostMapping("/member/{id}/delete")
    public R<Void> deleteMember(@PathVariable Long id) {
        manualGroupService.deleteMember(id);
        return R.ok();
    }
}
