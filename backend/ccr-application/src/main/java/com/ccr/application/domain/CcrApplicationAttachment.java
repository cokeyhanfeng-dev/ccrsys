package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 申请附件(材料附件步骤上传,§7.1 步骤6)
 * 演示期内容直接落库(MEDIUMBLOB);生产建议替换为对象存储+密钥管理
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_application_attachment")
public class CcrApplicationAttachment extends BaseEntity {

    /** 申请主键 */
    private Long applicationId;

    /** 文件名 */
    private String fileName;

    /** 文件大小(字节) */
    private Long fileSize;

    /** 内容类型(MIME) */
    private String fileType;

    /** 文件内容 */
    @TableField(select = false)
    private byte[] content;
}
