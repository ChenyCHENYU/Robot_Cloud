package com.robot.system.menu.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.robot.common.core.domain.Result;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.vo.menu.MenuButtonVO;
import com.robot.system.menu.service.MenuQueryService;
import com.robot.system.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 菜单按钮权限管理（前端 /sys/menu-buttons 契约：GET/POST/PUT/DELETE）。 */
@Tag(name = "菜单按钮")
@RestController
@RequestMapping("/sys/menu-buttons")
@RequiredArgsConstructor
public class MenuButtonController {

    private final MenuService menuService;
    private final MenuQueryService menuQueryService;

    @Operation(summary = "按钮权限清单")
    @SaCheckPermission("sys:menu:list")
    @GetMapping
    public Result<List<MenuButtonVO>> list() {
        return Result.success(menuQueryService.buttonsOfMenu(null));
    }

    @Operation(summary = "新增按钮权限")
    @SaCheckPermission("sys:menu:save")
    @PostMapping
    public Result<String> create(@RequestBody SysMenuButton button) {
        return Result.success(menuService.createButton(button));
    }

    @Operation(summary = "编辑按钮权限")
    @SaCheckPermission("sys:menu:save")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody SysMenuButton button) {
        menuService.updateButton(id, button);
        return Result.success();
    }

    @Operation(summary = "删除按钮权限")
    @SaCheckPermission("sys:menu:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        menuService.deleteButton(id);
        return Result.success();
    }
}
