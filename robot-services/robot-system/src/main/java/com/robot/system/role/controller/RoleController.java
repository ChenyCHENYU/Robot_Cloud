package com.robot.system.role.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.Result;
import com.robot.system.api.dto.role.RolePageQuery;
import com.robot.system.api.dto.role.RoleSaveRequest;
import com.robot.system.api.dto.role.PermissionAssignRequest;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.vo.permission.DataScopeVO;
import com.robot.system.api.vo.role.RoleOptionVO;
import com.robot.system.api.vo.role.RoleUserVO;
import com.robot.system.api.vo.role.RoleVO;
import com.robot.system.role.service.RoleCommandService;
import com.robot.system.role.service.RoleQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色查询与版本受控命令接口（对齐Robot Admin的/sys/roles路径）。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Tag(name = "角色管理")
@RestController
@Validated
@RequestMapping("/sys/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleQueryService roleQueries;
    private final RoleCommandService roleCommands;

    @Operation(summary = "角色分页列表")
    @SaCheckPermission("sys:role:list")
    @GetMapping
    public Result<PageResult<RoleVO>> list(RolePageQuery query) {
        return Result.success(roleQueries.queryPage(query));
    }

    @Operation(summary = "角色下拉选项")
    @GetMapping("/options")
    public Result<List<RoleOptionVO>> options() {
        return Result.success(roleQueries.options());
    }

    @Operation(summary = "角色详情")
    @SaCheckPermission("sys:role:query")
    @GetMapping("/{id}")
    public Result<RoleVO> detail(@PathVariable String id) {
        return Result.success(roleQueries.getDetail(id));
    }

    @Operation(summary = "角色成员清单")
    @SaCheckPermission("sys:role:query")
    @GetMapping("/{id}/users")
    public Result<List<RoleUserVO>> roleUsers(@PathVariable String id) {
        return Result.success(roleQueries.roleUsers(id));
    }

    @Operation(summary = "角色数据范围")
    @SaCheckPermission("sys:role:query")
    @GetMapping("/{id}/data-scopes")
    public Result<List<DataScopeVO>> dataScopes(@PathVariable String id) {
        return Result.success(roleQueries.dataScopes(id));
    }

    @Operation(summary = "角色临时授权清单")
    @SaCheckPermission("sys:role:query")
    @GetMapping("/{id}/temp-authorizations")
    public Result<List<SysTempAuthorization>> tempAuthorizations(@PathVariable String id) {
        return Result.success(roleQueries.tempAuthorizations(id));
    }

    @Operation(summary = "新增角色")
    @SaCheckPermission("sys:role:save")
    @PostMapping
    public Result<String> create(@Valid @RequestBody RoleSaveRequest request) {
        return Result.success(roleCommands.create(request));
    }

    @Operation(summary = "编辑角色")
    @SaCheckPermission("sys:role:update")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id,
                               @Valid @RequestBody RoleSaveRequest request) {
        request.setId(id);
        roleCommands.update(id, request);
        return Result.success();
    }

    @Operation(summary = "角色权限批量授权（前端 /sys/roles/{id}/permissions）")
    @SaCheckPermission("sys:role:update")
    @PutMapping("/{id}/permissions")
    public Result<Void> assignPermissions(@PathVariable String id,
                                          @Valid @RequestBody PermissionAssignRequest body) {
        roleCommands.assignPermissions(id, body.getPermissionIds(), body.getRevision());
        return Result.success();
    }

    @Operation(summary = "删除角色（有用户绑定拦截）")
    @SaCheckPermission("sys:role:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id,
                               @RequestParam("expectedRevision") @Min(0) Integer expectedRevision) {
        roleCommands.delete(id, expectedRevision);
        return Result.success();
    }

}
