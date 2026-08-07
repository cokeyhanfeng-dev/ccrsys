package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关联人绑定(§10.3.21 ccr_relation):一个关联人(证件号)全行唯一绑定一个客户/集团。
 * 对公 USCC(统一社会信用代码)/对私 ID_CARD(身份证);录入即绑定、暂不支持解绑。
 */
@Data
@TableName("ccr_relation")
public class CcrRelation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    /** 数据归属机构 */
    private Long orgId;

    private String status;

    private Integer versionNo;

    private Long createDept;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;

    private String delFlag;

    /** 证件类型:USCC对公(统一社会信用代码)/ID_CARD对私(身份证号) */
    private String certType;

    /** 证件号(必填,全行唯一判重键) */
    private String certNo;

    /** 姓名/企业名称 */
    private String relationName;

    /** 关系说明 */
    private String relationType;

    /** 绑定客户号(单户场景=申请主客户;集团场景为空) */
    private String customerNo;

    /** 集团客户编号(集团场景绑定对象;单户场景为空) */
    private String groupNo;

    /** 绑定来源申请号(留痕) */
    private String bindApplicationNo;

    /** MANUAL手工/MATCH后台匹配 */
    private String source;

    /** 绑定时间 */
    private LocalDateTime bindTime;
}
