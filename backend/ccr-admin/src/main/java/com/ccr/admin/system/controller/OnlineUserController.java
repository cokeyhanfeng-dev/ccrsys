package com.ccr.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ccr.admin.system.dto.OnlineUserQuery;
import com.ccr.admin.system.service.OnlineUserService;
import com.ccr.common.core.domain.R;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 管理员在线清单及账号级强制下线，不返回凭证。 */
@org.springframework.validation.annotation.Validated
@RestController
@RequestMapping("/system/online-users")
@SaCheckRole("admin")
@RequiredArgsConstructor
public class OnlineUserController {
    private final OnlineUserService service;
    private final com.ccr.admin.system.service.OnlineUserKickoutService kickoutService;

    @PostMapping("/{userId}/kickout")
    public R<Void> kickout(@PathVariable @jakarta.validation.constraints.Positive Long userId,
                          HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        kickoutService.kickout(userId);
        return R.ok();
    }


    @GetMapping
    public R<OnlineUserService.Result> list(@Valid @ModelAttribute OnlineUserQuery query, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return R.ok(service.list(query));
    }
}
