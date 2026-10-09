package com.robot.system.user.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.Result;
import com.robot.system.api.dto.user.UserPageQuery;
import com.robot.system.api.dto.user.UserSaveRequest;
import com.robot.system.api.dto.user.UserStatusRequest;
import com.robot.system.api.dto.user.UserPasswordResetRequest;
import com.robot.system.api.vo.user.UserVO;
import com.robot.system.user.service.UserService;
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

/**
 * 用户管理接口；成员动作使用客户端版本，账号与成员版本分别取自详情。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Tag(name = "用户管理")
@RestController
@Validated
@RequestMapping("/sys/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "用户分页列表")
    @SaCheckPermission("sys:user:list")
    @GetMapping
    public Result<PageResult<UserVO>> list(UserPageQuery query) {
        return Result.success(userService.queryPage(query));
    }

    @Operation(summary = "用户详情")
    @SaCheckPermission("sys:user:query")
    @GetMapping("/{id}")
    public Result<UserVO> detail(@PathVariable String id) {
        return Result.success(userService.getDetail(id));
    }

    @Operation(summary = "新增用户")
    @SaCheckPermission("sys:user:save")
    @PostMapping
    public Result<String> create(@Valid @RequestBody UserSaveRequest request) {
        return Result.success(userService.create(request));
    }

    @Operation(summary = "编辑用户")
    @SaCheckPermission("sys:user:update")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id,
                               @Valid @RequestBody UserSaveRequest request) {
        request.setId(id);
        userService.update(id, request);
        return Result.success();
    }

    @Operation(summary = "按成员版本移出当前公司")
    @SaCheckPermission("sys:user:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id,
                               @RequestParam("expectedRevision") @Min(0) Integer expectedRevision) {
        userService.delete(id, expectedRevision);
        return Result.success();
    }

    @Operation(summary = "管理员重置密码")
    @SaCheckPermission("sys:user:resetPassword")
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable String id,
                                      @Valid @RequestBody UserPasswordResetRequest body) {
        userService.resetPassword(id, body);
        return Result.success();
    }

    @Operation(summary = "启用/停用用户")
    @SaCheckPermission("sys:user:update")
    @PutMapping("/{id}/status")
    public Result<Void> toggleStatus(@PathVariable String id,
                                     @Valid @RequestBody UserStatusRequest body) {
        userService.toggleStatus(id, body);
        return Result.success();
    }
}
