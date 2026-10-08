package com.robot.system.role.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.Result;
import com.robot.system.api.dto.role.RolePageQuery;
import com.robot.system.api.dto.role.RoleSaveRequest;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.vo.permission.DataScopeVO;
import com.robot.system.api.vo.role.RoleOptionVO;
import com.robot.system.api.vo.role.RoleUserVO;
import com.robot.system.api.vo.role.RoleVO;
import com.robot.system.role.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import java.util.Map;
/** 角色管理接口（对齐 Robot_Admin 前端 /sys/roles 契约）。 */
@Tag(name = "角色管理")
@RestController
@RequestMapping("/sys/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "角色分页列表")
    @SaCheckPermission("sys:role:list")
    @GetMapping
    public Result<PageResult<RoleVO>> list(RolePageQuery query) {
        return Result.success(roleService.queryPage(query));
    }

    @Operation(summary = "角色下拉选项")
    @GetMapping("/options")
    public Result<List<RoleOptionVO>> options() {
        return Result.success(roleService.options());
    }

    @Operation(summary = "角色详情")
    @SaCheckPermission("sys:role:query")
    @GetMapping("/{id}")
    public Result<RoleVO> detail(@PathVariable String id) {
        return Result.success(roleService.getDetail(id));
    }

    @Operation(summary = "角色成员清单")
    @SaCheckPermission("sys:role:query")
    @GetMapping("/{id}/users")
    public Result<List<RoleUserVO>> roleUsers(@PathVariable String id) {
        return Result.success(roleService.roleUsers(id));
    }

    @Operation(summary = "角色数据范围")
    @SaCheckPermission("sys:role:query")
    @GetMapping("/{id}/data-scopes")
    public Result<List<DataScopeVO>> dataScopes(@PathVariable String id) {
        return Result.success(roleService.dataScopes(id));
    }

    @Operation(summary = "角色临时授权清单")
    @SaCheckPermission("sys:role:query")
    @GetMapping("/{id}/temp-authorizations")
    public Result<List<SysTempAuthorization>> tempAuthorizations(@PathVariable String id) {
        return Result.success(roleService.tempAuthorizations(id));
    }

    @Operation(summary = "新增角色")
    @SaCheckPermission("sys:role:save")
    @PostMapping
    public Result<String> create(@Valid @RequestBody RoleSaveRequest request) {
        return Result.success(roleService.create(request));
    }

    @Operation(summary = "编辑角色")
    @SaCheckPermission("sys:role:update")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id,
                               @Valid @RequestBody RoleSaveRequest request) {
        request.setId(id);
        roleService.update(id, request);
        return Result.success();
    }

    @Operation(summary = "角色权限批量授权（前端 /sys/roles/{id}/permissions）")
    @SaCheckPermission("sys:role:update")
    @PutMapping("/{id}/permissions")
    public Result<Void> assignPermissions(@PathVariable String id,
                                          @RequestBody Map<String, List<String>> body) {
        roleService.assignPermissions(id, body.get("permissionIds"));
        return Result.success();
    }

    @Operation(summary = "删除角色（有用户绑定拦截）")
    @SaCheckPermission("sys:role:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        roleService.delete(id);
        return Result.success();
    }

}
