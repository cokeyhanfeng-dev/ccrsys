package com.ccr.admin.system.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 在线会话查询，所有条件仅用于管理员列表过滤。 */
@Data
public class OnlineUserQuery {
    @Min(1) private int pageNum = 1;
    @Min(1) @Max(100) private int pageSize = 20;
    @Size(max = 64) private String keyword;
    @Positive private Long orgId;
    @Pattern(regexp = "PC|MOBILE|", message = "终端类型仅支持 PC 或 MOBILE")
    private String client;
}
