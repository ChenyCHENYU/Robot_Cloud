package com.robot.system.account.controller;

import com.robot.common.core.domain.Result;
import com.robot.system.account.service.AccountService;
import com.robot.system.api.dto.account.PasswordChangeRequest;
import com.robot.system.api.dto.account.ProfileUpdateRequest;
import com.robot.system.api.vo.account.AccountProfileVO;
import com.robot.system.api.vo.account.ActivityLogVO;
import com.robot.system.api.vo.account.LoginRecordVO;
import com.robot.system.api.vo.account.SecuritySettingVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录账户的自助查询与写入接口。 */
@Tag(name = "个人账户")
@RestController
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountController {
    private final AccountService service;

    @Operation(summary = "个人资料")
    @GetMapping("/profile")
    public Result<AccountProfileVO> profile() {
        return Result.success(service.profile());
    }

    @Operation(summary = "更新个人资料")
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        service.updateProfile(request);
        return Result.success();
    }

    @Operation(summary = "修改密码（校验原密码）")
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        service.changePassword(request);
        return Result.success();
    }

    @Operation(summary = "登录记录（最近 20 条）")
    @GetMapping("/login-records")
    public Result<List<LoginRecordVO>> loginRecords() {
        return Result.success(service.loginRecords());
    }

    @Operation(summary = "安全设置项")
    @GetMapping("/security-settings")
    public Result<List<SecuritySettingVO>> securitySettings() {
        return Result.success(service.securitySettings());
    }

    @Operation(summary = "操作动态（当前公司，最近 20 条）")
    @GetMapping("/activity-logs")
    public Result<List<ActivityLogVO>> activityLogs() {
        return Result.success(service.activityLogs());
    }
}
