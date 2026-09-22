package com.ccr.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ccr.admin.system.dto.OnlineUserQuery;
import com.ccr.admin.system.service.OnlineUserService;
import com.ccr.common.core.domain.R;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 管理员只读在线清单，不返回凭证，不提供强制下线操作。 */
@RestController
@RequestMapping("/system/online-users")
@SaCheckRole("admin")
@RequiredArgsConstructor
public class OnlineUserController {
    private final OnlineUserService service;

    @GetMapping
    public R<OnlineUserService.Result> list(@Valid @ModelAttribute OnlineUserQuery query, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return R.ok(service.list(query));
    }
}
