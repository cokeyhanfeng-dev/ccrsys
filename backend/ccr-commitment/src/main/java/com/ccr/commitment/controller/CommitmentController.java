package com.ccr.commitment.controller;

import cn.hutool.core.util.StrUtil;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.commitment.service.CommitmentTrackService;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 承诺跟踪接口(v2 简化,docs/28):一张跟踪表三种读法(实时完成度/惰性结算/机构达成率)。
 * 旧计划/评估/策略/月报端点已随旧体系停用删除;机构达成率保留沿用的 GET /ccr/commitments/org-achievement,
 * 换为聚合终态行(先惰性结算,met_rate/avg_ratio)。
 */
@RestController
@RequestMapping("/ccr/commitments")
public class CommitmentController {

    @Resource
    private CommitmentTrackService commitmentTrackService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 贡献度跟踪列表(v2:平铺 track 记录,TRACKING 实时算完成度/终态读定案;读前惰性结算;数据权限在 trackService 内) */
    @GetMapping("/tracks")
    public R<List<Map<String, Object>>> listTracks(@RequestParam(required = false) Long orgId,
                                                   @RequestParam(required = false) Long managerId,
                                                   @RequestParam(required = false) String customerNo,
                                                   @RequestParam(required = false) String status) {
        return R.ok(commitmentTrackService.listTracks(orgId, managerId, customerNo, status));
    }

    /** 单条承诺跟踪详情(承诺要素 + 实时/定案信息 + 所属申请摘要) */
    @GetMapping("/tracks/{trackId}")
    public R<Map<String, Object>> trackDetail(@PathVariable Long trackId) {
        return R.ok(commitmentTrackService.trackDetail(trackId));
    }

    /** 客户所属机构达成率(§11.8 口径收敛:客户号→开户机构→聚合该机构终态行;数据权限在 trackService 内) */
    @GetMapping("/org-achievement")
    public R<Map<String, Object>> orgAchievement(@RequestParam String customerNo) {
        Long orgId = resolveOrgId(customerNo);
        if (orgId == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "客户未登记开户机构: " + customerNo);
        }
        List<Map<String, Object>> rows = commitmentTrackService.orgAchievement(orgId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customerNo", customerNo);
        result.put("orgId", orgId);
        result.put("orgName", orgName(orgId));
        result.put("stats", rows.isEmpty() ? null : rows.get(0));
        result.put("list", rows);
        return R.ok(result);
    }

    /** 客户所属机构:数仓对公/对私主数据最新批次开户机构号 → ccr_sys_dept(org_code 匹配) */
    private Long resolveOrgId(String customerNo) {
        String orgCode = null;
        List<Map<String, Object>> corp = jdbcTemplate.queryForList(
                "SELECT openact_org_no FROM caps_corp_cust_basic_info "
                        + "WHERE cust_no = ? AND data_dt = (SELECT MAX(data_dt) FROM caps_corp_cust_basic_info WHERE cust_no = ?) LIMIT 1",
                customerNo, customerNo);
        if (!corp.isEmpty() && corp.get(0).get("openact_org_no") != null) {
            orgCode = corp.get(0).get("openact_org_no").toString();
        } else {
            List<Map<String, Object>> indv = jdbcTemplate.queryForList(
                    "SELECT opnact_org_no FROM caps_indv_cust_basic_info "
                            + "WHERE cust_no = ? AND data_dt = (SELECT MAX(data_dt) FROM caps_indv_cust_basic_info WHERE cust_no = ?) LIMIT 1",
                    customerNo, customerNo);
            if (!indv.isEmpty() && indv.get(0).get("opnact_org_no") != null) {
                orgCode = indv.get(0).get("opnact_org_no").toString();
            }
        }
        if (StrUtil.isBlank(orgCode)) {
            return null;
        }
        List<Map<String, Object>> dept = jdbcTemplate.queryForList(
                "SELECT id FROM ccr_sys_dept WHERE org_code = ? AND del_flag = '0' LIMIT 1", orgCode);
        return dept.isEmpty() ? null : Long.valueOf(dept.get(0).get("id").toString());
    }

    private String orgName(Long orgId) {
        if (orgId == null) {
            return null;
        }
        List<Map<String, Object>> dept = jdbcTemplate.queryForList(
                "SELECT dept_name FROM ccr_sys_dept WHERE id = ? AND del_flag = '0'", orgId);
        return dept.isEmpty() ? null : dept.get(0).get("dept_name").toString();
    }
}
