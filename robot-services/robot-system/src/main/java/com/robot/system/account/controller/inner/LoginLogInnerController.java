package com.robot.system.account.controller.inner;

import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.common.core.domain.Result;
import com.robot.system.account.mapper.SysLoginLogMapper;
import com.robot.system.api.entity.SysLoginLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 登录日志内部接口（/inner/**：认证服务打点写入，尽力而为语义）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LoginLogInnerController {

    private final SysLoginLogMapper loginLogMapper;

    @PostMapping("/inner/log/login-record")
    public Result<Void> recordLogin(@RequestBody RemoteLoginLogDTO dto) {
        try {
            SysLoginLog entry = new SysLoginLog();
            entry.setUserId(dto.getUserId());
            entry.setUsername(dto.getUsername());
            entry.setCompanyId(dto.getCompanyId());
            entry.setLoginType(dto.getLoginType() == null ? "login" : dto.getLoginType());
            entry.setStatusFlag(dto.getStatus() == null ? 1 : dto.getStatus());
            entry.setIp(dto.getIp());
            entry.setUserAgent(dto.getUserAgent());
            entry.setLoginTime(LocalDateTime.now());
            loginLogMapper.insert(entry);
        } catch (Exception e) {
            // 日志打点失败不影响认证主流程（尽力而为）
            log.warn("record login log failed: {}", e.getMessage());
        }
        return Result.success();
    }
}
