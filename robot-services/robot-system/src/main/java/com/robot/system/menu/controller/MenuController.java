package com.robot.system.menu.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.robot.common.core.domain.Result;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.vo.menu.MenuTreeNode;
import com.robot.system.menu.service.MenuQueryService;
import com.robot.system.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 菜单管理接口（对齐 Robot_Admin 前端 /sys/menus 契约）。
 * 新增/编辑同时支持 JSON 与 multipart/form-data（前端 FormData 提交形态）。
 */
@Tag(name = "菜单管理")
@RestController
@RequestMapping("/sys/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;
    private final MenuQueryService menuQueryService;

    @Operation(summary = "菜单树")
    @SaCheckPermission("sys:menu:list")
    @GetMapping
    public Result<List<MenuTreeNode>> tree() {
        return Result.success(menuQueryService.menuTree());
    }

    @Operation(summary = "菜单按钮清单（menuId 过滤）")
    @SaCheckPermission("sys:menu:list")
    @GetMapping("/{menuId}/buttons")
    public Result<List<com.robot.system.api.vo.menu.MenuButtonVO>> menuButtons(
            @PathVariable String menuId) {
        return Result.success(menuQueryService.buttonsOfMenu(menuId));
    }

    @Operation(summary = "新增菜单（JSON）")
    @SaCheckPermission("sys:menu:save")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<String> createJson(@RequestBody SysMenu menu) {
        return Result.success(menuService.createMenu(menu));
    }

    @Operation(summary = "新增菜单（表单/FormData）")
    @SaCheckPermission("sys:menu:save")
    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE,
            MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    public Result<String> createForm(SysMenu menu) {
        return Result.success(menuService.createMenu(menu));
    }

    @Operation(summary = "编辑菜单（JSON）")
    @SaCheckPermission("sys:menu:update")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> updateJson(@PathVariable String id, @RequestBody SysMenu menu) {
        menuService.updateMenu(id, menu);
        return Result.success();
    }

    @Operation(summary = "编辑菜单（表单/FormData）")
    @SaCheckPermission("sys:menu:update")
    @PutMapping(path = "/{id}", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE,
            MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    public Result<Void> updateForm(@PathVariable String id, SysMenu menu) {
        menuService.updateMenu(id, menu);
        return Result.success();
    }

    @Operation(summary = "拖拽排序（targetId + inner/before/after）")
    @SaCheckPermission("sys:menu:update")
    @PutMapping("/{id}/move")
    public Result<Void> move(@PathVariable String id,
                             @RequestBody Map<String, String> body) {
        menuService.moveMenu(id, body.get("targetId"), body.get("position"));
        return Result.success();
    }

    @Operation(summary = "删除菜单（含子菜单拦截）")
    @SaCheckPermission("sys:menu:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        menuService.deleteMenu(id);
        return Result.success();
    }

    @Operation(summary = "新增按钮权限")
    @SaCheckPermission("sys:menu:save")
    @PostMapping("/buttons")
    public Result<String> createButton(@RequestBody SysMenuButton button) {
        return Result.success(menuService.createButton(button));
    }
}
