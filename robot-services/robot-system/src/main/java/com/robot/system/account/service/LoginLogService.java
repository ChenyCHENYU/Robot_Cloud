package com.robot.system.account.service;

import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.exception.InnerAccessDeniedException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.account.mapper.SysLoginLogMapper;
import com.robot.system.api.entity.SysLoginLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/** 可信认证服务的登录事件打点；身份校验失败不能进入尽力而为的写入分支。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LoginLogService {

    private final SysLoginLogMapper loginLogMapper;

    /** 验证服务身份与可选用户委托后记录登录事件；持久化异常不阻断认证。 */
    public void recordLogin(RemoteLoginLogDTO dto) {
        InnerTrustContext context = AuthUtil.requireTrustedService();
        if (context.hasDelegate()) {
            AuthUtil.requireTrustedUser(dto.getUserId(), dto.getCompanyId());
            if (context.getUsername() != null && !Objects.equals(context.getUsername(), dto.getUsername())) {
                throw new InnerAccessDeniedException();
            }
        }
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
        } catch (Exception error) {
            // 登录打点持久化失败保持既有尽力而为语义；不记录原始消息中的请求或凭据。
            log.warn("record login log failed: {}", error.getClass().getSimpleName());
        }
    }
}
