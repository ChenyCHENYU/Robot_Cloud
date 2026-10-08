package com.robot.system.user.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.Result;
import com.robot.system.api.dto.user.UserPageQuery;
import com.robot.system.api.dto.user.UserSaveRequest;
import com.robot.system.api.vo.user.UserVO;
import com.robot.system.user.service.UserService;
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

import java.util.Map;

/**
 * 用户管理接口（对齐 Robot_Admin 前端 /sys/users 契约：GET 分页 + REST 风格增删改）。
 */
@Tag(name = "用户管理")
@RestController
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

    @Operation(summary = "删除用户（软删）")
    @SaCheckPermission("sys:user:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        userService.delete(id);
        return Result.success();
    }

    @Operation(summary = "管理员重置密码")
    @SaCheckPermission("sys:user:resetPassword")
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable String id,
                                      @RequestBody Map<String, String> body) {
        userService.resetPassword(id, body.get("password"));
        return Result.success();
    }

    @Operation(summary = "启用/停用用户")
    @SaCheckPermission("sys:user:update")
    @PutMapping("/{id}/status")
    public Result<Void> toggleStatus(@PathVariable String id,
                                     @RequestBody Map<String, Integer> body) {
        userService.toggleStatus(id, body.get("status"));
        return Result.success();
    }
}
