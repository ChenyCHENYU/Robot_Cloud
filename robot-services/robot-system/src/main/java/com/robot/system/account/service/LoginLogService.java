package com.robot.system.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.mybatis.tenant.TenantQueryScope;
import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.exception.InnerAccessDeniedException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.account.mapper.SysLoginLogMapper;
import com.robot.system.api.entity.SysLoginLog;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 可信认证服务的登录事件打点；身份校验失败不能进入尽力而为的写入分支。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LoginLogService {
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_USERNAME_LENGTH = 30;
    private static final int MAX_IP_LENGTH = 45;
    private static final int MAX_AGENT_LENGTH = 500;

    private final SysLoginLogMapper loginLogMapper;

    /**
     * 可信队列只在持久化成功后确认；重复 eventId 幂等，失败留给上游重试。
     *
     * @param dto 可信认证服务投递的登录事件
     */
    public void recordLogin(RemoteLoginLogDTO dto) {
        InnerTrustContext context = AuthUtil.requireTrustedService();
        validatePayload(dto);
        validateDelegate(dto, context);
        String eventId = dto.getEventId();
        ServiceAssert.isTrue(
                eventId == null
                        || eventId.matches("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"),
                ResultCode.PARAM_INVALID);
        persistDelivery(dto, eventId);
    }

    private static void validatePayload(RemoteLoginLogDTO dto) {
        ServiceAssert.notNull(dto, ResultCode.PARAM_INVALID);
        ServiceAssert.isTrue(
                length(dto.getUserId(), MAX_ID_LENGTH)
                        && length(dto.getCompanyId(), MAX_ID_LENGTH)
                        && length(dto.getUsername(), MAX_USERNAME_LENGTH)
                        && length(dto.getIp(), MAX_IP_LENGTH)
                        && length(dto.getUserAgent(), MAX_AGENT_LENGTH)
                        && (dto.getStatus() == null || dto.getStatus() == 0 || dto.getStatus() == 1)
                        && (dto.getLoginType() == null
                                || java.util.Set.of(
                                                "login", "activate", "switch", "refresh", "logout")
                                        .contains(dto.getLoginType())),
                ResultCode.PARAM_INVALID);
    }

    private static void validateDelegate(RemoteLoginLogDTO dto, InnerTrustContext context) {
        if (context.hasDelegate()) {
            AuthUtil.requireTrustedUser(dto.getUserId(), dto.getCompanyId());
            if (context.getUsername() != null
                    && !Objects.equals(context.getUsername(), dto.getUsername())) {
                throw new InnerAccessDeniedException();
            }
        }
    }

    private void persistDelivery(RemoteLoginLogDTO dto, String eventId) {
        try {
            ServiceAssert.isTrue(
                    loginLogMapper.insert(toEntry(dto, eventId)) == 1, ResultCode.FAILED);
        } catch (DuplicateKeyException error) {
            // 仅证实同一已持久化投递身份才确认重复；其他约束异常必须传播。
            Long count =
                    eventId == null
                            ? 0L
                            : TenantQueryScope.withoutTenantLine(
                                    () ->
                                            loginLogMapper.selectCount(
                                                    new LambdaQueryWrapper<SysLoginLog>()
                                                            .eq(SysLoginLog::getEventId, eventId)));
            if (count != 1) {
                throw error;
            }
        }
    }

    private static SysLoginLog toEntry(RemoteLoginLogDTO dto, String eventId) {
        SysLoginLog entry = new SysLoginLog();
        entry.setEventId(eventId);
        entry.setUserId(dto.getUserId());
        entry.setUsername(dto.getUsername());
        entry.setCompanyId(dto.getCompanyId());
        entry.setLoginType(dto.getLoginType() == null ? "login" : dto.getLoginType());
        entry.setStatusFlag(dto.getStatus() == null ? Integer.valueOf(1) : dto.getStatus());
        entry.setIp(dto.getIp());
        entry.setUserAgent(dto.getUserAgent());
        entry.setLoginTime(LocalDateTime.now(ZoneOffset.UTC));
        return entry;
    }

    private static boolean length(String value, int maximum) {
        return value == null || value.length() <= maximum;
    }
}
