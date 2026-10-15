package com.robot.system.user.controller.inner;

import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.common.core.domain.Result;
import com.robot.system.user.service.UserSupplyService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户内部接口（/inner/**：仅供服务间 Feign 调用；网关已屏蔽外部访问）。 */
@RestController
@RequiredArgsConstructor
public class UserInnerController {

    private final UserSupplyService userSupplyService;

    @GetMapping("/inner/user/by-username")
    @Operation(summary = "内部按登录名供给认证用户")
    public Result<RemoteUserDTO> getByUsername(@RequestParam("username") String username) {
        return Result.success(userSupplyService.findAuthenticationUserByUsername(username));
    }

    @GetMapping("/inner/user/{userId}")
    @Operation(summary = "内部按账号ID供给认证用户")
    public Result<RemoteUserDTO> getById(@PathVariable("userId") String userId) {
        return Result.success(userSupplyService.findAuthenticationUserById(userId));
    }

    @GetMapping("/inner/user/{userId}/contexts")
    @Operation(summary = "内部供给用户可登录公司上下文")
    public Result<List<RemoteUserContextDTO>> getContexts(@PathVariable("userId") String userId) {
        return Result.success(userSupplyService.listLoginContexts(userId));
    }
}
