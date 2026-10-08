package com.robot.system.permission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.Result;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.role.TempAuthorizationCreateRequest;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.api.entity.SysPermissionConstraint;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.mapper.SysPermissionAuditLogMapper;
import com.robot.system.mapper.SysPermissionConstraintMapper;
import com.robot.system.api.vo.permission.PermissionItem;
import com.robot.system.api.vo.permission.PermissionNode;
import com.robot.system.menu.service.MenuQueryService;
import com.robot.system.permission.service.DataScopeService;
import com.robot.system.role.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限治理接口（前端 permission-governance.ts 契约）：
 * 权限清单 / 权限树 / 约束规则 / 审计日志 / 临时授权（治理页路径形态）。
 */
@Tag(name = "权限治理")
@RestController
@RequestMapping("/sys")
@RequiredArgsConstructor
public class PermissionGovernanceController {

    private final MenuQueryService menuQueryService;
    private final DataScopeService dataScopeService;
    private final RoleService roleService;
    private final SysPermissionConstraintMapper constraintMapper;
    private final SysPermissionAuditLogMapper auditLogMapper;

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
        return Result.success(dataScopeService.scopesOfUser(
                AuthUtil.getLoginUserId(), AuthUtil.getLoginCompanyId()));
    }

    @Operation(summary = "权限约束规则")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/permission-constraints")
    public Result<List<SysPermissionConstraint>> constraints() {
        return Result.success(constraintMapper.selectList(
                new LambdaQueryWrapper<SysPermissionConstraint>()
                        .eq(SysPermissionConstraint::getDeleteFlag,
                                RobotConstants.DELETE_FLAG_ACTIVE)
                        .orderByAsc(SysPermissionConstraint::getRuleName)));
    }

    @Operation(summary = "权限审计日志（只读，最近 500 条）")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/permission-audit-logs")
    public Result<List<SysPermissionAuditLog>> auditLogs() {
        return Result.success(auditLogMapper.selectList(
                new LambdaQueryWrapper<SysPermissionAuditLog>()
                        .orderByDesc(SysPermissionAuditLog::getCreateDateTime)
                        .last("LIMIT 500")));
    }

    @Operation(summary = "全局临时授权清单（治理页契约路径）")
    @SaCheckPermission("sys:permission:list")
    @GetMapping("/temp-authorizations")
    public Result<List<SysTempAuthorization>> tempAuthorizations() {
        return Result.success(roleService.allTempAuthorizations());
    }

    @Operation(summary = "创建临时授权")
    @SaCheckPermission("sys:permission:tempAuthorize")
    @PostMapping("/temp-authorizations")
    public Result<String> createTempAuthorization(
            @Valid @RequestBody TempAuthorizationCreateRequest request) {
        return Result.success(roleService.createTempAuthorization(request));
    }
}
