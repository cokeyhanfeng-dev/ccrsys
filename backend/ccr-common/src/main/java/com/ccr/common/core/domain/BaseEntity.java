package com.ccr.common.core.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务表公共字段(设计文档 V1.0 附录 A.1)
 */
@Data
public class BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户标识,默认本行租户 */
    private String tenantId;

    /** 表内唯一业务编号(不使用主键对外交换) */
    @TableField(fill = FieldFill.INSERT)
    private String businessNo;

    /** 数据归属机构 */
    @TableField(fill = FieldFill.INSERT)
    private Long orgId;

    /** 业务状态编码(业务显式赋值优先,未赋值兜底 ACTIVE) */
    @TableField(fill = FieldFill.INSERT)
    private String status;

    /** 乐观锁版本号(从 1 开始) */
    @Version
    private Integer versionNo;

    /** 创建部门 */
    @TableField(fill = FieldFill.INSERT)
    private Long createDept;

    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 最后修改人 */
    @TableField(fill = FieldFill.UPDATE)
    private Long updateBy;

    /** 最后修改时间 */
    @TableField(fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标识(0否 1是;审批/投票/决议/快照表代码层禁止物理删除) */
    @TableLogic
    private String delFlag;
}
