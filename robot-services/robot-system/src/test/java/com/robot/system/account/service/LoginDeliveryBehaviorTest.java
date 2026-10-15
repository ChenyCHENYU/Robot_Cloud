package com.robot.system.account.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.exception.InnerAccessDeniedException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.account.mapper.SysLoginLogMapper;
import com.robot.system.api.entity.SysLoginLog;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class LoginDeliveryBehaviorTest {
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_USERNAME_LENGTH = 30;
    private static final int MAX_IP_LENGTH = 45;
    private static final int MAX_REMARK_LENGTH = 500;
    private static final int DELIVERY_ID_LOOKUPS = 3;

    @Test
    void trustedServicePersistsDefaultsAndExplicitFailedEventWithoutAcknowledgingLostInsert() {
        var mapper = mock(SysLoginLogMapper.class);
        var service = new LoginLogService(mapper);
        when(mapper.insert(any(SysLoginLog.class))).thenReturn(1);
        InnerTrustContext.bindVerifiedService(null, null, null);
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::requireTrustedService).thenReturn(InnerTrustContext.current());
            var dto = event();
            dto.setStatus(null);
            dto.setLoginType(null);
            dto.setEventId(null);
            service.recordLogin(dto);
            verify(mapper)
                    .insert(
                            argThat(
                                    (SysLoginLog log) ->
                                            log.getStatusFlag() == 1
                                                    && "login".equals(log.getLoginType())
                                                    && log.getLoginTime() != null));
            dto.setEventId("6c31a250-5271-4685-a71f-16d812034a32");
            dto.setStatus(0);
            dto.setLoginType("refresh");
            service.recordLogin(dto);
            verify(mapper)
                    .insert(
                            argThat(
                                    (SysLoginLog log) ->
                                            log.getStatusFlag() == 0
                                                    && "refresh".equals(log.getLoginType())));
            when(mapper.insert(any(SysLoginLog.class))).thenReturn(0);
            assertThrows(ServiceException.class, () -> service.recordLogin(dto));
        } finally {
            InnerTrustContext.clear();
        }
    }

    @Test
    void delegateMustMatchAuthenticatedAccountCompanyAndUsername() {
        var mapper = mock(SysLoginLogMapper.class);
        var service = new LoginLogService(mapper);
        when(mapper.insert(any(SysLoginLog.class))).thenReturn(1);
        InnerTrustContext.bindVerifiedService("u", "c", "alice");
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::requireTrustedService).thenReturn(InnerTrustContext.current());
            var dto = event();
            service.recordLogin(dto);
            auth.verify(() -> AuthUtil.requireTrustedUser("u", "c"));
            dto.setUsername("impersonator");
            assertThrows(InnerAccessDeniedException.class, () -> service.recordLogin(dto));
            verify(mapper, times(1)).insert(any(SysLoginLog.class));
            InnerTrustContext.bindVerifiedService("u", "c", null);
            auth.when(AuthUtil::requireTrustedService).thenReturn(InnerTrustContext.current());
            service.recordLogin(dto);
        } finally {
            InnerTrustContext.clear();
        }
    }

    @Test
    void invalidPayloadBoundsAndClosedEventTypesNeverReachPersistence() {
        var mapper = mock(SysLoginLogMapper.class);
        var service = new LoginLogService(mapper);
        InnerTrustContext.bindVerifiedService(null, null, null);
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::requireTrustedService).thenReturn(InnerTrustContext.current());
            assertThrows(ServiceException.class, () -> service.recordLogin(null));
            for (java.util.function.Consumer<RemoteLoginLogDTO> invalid :
                    java.util.List.<java.util.function.Consumer<RemoteLoginLogDTO>>of(
                            dto -> dto.setUserId("u".repeat(MAX_ID_LENGTH + 1)),
                            dto -> dto.setCompanyId("c".repeat(MAX_ID_LENGTH + 1)),
                            dto -> dto.setUsername("a".repeat(MAX_USERNAME_LENGTH + 1)),
                            dto -> dto.setIp("1".repeat(MAX_IP_LENGTH + 1)),
                            dto -> dto.setUserAgent("a".repeat(MAX_REMARK_LENGTH + 1)),
                            dto -> dto.setStatus(2),
                            dto -> dto.setLoginType("arbitrary"),
                            dto -> dto.setEventId("invalid"))) {
                var dto = event();
                invalid.accept(dto);
                assertThrows(ServiceException.class, () -> service.recordLogin(dto));
            }
            verifyNoInteractions(mapper);
        } finally {
            InnerTrustContext.clear();
        }
    }

    @Test
    void duplicateAcknowledgementRequiresExactlyOnePersistedDeliveryIdentity() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "login-delivery"),
                SysLoginLog.class);
        var mapper = mock(SysLoginLogMapper.class);
        var service = new LoginLogService(mapper);
        var duplicate = new DuplicateKeyException("delivery duplicate");
        when(mapper.insert(any(SysLoginLog.class))).thenThrow(duplicate);
        InnerTrustContext.bindVerifiedService(null, null, null);
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::requireTrustedService).thenReturn(InnerTrustContext.current());
            var dto = event();
            dto.setEventId("6c31a250-5271-4685-a71f-16d812034a32");
            when(mapper.selectCount(any())).thenReturn(1L);
            assertDoesNotThrow(() -> service.recordLogin(dto));
            when(mapper.selectCount(any())).thenReturn(0L);
            assertSame(
                    duplicate,
                    assertThrows(DuplicateKeyException.class, () -> service.recordLogin(dto)));
            when(mapper.selectCount(any())).thenReturn(2L);
            assertThrows(DuplicateKeyException.class, () -> service.recordLogin(dto));
            dto.setEventId(null);
            assertThrows(DuplicateKeyException.class, () -> service.recordLogin(dto));
            verify(mapper, times(DELIVERY_ID_LOOKUPS)).selectCount(any());
        } finally {
            InnerTrustContext.clear();
        }
    }

    @Test
    @ResourceLock(Resources.TIME_ZONE)
    void loginRecordTimestampUsesUtcEvenWhenJvmDefaultIsNewYork() {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        var mapper = mock(SysLoginLogMapper.class);
        when(mapper.insert(any(SysLoginLog.class))).thenReturn(1);
        InnerTrustContext.bindVerifiedService(null, null, null);
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::requireTrustedService).thenReturn(InnerTrustContext.current());
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
            new LoginLogService(mapper).recordLogin(event());
            LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);
            ArgumentCaptor<SysLoginLog> inserted = ArgumentCaptor.forClass(SysLoginLog.class);
            verify(mapper).insert(inserted.capture());
            LocalDateTime time = inserted.getValue().getLoginTime();
            assertTrue(!time.isBefore(before) && !time.isAfter(after));
        } finally {
            InnerTrustContext.clear();
            TimeZone.setDefault(previous);
        }
    }

    private static RemoteLoginLogDTO event() {
        var dto = new RemoteLoginLogDTO();
        dto.setUserId("u");
        dto.setCompanyId("c");
        dto.setUsername("alice");
        dto.setStatus(1);
        dto.setLoginType("login");
        return dto;
    }
}
