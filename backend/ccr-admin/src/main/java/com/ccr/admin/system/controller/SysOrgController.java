package com.ccr.admin.system.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.admin.system.domain.CcrSysDept;
import com.ccr.admin.system.domain.CcrSysOrg;
import com.ccr.admin.system.mapper.CcrSysDeptMapper;
import com.ccr.admin.system.mapper.CcrSysOrgMapper;
import com.ccr.common.core.domain.R;
import com.ccr.common.exception.ServiceException;
import com.ccr.vote.read.SysUserRead;
import com.ccr.vote.support.CurrentLoginUser;
import jakarta.annotation.Resource;
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
import java.util.Map;

/**
 * 机构档案维护(§10.3.25/§11.12,仅 admin 角色;权限码 ccr:system:org:*)
 * 承载机构完整档案含资质字段(营业执照/金融许可证/社会信用代码),org_code 与
 * ccr_sys_dept 唯一对齐(建档时从机构树校验并同步名称/层级/类型),社会信用代码非空唯一。
 */
@RestController
@RequestMapping("/system/orgs")
public class SysOrgController {

    /** org_type → org_level_code 默认映射(1总行/2分行/3支行/4网点,§10.3.25) */
    private static final Map<String, String> TYPE_LEVEL = Map.of(
            "HEAD", "1", "DEPT", "2", "BRANCH", "3", "NETWORK", "4", "GROUP", "2");

    @Resource
    private CcrSysOrgMapper orgMapper;

    @Resource
    private CcrSysDeptMapper deptMapper;

    @Resource
    private CurrentLoginUser currentLoginUser;

    /** 机构档案列表(筛选:类型/状态/关键字=名称或简称或编码) */
    @GetMapping
    public R<List<CcrSysOrg>> list(@RequestParam(required = false) String orgType,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String keyword) {
        requireAdmin();
        LambdaQueryWrapper<CcrSysOrg> w = new LambdaQueryWrapper<CcrSysOrg>()
                .eq(CcrSysOrg::getDelFlag, "0")
                .orderByAsc(CcrSysOrg::getOrgCode);
        if (StrUtil.isNotBlank(orgType)) {
            w.eq(CcrSysOrg::getOrgType, orgType);
        }
        if (StrUtil.isNotBlank(status)) {
            w.eq(CcrSysOrg::getStatus, status);
        }
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like(CcrSysOrg::getOrgName, keyword)
                    .or().like(CcrSysOrg::getShortName, keyword)
                    .or().like(CcrSysOrg::getOrgCode, keyword));
        }
        return R.ok(orgMapper.selectList(w));
    }

    /** 机构档案查询(按机构编码) */
    @GetMapping("/{code}")
    public R<CcrSysOrg> detail(@PathVariable String code) {
        requireAdmin();
        CcrSysOrg org = orgMapper.selectOne(new LambdaQueryWrapper<CcrSysOrg>()
                .eq(CcrSysOrg::getOrgCode, code)
                .eq(CcrSysOrg::getDelFlag, "0"));
        if (org == null) {
            throw new ServiceException(404, "机构档案不存在:" + code);
        }
        return R.ok(org);
    }

    /** 机构档案新增(§11.12):org_code 与 ccr_sys_dept 唯一对齐(自动同步名称/层级/类型);信用代码非空唯一 */
    @PostMapping
    public R<CcrSysOrg> create(@RequestBody CcrSysOrg org) {
        requireAdmin();
        if (StrUtil.isBlank(org.getOrgCode())) {
            throw new ServiceException(400, "机构编号必填");
        }
        if (StrUtil.isBlank(org.getCreditCode()) || org.getCreditCode().length() != 18) {
            throw new ServiceException(400, "社会信用代码必填且为 18 位");
        }
        Long dupOrg = orgMapper.selectCount(new LambdaQueryWrapper<CcrSysOrg>()
                .eq(CcrSysOrg::getOrgCode, org.getOrgCode())
                .eq(CcrSysOrg::getDelFlag, "0"));
        if (dupOrg != null && dupOrg > 0) {
            throw new ServiceException(400, "机构档案已存在:" + org.getOrgCode());
        }
        Long dupCredit = orgMapper.selectCount(new LambdaQueryWrapper<CcrSysOrg>()
                .eq(CcrSysOrg::getCreditCode, org.getCreditCode())
                .eq(CcrSysOrg::getDelFlag, "0"));
        if (dupCredit != null && dupCredit > 0) {
            throw new ServiceException(400, "社会信用代码已被其他机构占用");
        }
        // 与机构树对齐:org_code 必须在 ccr_sys_dept 存在,并自动同步名称/上级/层级/类型/支行编码
        CcrSysDept dept = deptMapper.selectOne(new LambdaQueryWrapper<CcrSysDept>()
                .eq(CcrSysDept::getOrgCode, org.getOrgCode())
                .eq(CcrSysDept::getDelFlag, "0"));
        if (dept == null) {
            throw new ServiceException(400, "机构编号与机构树(ccr_sys_dept)不对齐:" + org.getOrgCode());
        }
        if (StrUtil.isBlank(org.getOrgName())) {
            org.setOrgName(dept.getDeptName());
        }
        org.setOrgType(StrUtil.isBlank(org.getOrgType()) ? dept.getOrgType() : org.getOrgType());
        if (StrUtil.isBlank(org.getOrgLevelCode())) {
            org.setOrgLevelCode(TYPE_LEVEL.getOrDefault(org.getOrgType(), "2"));
        }
        org.setBranchCode(dept.getBranchCode());
        org.setParentOrgCode(dept.getParentId() == null || dept.getParentId() == 0 ? null : parentCode(dept));
        if (org.getParentOrgCode() != null) {
            org.setParentOrgName(parentName(dept));
        }
        org.setTenantId("000000");
        org.setStatus(StrUtil.isBlank(org.getStatus()) ? "0" : org.getStatus());
        org.setDelFlag("0");
        org.setCreateTime(LocalDateTime.now());
        orgMapper.insert(org);
        return R.ok(org);
    }

    /** 机构档案维护(§11.12):禁改 org_code;信用代码非空唯一(排除自身);资质字段可维护 */
    @PutMapping("/{code}")
    public R<Void> update(@PathVariable String code, @RequestBody CcrSysOrg body) {
        requireAdmin();
        CcrSysOrg exist = orgMapper.selectOne(new LambdaQueryWrapper<CcrSysOrg>()
                .eq(CcrSysOrg::getOrgCode, code)
                .eq(CcrSysOrg::getDelFlag, "0"));
        if (exist == null) {
            throw new ServiceException(404, "机构档案不存在:" + code);
        }
        if (StrUtil.isNotBlank(body.getOrgCode()) && !body.getOrgCode().equals(exist.getOrgCode())) {
            throw new ServiceException(400, "机构编号禁止修改");
        }
        if (StrUtil.isBlank(body.getCreditCode()) || body.getCreditCode().length() != 18) {
            throw new ServiceException(400, "社会信用代码必填且为 18 位");
        }
        if (!body.getCreditCode().equals(exist.getCreditCode())) {
            Long dupCredit = orgMapper.selectCount(new LambdaQueryWrapper<CcrSysOrg>()
                    .eq(CcrSysOrg::getCreditCode, body.getCreditCode())
                    .ne(CcrSysOrg::getId, exist.getId())
                    .eq(CcrSysOrg::getDelFlag, "0"));
            if (dupCredit != null && dupCredit > 0) {
                throw new ServiceException(400, "社会信用代码已被其他机构占用");
            }
            exist.setCreditCode(body.getCreditCode());
        }
        exist.setOrgName(StrUtil.isBlank(body.getOrgName()) ? exist.getOrgName() : body.getOrgName());
        exist.setShortName(body.getShortName());
        exist.setBusinessLicenseNo(body.getBusinessLicenseNo());
        exist.setFinancialLicenseNo(body.getFinancialLicenseNo());
        exist.setRemark(body.getRemark());
        if (StrUtil.isNotBlank(body.getOrgType()) && !body.getOrgType().equals(exist.getOrgType())) {
            exist.setOrgType(body.getOrgType());
            exist.setOrgLevelCode(TYPE_LEVEL.getOrDefault(body.getOrgType(), "2"));
        }
        if (StrUtil.isNotBlank(body.getOrgLevelCode()) && !body.getOrgLevelCode().equals(exist.getOrgLevelCode())) {
            exist.setOrgLevelCode(body.getOrgLevelCode());
        }
        exist.setUpdateTime(LocalDateTime.now());
        orgMapper.updateById(exist);
        return R.ok();
    }

    // ---------- 私有 ----------

    /** 仅 admin 角色(机构档案维护) */
    private SysUserRead requireAdmin() {
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_ADMIN);
        return currentLoginUser.requireCurrentUser();
    }

    private String parentCode(CcrSysDept dept) {
        CcrSysDept p = deptMapper.selectById(dept.getParentId());
        return p == null ? null : p.getOrgCode();
    }

    private String parentName(CcrSysDept dept) {
        CcrSysDept p = deptMapper.selectById(dept.getParentId());
        return p == null ? null : p.getDeptName();
    }
}
