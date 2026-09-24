package com.ccr.admin.system.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 导航目录和页面菜单，业务操作权限继续由领域服务校验。 */
@Data
@TableName("ccr_sys_menu")
public class CcrSysMenu {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String tenantId;
    private Long parentId;
    private String menuName;
    private String menuType;
    private String path;
    private String icon;
    private String perms;
    private Integer sortNo;
    private String visible;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String delFlag;
}
