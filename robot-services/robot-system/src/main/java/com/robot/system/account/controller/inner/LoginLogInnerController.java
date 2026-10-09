package com.robot.system.account.controller.inner;

import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.common.core.domain.Result;
import com.robot.system.account.service.LoginLogService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录日志内部接口（/inner/**：认证服务打点写入，尽力而为语义）。
 */
@RestController
@RequiredArgsConstructor
public class LoginLogInnerController {

    private final LoginLogService loginLogService;

    @PostMapping("/inner/log/login-record")
    @Operation(summary = "内部记录认证登录事件")
    public Result<Void> recordLogin(@RequestBody RemoteLoginLogDTO dto) {
        loginLogService.recordLogin(dto);
        return Result.success();
    }
}
