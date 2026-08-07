package com.ccr.admin.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 节点审批人指派(§5.5.1/§10.3.19 ccr_node_assignee)
 * <p>审批人可配置,四层解析(PERSON→GROUP→DEPT→ROLE)命中即止;
 * delegate_to 代理人在代理有效期内替换原处理人;valid_from/valid_to 配置有效期。</p>
 * <p>现状说明:AssigneeController/NodeAssigneeResolver 采用 JdbcTemplate 直查,
 * 本实体为类型安全读写规范(映射公共字段/代理/有效期),供配置侧迁移与统一校验使用,
 * 不改变既有解析链路(已验证功能保持不动)。</p>
 */
@Data
@TableName("ccr_node_assignee")
public class CcrNodeAssignee {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    /** 流程定义key(Warm-Flow),空=适用该节点所有流程 */
    private String flowKey;

    /** 节点编码:BRANCH_MANAGER/DEPT_GENERAL_MANAGER/VICE_PRESIDENT/SIX_PEOPLE_GROUP/PRESIDENT */
    private String nodeCode;

    /** 指派类型:PERSON按人/GROUP按组(角色集合,逗号分隔)/DEPT按机构/ROLE按角色兜底 */
    private String assigneeType;

    /** 工号/组编码/机构org_code/角色码 */
    private String assigneeCode;

    /** AND需全员/OR任一(默认) */
    private String relation;

    /** 代理人工号(暂代) */
    private String delegateTo;

    /** 代理有效期起,空=立即 */
    private LocalDateTime delegateValidFrom;

    /** 代理有效期止,空=长期 */
    private LocalDateTime delegateValidTo;

    /** 配置有效期起,空=长期 */
    private LocalDate validFrom;

    /** 配置有效期止,空=长期 */
    private LocalDate validTo;

    /** ACTIVE/INACTIVE */
    private String status;

    private String remark;

    /** 乐观锁版本 */
    private Integer versionNo;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;

    private String delFlag;
}
