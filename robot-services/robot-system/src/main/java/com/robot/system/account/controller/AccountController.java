package com.robot.system.account.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.account.mapper.SysLoginLogMapper;
import com.robot.system.api.entity.SysLoginLog;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.vo.account.AccountProfileVO;
import com.robot.system.api.vo.account.ActivityLogVO;
import com.robot.system.api.vo.account.LoginRecordVO;
import com.robot.system.api.vo.account.SecuritySettingVO;
import com.robot.system.mapper.SysPermissionAuditLogMapper;
import com.robot.system.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serial;
import java.io.Serializable;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 个人账户自助接口（前端 /account/** 契约；只操作当前登录用户自身数据）。
 */
@Tag(name = "个人账户")
@RestController
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserService userService;
    private final SysLoginLogMapper loginLogMapper;
    private final SysPermissionAuditLogMapper auditLogMapper;

    @Operation(summary = "个人资料")
    @GetMapping("/profile")
    public Result<AccountProfileVO> profile() {
        String userId = AuthUtil.getLoginUserId();
        ServiceAssert.notBlank(userId, ResultCode.UNAUTHORIZED);
        SysUser user = ServiceAssert.notNull(userService.getById(userId),
                ResultCode.USER_NOT_FOUND);

        SysLoginLog lastLogin = loginLogMapper.selectList(
                        new LambdaQueryWrapper<SysLoginLog>()
                                .eq(SysLoginLog::getUserId, userId)
                                .eq(SysLoginLog::getStatusFlag, 1)
                                .eq(SysLoginLog::getLoginType, "login")
                                .orderByDesc(SysLoginLog::getLoginTime)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);

        return Result.success(new AccountProfileVO(
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getPhone(),
                "",
                "",
                AuthUtil.isSuperAdmin() ? "超级管理员" : "普通用户",
                user.getDepartmentId(),
                user.getCreateDateTime() == null ? "" : TIME.format(user.getCreateDateTime()),
                lastLogin == null ? "" : TIME.format(lastLogin.getLoginTime()),
                lastLogin == null || lastLogin.getIp() == null ? "" : lastLogin.getIp()));
    }

    @Operation(summary = "更新个人资料")
    @PutMapping("/profile")
    public Result<Void> updateProfile(@RequestBody ProfileUpdateRequest request) {
        userService.updateOwnProfile(AuthUtil.getLoginUserId(),
                request.getNickname(), request.getEmail(), request.getPhone());
        return Result.success();
    }

    @Operation(summary = "修改密码（校验原密码）")
    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody PasswordChangeRequest request) {
        userService.changeOwnPassword(AuthUtil.getLoginUserId(),
                request.getOldPassword(), request.getNewPassword());
        return Result.success();
    }

    @Operation(summary = "登录记录（最近 20 条）")
    @GetMapping("/login-records")
    public Result<List<LoginRecordVO>> loginRecords() {
        String userId = AuthUtil.getLoginUserId();
        ServiceAssert.notBlank(userId, ResultCode.UNAUTHORIZED);
        return Result.success(loginLogMapper.selectList(
                        new LambdaQueryWrapper<SysLoginLog>()
                                .eq(SysLoginLog::getUserId, userId)
                                .orderByDesc(SysLoginLog::getLoginTime)
                                .last("LIMIT 20"))
                .stream().map(entry -> new LoginRecordVO(
                        entry.getId(),
                        TIME.format(entry.getLoginTime()),
                        entry.getIp() == null ? "" : entry.getIp(),
                        "",
                        entry.getUserAgent() == null ? "" : deviceOf(entry.getUserAgent()),
                        entry.getUserAgent() == null ? "" : browserOf(entry.getUserAgent()),
                        entry.getStatusFlag() != null && entry.getStatusFlag() == 1
                                ? "success" : "failed"))
                .toList());
    }

    @Operation(summary = "安全设置项")
    @GetMapping("/security-settings")
    public Result<List<SecuritySettingVO>> securitySettings() {
        return Result.success(List.of(
                new SecuritySettingVO("password-strength", "密码强度策略",
                        "长度 6~32，BCrypt 摘要存储", "shield", true),
                new SecuritySettingVO("session-timeout", "会话自动过期",
                        "访问令牌 30 分钟无感刷新", "timer", true),
                new SecuritySettingVO("refresh-rotation", "刷新令牌轮换",
                        "每次刷新旧令牌立即作废", "refresh", true),
                new SecuritySettingVO("two-factor", "双因素认证",
                        "登录二次验证（规划中）", "lock", false)));
    }

    @Operation(summary = "操作动态（映射权限审计日志，最近 20 条）")
    @GetMapping("/activity-logs")
    public Result<List<ActivityLogVO>> activityLogs() {
        return Result.success(auditLogMapper.selectList(
                        new LambdaQueryWrapper<SysPermissionAuditLog>()
                                .eq(SysPermissionAuditLog::getActorUserNo,
                                        AuthUtil.getLoginUsername())
                                .eq(SysPermissionAuditLog::getDeleteFlag,
                                        RobotConstants.DELETE_FLAG_ACTIVE)
                                .orderByDesc(SysPermissionAuditLog::getCreateDateTime)
                                .last("LIMIT 20"))
                .stream().map(entry -> new ActivityLogVO(
                        entry.getId(),
                        TIME.format(entry.getCreateDateTime()),
                        actionTypeOf(entry.getAction()),
                        "sys",
                        entry.getAction() + " " + (entry.getTarget() == null ? "" : entry.getTarget()),
                        "",
                        "success"))
                .toList());
    }

    private static String deviceOf(String userAgent) {
        return userAgent.contains("Mobile") ? "移动端" : "桌面端";
    }

    private static String browserOf(String userAgent) {
        if (userAgent.contains("Edg/")) {
            return "Edge";
        }
        if (userAgent.contains("Chrome/")) {
            return "Chrome";
        }
        if (userAgent.contains("Firefox/")) {
            return "Firefox";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari";
        }
        return "未知";
    }

    private static String actionTypeOf(String action) {
        if (action == null) {
            return "other";
        }
        if (action.contains("save") || action.contains("assign") || action.contains("grant")) {
            return "create";
        }
        if (action.contains("update") || action.contains("rebind") || action.contains("move")) {
            return "update";
        }
        if (action.contains("delete")) {
            return "delete";
        }
        return "other";
    }

    @Data
    @NoArgsConstructor
    public static class ProfileUpdateRequest implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String username;
        private String nickname;
        private String email;
        private String phone;
        private String bio;
        private String avatar;
    }

    @Data
    @NoArgsConstructor
    public static class PasswordChangeRequest implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        @NotBlank(message = "原密码不能为空")
        private String oldPassword;
        @NotBlank(message = "新密码不能为空")
        private String newPassword;
    }
}
