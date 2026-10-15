package com.robot.system.permission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.Result;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.role.TempAuthorizationCreateRequest;
import com.robot.system.api.dto.role.TempAuthorizationRevokeRequest;
import com.robot.system.api.dto.role.TemporaryAuthorizationPageQuery;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.api.entity.SysPermissionConstraint;
import com.robot.system.api.vo.permission.PermissionItem;
import com.robot.system.api.vo.permission.PermissionNode;
import com.robot.system.api.vo.role.TemporaryAuthorizationVO;
import com.robot.system.menu.service.MenuQueryService;
import com.robot.system.permission.service.DataScopeService;
import com.robot.system.permission.service.PermissionGovernanceQueryService;
import com.robot.system.role.service.RoleQueryService;
import com.robot.system.role.service.TemporaryAuthorizationCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 权限治理接口（前端 permission-governance.ts 契约）： 权限清单 / 权限树 / 约束规则 / 审计日志 / 临时授权（治理页路径形态）。 */
@Tag(name = "权限治理")
@RestController
@RequestMapping("/sys")
@RequiredArgsConstructor
public class PermissionGovernanceController {

    private final MenuQueryService menuQueryService;
    private final DataScopeService dataScopeService;
    private final RoleQueryService roleQueries;
    private final TemporaryAuthorizationCommandService temporaryCommands;
    private final PermissionGovernanceQueryService governanceQueries;

    @Operation(summary = "权限码清单")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/permissions")
    public Result<List<PermissionItem>> list() {
        return Result.success(menuQueryService.permissionList());
    }

    @Operation(summary = "权限树（菜单 + 按钮）")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/permissions/tree")
    public Result<List<PermissionNode>> tree() {
        return Result.success(menuQueryService.permissionTree());
    }

    @Operation(summary = "数据权限配置（当前公司角色范围汇总）")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/data-permissions")
    public Result<List<com.robot.system.api.vo.permission.DataScopeVO>> dataPermissions() {
        return Result.success(
                dataScopeService.scopesOfUser(
                        AuthUtil.getLoginUserId(), AuthUtil.getLoginCompanyId()));
    }

    @Operation(summary = "权限约束规则")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/permission-constraints")
    public Result<List<SysPermissionConstraint>> constraints() {
        return Result.success(governanceQueries.constraints());
    }

    @Operation(summary = "权限审计日志（只读，最近 500 条）")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/permission-audit-logs")
    public Result<List<SysPermissionAuditLog>> auditLogs() {
        return Result.success(governanceQueries.auditLogs());
    }

    @Operation(summary = "检索公司历史审计")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/permission-audit-logs/page")
    public Result<PageResult<com.robot.system.api.vo.permission.PermissionAuditVO>> auditPage(
            @Valid com.robot.system.api.dto.role.PermissionAuditPageQuery query) {
        return Result.success(governanceQueries.auditPage(query));
    }

    @Operation(summary = "全局临时授权清单（治理页契约路径）")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/temp-authorizations")
    public Result<List<TemporaryAuthorizationVO>> tempAuthorizations() {
        return Result.success(roleQueries.allTempAuthorizations());
    }

    @Operation(summary = "临时角色授予分页")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/temp-authorizations/page")
    public Result<PageResult<TemporaryAuthorizationVO>> tempAuthorizationPage(
            @Valid TemporaryAuthorizationPageQuery query) {
        return Result.success(roleQueries.temporaryAuthorizationPage(query));
    }

    @Operation(summary = "治理能力范围")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/governance-capabilities")
    public Result<java.util.Map<String, java.util.Map<String, Boolean>>> capabilities() {
        var readOnly = java.util.Map.of("read", true, "write", false);
        return Result.success(
                java.util.Map.of(
                        "dataScope",
                        readOnly,
                        "fieldPermission",
                        java.util.Map.of("read", false, "write", false),
                        "constraint",
                        readOnly,
                        "audit",
                        readOnly,
                        "temporaryRoleGrant",
                        java.util.Map.of("read", true, "write", true, "revoke", true)));
    }

    @Operation(summary = "精准撤回临时授权")
    @SaCheckPermission("sys:permission:tempAuthorize")
    @PutMapping("/temp-authorizations/{id}/revoke")
    public Result<Void> revoke(
            @PathVariable String id, @Valid @RequestBody TempAuthorizationRevokeRequest request) {
        temporaryCommands.revoke(id, request.expectedRevision());
        return Result.success();
    }

    @Operation(summary = "创建临时授权")
    @SaCheckPermission("sys:permission:tempAuthorize")
    @PostMapping("/temp-authorizations")
    public Result<String> createTempAuthorization(
            @Valid @RequestBody TempAuthorizationCreateRequest request) {
        return Result.success(temporaryCommands.createTempAuthorization(request));
    }
}
