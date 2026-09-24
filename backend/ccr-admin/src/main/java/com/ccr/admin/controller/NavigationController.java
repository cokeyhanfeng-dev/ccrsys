package com.ccr.admin.controller;
import cn.dev33.satoken.stp.StpUtil;
import com.ccr.admin.system.domain.CcrSysMenu;
import com.ccr.admin.system.service.MenuService;
import com.ccr.common.core.domain.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class NavigationController {
    private final MenuService service;
    @GetMapping("/auth/menus")
    public R<List<CcrSysMenu>> current(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return R.ok(service.current(StpUtil.getLoginIdAsLong()));
    }
}
