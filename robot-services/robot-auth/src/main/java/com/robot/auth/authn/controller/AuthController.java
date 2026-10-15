package com.robot.auth.authn.controller;

import com.robot.api.system.dto.RemoteMenuRouteDTO;
import com.robot.auth.authn.dto.ActivateContextRequest;
import com.robot.auth.authn.dto.LoginCompaniesRequest;
import com.robot.auth.authn.dto.LoginCompaniesResponse;
import com.robot.auth.authn.dto.LoginRequest;
import com.robot.auth.authn.dto.LoginResponse;
import com.robot.auth.authn.dto.RefreshTokenRequest;
import com.robot.auth.authn.dto.RefreshTokenResponse;
import com.robot.auth.authn.dto.SwitchContextRequest;
import com.robot.auth.authn.service.AuthService;
import com.robot.common.core.domain.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口：路径与响应对齐 Robot_Admin 前端 auth 契约（src/api/auth.contract.ts）。 */
@Tag(name = "认证授权")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录（凭据验证）")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @Operation(summary = "凭据验证后发现可选公司（不创建会话）")
    @PostMapping("/login-companies")
    public Result<LoginCompaniesResponse> loginCompanies(
            @Valid @RequestBody LoginCompaniesRequest request) {
        return Result.success(authService.getLoginCompanies(request));
    }

    @Operation(summary = "激活企业上下文（loginTicket 换取完整会话）")
    @PostMapping("/context/activate")
    public Result<LoginResponse> activateContext(
            @Valid @RequestBody ActivateContextRequest request) {
        return Result.success(authService.activateContext(request));
    }

    @Operation(summary = "切换企业上下文（需有效登录态）")
    @PostMapping("/context/switch")
    public Result<LoginResponse> switchContext(@Valid @RequestBody SwitchContextRequest request) {
        return Result.success(authService.switchContext(request));
    }

    @Operation(summary = "刷新访问令牌（双 Token 轮换）")
    @PostMapping("/refresh-token")
    public Result<RefreshTokenResponse> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request) {
        return Result.success(authService.refreshToken(request.getRefreshToken()));
    }

    @Operation(summary = "当前用户动态菜单")
    @GetMapping("/menu-list")
    public Result<List<RemoteMenuRouteDTO>> menuList() {
        return Result.success(authService.getCurrentMenus());
    }

    @Operation(summary = "当前用户按钮权限映射")
    @GetMapping("/button-list")
    public Result<Map<String, List<String>>> buttonList() {
        return Result.success(authService.getCurrentButtons());
    }

    @Operation(summary = "当前用户数据权限")
    @GetMapping("/data-permission")
    public Result<List<com.robot.api.system.dto.RemoteDataScopeDTO>> dataPermission() {
        return Result.success(authService.getCurrentDataScopes());
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }
}
