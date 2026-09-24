package com.ccr.admin.system.controller;
import com.ccr.admin.system.domain.CcrSysMenu;
import com.ccr.admin.system.dto.MenuSaveRequest;
import com.ccr.admin.system.service.MenuService;
import com.ccr.common.core.domain.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@cn.dev33.satoken.annotation.SaCheckRole("admin")
@RestController
@RequestMapping("/system/menus")
@RequiredArgsConstructor
public class SysMenuController {
    private final MenuService service;
    @GetMapping public R<List<CcrSysMenu>> list(){return R.ok(service.list());}
    @PostMapping public R<Void> create(@Valid @RequestBody MenuSaveRequest request){service.save(null,request);return R.ok();}
    @PutMapping("/{id}") public R<Void> update(@PathVariable Long id,@Valid @RequestBody MenuSaveRequest request){service.save(id,request);return R.ok();}
    @DeleteMapping("/{id}") public R<Void> delete(@PathVariable Long id){service.delete(id);return R.ok();}
}
