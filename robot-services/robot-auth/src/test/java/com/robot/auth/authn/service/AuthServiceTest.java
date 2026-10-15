package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.dto.LoginCompaniesRequest;
import com.robot.auth.authn.dto.LoginRequest;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.redis.core.RedisHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 登录安全测试：凭据失败统一话术、停用拦截和公司发现的凭据边界。 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private RemoteSystemService remoteSystemService;

    @Mock private RedisHelper redisHelper;

    @Mock private AuthSessionManager sessions;

    private AuthService authService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    @BeforeEach
    void setUp() {
        authService =
                new AuthService(
                        remoteSystemService,
                        redisHelper,
                        sessions,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        new AuthCredentials(
                                remoteSystemService,
                                encoder,
                                org.mockito.Mockito.mock(AuthAttemptGate.class),
                                org.mockito.Mockito.mock(AuthAuditQueue.class)));
    }

    private static LoginCompaniesRequest buildCompanies(String username, String password) {
        LoginCompaniesRequest request = new LoginCompaniesRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    private static LoginRequest login(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    private RemoteUserDTO activeUser() {
        return RemoteUserDTO.builder()
                .id("usr-1")
                .username("admin")
                .passwordHash(encoder.encode("admin123"))
                .nickname("管理员")
                .statusFlag(1)
                .securityVersion(0L)
                .build();
    }

    @Test
    @DisplayName("用户不存在：统一返回账号或密码错误（防枚举）")
    void loginShouldFailUniformlyForUnknownUser() {
        lenient()
                .when(remoteSystemService.getUserByUsername("ghost"))
                .thenReturn(Result.success(null));

        ServiceException e =
                assertThrows(
                        ServiceException.class,
                        () -> authService.login(login("ghost", "whatever")));
        assertEquals(ResultCode.AUTH_LOGIN_FAILED.getCode(), e.getCode());
        assertEquals("账号或密码错误", e.getMessage());
    }

    @Test
    @DisplayName("密码错误：同一话术，不暴露具体原因")
    void loginShouldFailUniformlyForWrongPassword() {
        lenient()
                .when(remoteSystemService.getUserByUsername("admin"))
                .thenReturn(Result.success(activeUser()));

        ServiceException e =
                assertThrows(
                        ServiceException.class,
                        () -> authService.login(login("admin", "wrong-pass")));
        assertEquals(ResultCode.AUTH_LOGIN_FAILED.getCode(), e.getCode());
        verify(remoteSystemService, never()).getUserContexts(anyString());
    }

    @Test
    @DisplayName("停用账号：返回账号停用")
    void loginShouldRejectDisabledAccount() {
        RemoteUserDTO disabled = activeUser();
        disabled.setStatusFlag(0);
        lenient()
                .when(remoteSystemService.getUserByUsername("admin"))
                .thenReturn(Result.success(disabled));

        ServiceException e =
                assertThrows(
                        ServiceException.class,
                        () -> authService.login(login("admin", "admin123")));
        assertEquals(ResultCode.AUTH_ACCOUNT_DISABLED.getCode(), e.getCode());
    }

    @Test
    void wrongPasswordDoesNotRevealDisabledAccount() {
        RemoteUserDTO disabled = activeUser();
        disabled.setStatusFlag(0);
        org.mockito.Mockito.when(remoteSystemService.getUserByUsername("admin"))
                .thenReturn(Result.success(disabled));
        var failure =
                assertThrows(
                        ServiceException.class,
                        () -> authService.login(login("admin", "wrong-pass")));
        assertEquals(ResultCode.AUTH_LOGIN_FAILED.getCode(), failure.getCode());
        verify(remoteSystemService, never()).getUserContexts(anyString());
    }

    @Test
    @DisplayName("公司发现：未知账号使用相同凭据失败，不读取公司清单")
    void loginCompaniesShouldRejectUnknownUserWithoutReadingContexts() {
        lenient()
                .when(remoteSystemService.getUserByUsername("nobody"))
                .thenReturn(Result.success(null));

        ServiceException failure =
                assertThrows(
                        ServiceException.class,
                        () -> authService.getLoginCompanies(buildCompanies("nobody", "wrong")));
        assertEquals(ResultCode.AUTH_LOGIN_FAILED.getCode(), failure.getCode());
        assertEquals("账号或密码错误", failure.getMessage());
        verify(remoteSystemService, never()).getUserContexts(anyString());
    }

    @Test
    @DisplayName("刷新令牌无效：拒绝并提示重新登录")
    void refreshShouldRejectUnknownToken() {
        lenient()
                .when(sessions.requireRefresh("not-a-token"))
                .thenThrow(new ServiceException(ResultCode.AUTH_REFRESH_TOKEN_INVALID));

        ServiceException e =
                assertThrows(ServiceException.class, () -> authService.refreshToken("not-a-token"));
        assertEquals(ResultCode.AUTH_REFRESH_TOKEN_INVALID.getCode(), e.getCode());
    }
}
