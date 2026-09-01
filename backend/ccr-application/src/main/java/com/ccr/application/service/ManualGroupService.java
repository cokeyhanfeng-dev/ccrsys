package com.ccr.application.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ccr.application.domain.CcrGroup;
import com.ccr.application.domain.CcrGroupMember;
import com.ccr.application.mapper.CcrGroupMapper;
import com.ccr.application.mapper.CcrGroupMemberMapper;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手工集团主数据服务(ccr_group / ccr_group_member)——数仓未统计的集团与公司,系统级新增/补录。
 * <p>数仓快照(dw_customer_group_snapshot 等)仍为权威数据源,本服务只管理手工录入数据;
 * 手工集团无数仓授信快照,批复总额度 approved_total_amount 手工补录(路由定档/额度勾稽基准)。
 * 供集团查询合并(GroupQueryController)、提交链路(ApplicationSubmitServiceImpl)、集团管理(ccr-admin)复用。</p>
 */
@Service
public class ManualGroupService {

    @Resource
    private CcrGroupMapper groupMapper;

    @Resource
    private CcrGroupMemberMapper memberMapper;

    @Resource
    private DataWarehouseService dataWarehouseService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    // ---------- 管理 CRUD ----------

    /** 手工集团分页列表(管理页;keyword 按集团号/名称模糊) */
    public Page<CcrGroup> listGroups(String keyword, int pageNum, int pageSize) {
        return groupMapper.selectPage(new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 100)),
                new LambdaQueryWrapper<CcrGroup>()
                        .and(StrUtil.isNotBlank(keyword), w -> w
                                .like(CcrGroup::getGroupNo, keyword)
                                .or().like(CcrGroup::getGroupName, keyword))
                        .orderByAsc(CcrGroup::getGroupNo));
    }

    /** 集团详情(含成员列表) */
    public Map<String, Object> groupDetail(String groupNo) {
        CcrGroup group = findGroup(groupNo);
        if (group == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "手工集团不存在:" + groupNo);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", group);
        result.put("members", listMembers(groupNo));
        return result;
    }

    /**
     * 新增/编辑手工集团。group_no 全局唯一(ccr_group 内部 + 数仓集团),批复总额度必填且大于 0。
     */
    @Transactional(rollbackFor = Exception.class)
    public CcrGroup saveGroup(CcrGroup group) {
        if (StrUtil.isBlank(group.getGroupNo()) || StrUtil.isBlank(group.getGroupName())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "集团编号与集团名称必填");
        }
        if (group.getApprovedTotalAmount() == null || group.getApprovedTotalAmount().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "批复总额度必填且大于 0(手工集团无数据仓授信快照)");
        }
        String groupNo = group.getGroupNo().trim();
        // 与数仓集团查重(数仓权威,不允许覆盖数仓已统计集团)
        if (dataWarehouseService.findGroup(groupNo) != null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "集团[" + groupNo + "]已存在于数仓集团快照,无需手工新增");
        }
        // ccr_group 内部查重(编辑排除自身)
        CcrGroup exists = findGroup(groupNo);
        if (exists != null && !exists.getId().equals(group.getId())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "集团编号[" + groupNo + "]已存在");
        }
        group.setGroupNo(groupNo);
        group.setGroupType(StrUtil.blankToDefault(group.getGroupType(), "INDUSTRY_GROUP"));
        group.setGroupStatus(StrUtil.blankToDefault(group.getGroupStatus(), "NORMAL"));
        group.setCurrency(StrUtil.blankToDefault(group.getCurrency(), "CNY"));
        if (group.getId() == null) {
            group.setId(null);
            groupMapper.insert(group);
        } else {
            groupMapper.updateById(group);
        }
        return group;
    }

    /** 逻辑删除集团(级联物理删除成员) */
    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(String groupNo) {
        CcrGroup group = findGroup(groupNo);
        if (group == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "手工集团不存在:" + groupNo);
        }
        memberMapper.deletePhysicalByGroup(groupNo);
        groupMapper.deleteById(group.getId());
    }

    /** 保存集团成员(全量替换:先物理删后插,避免唯一键撞键) */
    @Transactional(rollbackFor = Exception.class)
    public void saveMembers(String groupNo, List<CcrGroupMember> members) {
        if (findGroup(groupNo) == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "手工集团不存在:" + groupNo);
        }
        if (members == null || members.isEmpty()) {
            memberMapper.deletePhysicalByGroup(groupNo);
            return;
        }
        memberMapper.deletePhysicalByGroup(groupNo);
        for (CcrGroupMember m : members) {
            if (StrUtil.isBlank(m.getMemberCustomerNo()) || StrUtil.isBlank(m.getMemberName())) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "成员客户号与成员名称必填");
            }
            m.setId(null);
            m.setGroupNo(groupNo);
            memberMapper.insert(m);
        }
    }

    /** 幂等 upsert 成员(存在则更新,不存在则插入;申请提交链路落表用,最新覆盖) */
    @Transactional(rollbackFor = Exception.class)
    public void upsertMember(CcrGroupMember member) {
        if (StrUtil.isBlank(member.getGroupNo()) || StrUtil.isBlank(member.getMemberCustomerNo())
                || StrUtil.isBlank(member.getMemberName())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "所属集团、成员客户号与成员名称必填");
        }
        CcrGroupMember exists = findGroupMember(member.getGroupNo(), member.getMemberCustomerNo());
        if (exists == null) {
            member.setId(null);
            memberMapper.insert(member);
        } else {
            member.setId(exists.getId());
            memberMapper.updateById(member);
        }
    }

    /** 删除单个成员 */
    public void deleteMember(Long id) {
        memberMapper.deleteById(id);
    }

    // ---------- 查询合并(申请页/提交链路复用) ----------

    /** 手工集团(实体;无则 null) */
    public CcrGroup findGroup(String groupNo) {
        if (StrUtil.isBlank(groupNo)) {
            return null;
        }
        return groupMapper.selectOne(new LambdaQueryWrapper<CcrGroup>()
                .eq(CcrGroup::getGroupNo, groupNo)
                .last("limit 1"));
    }

    /** 手工集团联想(group_no/group_name) */
    public List<Map<String, Object>> suggest(String keyword) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CcrGroup g : groupMapper.selectList(new LambdaQueryWrapper<CcrGroup>()
                .and(w -> w.like(CcrGroup::getGroupNo, keyword).or().like(CcrGroup::getGroupName, keyword))
                .orderByAsc(CcrGroup::getGroupNo)
                .last("limit 10"))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupNo", g.getGroupNo());
            row.put("groupName", g.getGroupName());
            result.add(row);
        }
        return result;
    }

    /** 手工成员列表 */
    public List<CcrGroupMember> listMembers(String groupNo) {
        return memberMapper.selectList(new LambdaQueryWrapper<CcrGroupMember>()
                .eq(CcrGroupMember::getGroupNo, groupNo)
                .orderByAsc(CcrGroupMember::getMemberCustomerNo));
    }

    /** 手工成员单条(无则 null) */
    public CcrGroupMember findGroupMember(String groupNo, String memberCustomerNo) {
        return memberMapper.selectOne(new LambdaQueryWrapper<CcrGroupMember>()
                .eq(CcrGroupMember::getGroupNo, groupNo)
                .eq(CcrGroupMember::getMemberCustomerNo, memberCustomerNo)
                .last("limit 1"));
    }

    /** 客户所属手工集团(未删且在团;无则 null)——集团成员单户申请判定(2026-09-01) */
    public Map<String, Object> groupOfCustomer(String customerNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT g.group_no AS groupNo, g.group_name AS groupName
                FROM ccr_group_member m
                JOIN ccr_group g ON g.group_no = m.group_no AND g.del_flag = '0'
                WHERE m.member_customer_no = ? AND m.del_flag = '0'
                  AND (m.relation_end IS NULL OR m.relation_end >= CURDATE())
                LIMIT 1""", customerNo);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
