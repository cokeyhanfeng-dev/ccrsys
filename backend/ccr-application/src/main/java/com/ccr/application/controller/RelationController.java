package com.ccr.application.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrRelation;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrRelationMapper;
import com.ccr.application.support.AppLoginUser;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关联人唯一绑定(§6.2/§10.3.21/§11.2)
 * <p>对公 USCC(统一社会信用代码)/对私 ID_CARD(身份证),证件号必填;一个关联人全行唯一绑定
 * 一个客户/集团——已绑定其他客户/集团返回冲突;同客户/集团幂等允许;暂不支持解绑。
 * 写库唯一键 uk_relation_cert(cert_type,cert_no,del_flag) 并发兜底。</p>
 */
@RestController
@RequestMapping("/ccr/relations")
public class RelationController {

    private static final Set<String> CERT_TYPES = Set.of("USCC", "ID_CARD");

    @Resource
    private CcrRelationMapper relationMapper;

    @Resource
    private CcrApplicationMapper applicationMapper;

    @Resource
    private AppLoginUser appLoginUser;

    /** 判重查询(§11.2 relations/check):返回证件号是否已绑定及绑定对象 */
    @GetMapping("/check")
    public R<Map<String, Object>> check(@RequestParam String certType, @RequestParam String certNo) {
        validateCert(certType, certNo);
        CcrRelation exist = findByCert(certType, certNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bound", exist != null);
        result.put("boundCustomerNo", exist == null ? null : exist.getCustomerNo());
        result.put("boundGroupNo", exist == null ? null : exist.getGroupNo());
        result.put("relationName", exist == null ? null : exist.getRelationName());
        return R.ok(result);
    }

    /** 绑定(§11.2 relations/bind):绑定对象=申请主客户(单户 customer_no/集团 group_no);冲突/幂等 */
    @PostMapping("/bind")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> bind(@RequestBody Map<String, Object> body) {
        Long loginId = appLoginUser.requireLoginId();
        String certType = str(body.get("certType"));
        String certNo = str(body.get("certNo"));
        validateCert(certType, certNo);

        // 绑定对象:applicationId 提供则取申请主客户(customer_scope=GROUP 绑集团,否则绑客户);否则用入参
        String customerNo = str(body.get("customerNo"));
        String groupNo = str(body.get("groupNo"));
        String applicationNo = null;
        Long orgId = null;
        Object appId = body.get("applicationId");
        if (appId != null) {
            CcrApplication app = applicationMapper.selectById(((Number) appId).longValue());
            if (app == null || "1".equals(app.getDelFlag())) {
                throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "申请不存在:" + appId);
            }
            applicationNo = app.getApplicationNo();
            orgId = app.getOrgId();
            if (StrUtil.isBlank(customerNo) && StrUtil.isBlank(groupNo)) {
                if ("GROUP".equals(app.getCustomerScope())) {
                    groupNo = app.getGroupNo();
                } else {
                    customerNo = app.getCustomerNo();
                }
            }
        }
        if (StrUtil.isBlank(customerNo) && StrUtil.isBlank(groupNo)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "绑定对象缺失:须提供 customerNo 或 groupNo(或 applicationId)");
        }

        CcrRelation exist = findByCert(certType, certNo);
        if (exist != null) {
            boolean same = sameTarget(exist, customerNo, groupNo);
            if (!same) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "证件号已绑定其他客户/集团,禁止重复绑定:"
                                + (exist.getCustomerNo() != null ? exist.getCustomerNo() : exist.getGroupNo()));
            }
            // 同客户/集团幂等:不重复插入,返回已存在绑定
            return R.ok(resultOf(exist, false));
        }

        CcrRelation row = new CcrRelation();
        row.setTenantId("000000");
        row.setOrgId(orgId);
        row.setStatus("ACTIVE");
        row.setVersionNo(1);
        row.setCreateBy(loginId);
        row.setDelFlag("0");
        row.setCertType(certType);
        row.setCertNo(certNo);
        row.setRelationName(str(body.get("relationName")));
        row.setRelationType(str(body.get("relationType")));
        row.setCustomerNo(StrUtil.isBlank(customerNo) ? null : customerNo);
        row.setGroupNo(StrUtil.isBlank(groupNo) ? null : groupNo);
        row.setBindApplicationNo(applicationNo);
        row.setSource(StrUtil.isBlank(str(body.get("source"))) ? "MANUAL" : str(body.get("source")));
        row.setBindTime(LocalDateTime.now());
        try {
            relationMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发兜底:唯一键 uk_relation_cert 冲突
            CcrRelation concurrent = findByCert(certType, certNo);
            if (concurrent != null && !sameTarget(concurrent, customerNo, groupNo)) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "证件号已绑定其他客户/集团,并发绑定冲突");
            }
            return R.ok(resultOf(concurrent, false)); // 并发下同目标幂等
        }
        return R.ok(resultOf(row, true));
    }

    /** 申请关联人列表(§11.2 application/{id}/relations):按申请主客户反查已绑定关联人 */
    @GetMapping("/application/{applicationId}")
    public R<List<CcrRelation>> applicationRelations(@PathVariable Long applicationId) {
        appLoginUser.requireLoginId();
        CcrApplication app = applicationMapper.selectById(applicationId);
        if (app == null || "1".equals(app.getDelFlag())) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "申请不存在:" + applicationId);
        }
        List<CcrRelation> result = new ArrayList<>();
        if (StrUtil.isNotBlank(app.getCustomerNo())) {
            result.addAll(relationMapper.selectList(new LambdaQueryWrapper<CcrRelation>()
                    .eq(CcrRelation::getCustomerNo, app.getCustomerNo())
                    .eq(CcrRelation::getDelFlag, "0")
                    .orderByDesc(CcrRelation::getBindTime)));
        }
        if (StrUtil.isNotBlank(app.getGroupNo())) {
            result.addAll(relationMapper.selectList(new LambdaQueryWrapper<CcrRelation>()
                    .eq(CcrRelation::getGroupNo, app.getGroupNo())
                    .eq(CcrRelation::getDelFlag, "0")
                    .orderByDesc(CcrRelation::getBindTime)));
        }
        return R.ok(result);
    }

    // ---------- 私有 ----------

    private void validateCert(String certType, String certNo) {
        if (StrUtil.isBlank(certType) || !CERT_TYPES.contains(certType)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "证件类型仅支持 USCC(对公)/ID_CARD(对私)");
        }
        if (StrUtil.isBlank(certNo)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "证件号必填");
        }
    }

    private CcrRelation findByCert(String certType, String certNo) {
        return relationMapper.selectOne(new LambdaQueryWrapper<CcrRelation>()
                .eq(CcrRelation::getCertType, certType)
                .eq(CcrRelation::getCertNo, certNo)
                .eq(CcrRelation::getDelFlag, "0")
                .last("LIMIT 1"));
    }

    /** 目标一致判定:customerNo/groupNo 均相同(含同为 null)视为同一绑定对象 */
    private boolean sameTarget(CcrRelation exist, String customerNo, String groupNo) {
        return StrUtil.equals(exist.getCustomerNo(), customerNo)
                && StrUtil.equals(exist.getGroupNo(), groupNo);
    }

    private Map<String, Object> resultOf(CcrRelation r, boolean created) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("id", r.getId());
        result.put("certType", r.getCertType());
        result.put("certNo", r.getCertNo());
        result.put("customerNo", r.getCustomerNo());
        result.put("groupNo", r.getGroupNo());
        return result;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
