package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 借据与担保措施多对多(ccr_note_guarantee_rel)
 * 一笔借据可关联多个担保措施;一个担保措施可覆盖多笔借据
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_note_guarantee_rel")
public class CcrNoteGuaranteeRel extends BaseEntity {

    /** 借据号 */
    private String loanNoteNo;

    /** 担保措施主键 */
    private Long measureId;

    /** 覆盖金额(万元) */
    private BigDecimal coveredAmount;

    /** 币种 */
    private String currency;
}
