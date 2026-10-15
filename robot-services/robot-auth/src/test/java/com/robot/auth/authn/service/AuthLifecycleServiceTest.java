package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.dto.ActivateContextRequest;
import com.robot.auth.authn.dto.LoginRequest;
import com.robot.auth.authn.dto.LoginResponse;
import com.robot.auth.authn.dto.RefreshTokenResponse;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthLifecycleServiceTest {
    private static final int CONCURRENCY_TIMEOUT_SECONDS = 5;
    private static final int EXPECTED_FAMILY_AND_INDEX_KEYS = 3;

    private RemoteSystemService remote;
    private AuthStateTestSupport.MemoryRedis redis;
    private AuthStateTestSupport.MemoryAccessTokens tokens;
    private AuthSessionManager sessions;
    private AuthService auth;
    private RemoteUserDTO user;
    private RemoteUserContextDTO companyA, companyB;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setUp() {
        remote = mock(RemoteSystemService.class);
        redis = new AuthStateTestSupport.MemoryRedis();
        tokens = new AuthStateTestSupport.MemoryAccessTokens();
        user =
                RemoteUserDTO.builder()
                        .id("user-1")
                        .username("ordinary-user")
                        .statusFlag(1)
                        .securityVersion(0L)
                        .passwordHash(encoder.encode("test-password"))
                        .build();
        companyA =
                RemoteUserContextDTO.builder()
                        .contextId("ctx-a")
                        .companyId("company-a")
                        .securityVersion(0L)
                        .build();
        companyB =
                RemoteUserContextDTO.builder()
                        .contextId("ctx-b")
                        .companyId("company-b")
                        .securityVersion(0L)
                        .build();
        when(remote.getUserByUsername(anyString())).thenReturn(Result.success(user));
        when(remote.getUserById("user-1")).thenReturn(Result.success(user));
        when(remote.getUserContexts("user-1"))
                .thenReturn(Result.success(List.of(companyA, companyB)));
        sessions = new AuthSessionManager(remote, redis, tokens, new ObjectMapper());
        auth = service(sessions);
    }

    private AuthService service(AuthSessionManager manager) {
        return new AuthService(
                remote,
                redis,
                manager,
                new ObjectMapper(),
                new AuthCredentials(
                        remote,
                        encoder,
                        org.mockito.Mockito.mock(AuthAttemptGate.class),
                        org.mockito.Mockito.mock(AuthAuditQueue.class)));
    }

    private LoginResponse login(String context) {
        LoginRequest request = new LoginRequest();
        request.setUsername(user.getUsername());
        request.setPassword("test-password");
        request.setContextId(context);
        return auth.login(request);
    }

    private ActivateContextRequest activate(String ticket) {
        return new ActivateContextRequest(ticket, "ctx-a");
    }

    @Test
    void concurrentTicketActivationAcrossInstancesOnlyCreatesOneSession() throws Exception {
        LoginResponse pending = login(null);
        CyclicBarrier verified = new CyclicBarrier(2);
        when(remote.getUserContexts("user-1"))
                .thenAnswer(
                        invocation -> {
                            verified.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            return Result.success(List.of(companyA, companyB));
                        });
        AuthService other =
                service(new AuthSessionManager(remote, redis, tokens, new ObjectMapper()));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first =
                    pool.submit(() -> activateOrFalse(auth, pending.getLoginTicket()));
            Future<Boolean> second =
                    pool.submit(() -> activateOrFalse(other, pending.getLoginTicket()));
            assertNotEquals(
                    first.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, tokens.active.size());
            assertEquals(EXPECTED_FAMILY_AND_INDEX_KEYS, redis.entries.size());
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean activateOrFalse(AuthService service, String ticket) {
        try {
            service.activateContext(activate(ticket));
            return true;
        } catch (ServiceException e) {
            assertEquals(ResultCode.AUTH_LOGIN_TICKET_INVALID.getCode(), e.getCode());
            return false;
        }
    }

    @Test
    void ticketUsesPreciseFiveMinuteExpiryAndDigestKeys() {
        LoginResponse pending = login(null);
        assertFalse(
                redis.entries.keySet().stream()
                        .anyMatch(k -> k.contains(pending.getLoginTicket())));
        assertEquals(
                AuthKeys.TTL_LOGIN_TICKET.toMillis(),
                redis.entries.get(AuthKeys.loginTicket(pending.getLoginTicket())).expiresAt());
        redis.now.set(AuthKeys.TTL_LOGIN_TICKET.toMillis());
        assertThrows(
                ServiceException.class,
                () -> auth.activateContext(activate(pending.getLoginTicket())));
        assertTrue(tokens.active.isEmpty());
    }

    @Test
    void transientRpcFailureKeepsTicketUsableForRetry() {
        LoginResponse pending = login(null);
        when(remote.getUserById("user-1")).thenReturn(Result.failed(ResultCode.FAILED));
        ServiceException failure =
                assertThrows(
                        ServiceException.class,
                        () -> auth.activateContext(activate(pending.getLoginTicket())));
        assertEquals(ResultCode.FAILED.getCode(), failure.getCode());
        assertTrue(redis.getString(AuthKeys.loginTicket(pending.getLoginTicket())).isPresent());
        when(remote.getUserById("user-1")).thenReturn(Result.success(user));
        assertNotNull(auth.activateContext(activate(pending.getLoginTicket())).getToken());
    }

    @Test
    void loginRpcFailureIsNotReportedAsAWrongPasswordAndCreatesNoTicket() {
        when(remote.getUserByUsername(anyString())).thenReturn(Result.failed(ResultCode.FAILED));
        ServiceException failure = assertThrows(ServiceException.class, () -> login(null));
        assertEquals(ResultCode.FAILED.getCode(), failure.getCode());
        assertTrue(tokens.active.isEmpty());
        assertTrue(redis.entries.isEmpty());
        when(remote.getUserByUsername(anyString())).thenReturn(null);
        ServiceException nullResponse = assertThrows(ServiceException.class, () -> login(null));
        assertEquals(ResultCode.FAILED.getCode(), nullResponse.getCode());
    }

    @Test
    void passwordChangeBetweenLoginAndActivationInvalidatesTicket() {
        LoginResponse pending = login(null);
        user.setPasswordHash(encoder.encode("changed-test-password"));
        ServiceException failure =
                assertThrows(
                        ServiceException.class,
                        () -> auth.activateContext(activate(pending.getLoginTicket())));
        assertEquals(ResultCode.AUTH_LOGIN_TICKET_INVALID.getCode(), failure.getCode());
        assertTrue(tokens.active.isEmpty());
        assertTrue(redis.entries.isEmpty());
    }

    @Test
    void transientRpcFailureKeepsBothTokensAndRetryRotatesThem() {
        LoginResponse original = login("ctx-a");
        when(remote.getUserById("user-1")).thenReturn(Result.failed(ResultCode.FAILED));
        ServiceException failure =
                assertThrows(
                        ServiceException.class,
                        () -> auth.refreshToken(original.getRefreshToken()));
        assertEquals(ResultCode.FAILED.getCode(), failure.getCode());
        assertNotNull(sessions.requireRefresh(original.getRefreshToken()));
        assertTrue(tokens.active.containsKey(original.getToken()));
        when(remote.getUserById("user-1")).thenReturn(Result.success(user));
        RefreshTokenResponse refreshed = auth.refreshToken(original.getRefreshToken());
        assertNotNull(sessions.requireRefresh(refreshed.getRefreshToken()));
        assertFalse(tokens.active.containsKey(original.getToken()));
    }

    @Test
    void disabledSubjectRevokesFamilyAfterRevalidation() {
        LoginResponse original = login("ctx-a");
        user.setStatusFlag(0);
        ServiceException failure =
                assertThrows(
                        ServiceException.class,
                        () -> auth.refreshToken(original.getRefreshToken()));
        assertEquals(ResultCode.AUTH_ACCOUNT_DISABLED.getCode(), failure.getCode());
        assertTrue(tokens.active.isEmpty());
        assertThrows(
                ServiceException.class, () -> sessions.requireRefresh(original.getRefreshToken()));
        assertEquals(1, redis.entries.size());
    }

    @Test
    void removedMembershipRevokesFamilyAfterRevalidation() {
        LoginResponse original = login("ctx-a");
        when(remote.getUserContexts("user-1")).thenReturn(Result.success(List.of(companyB)));
        ServiceException failure =
                assertThrows(
                        ServiceException.class,
                        () -> auth.refreshToken(original.getRefreshToken()));
        assertEquals(ResultCode.AUTH_CONTEXT_DENIED.getCode(), failure.getCode());
        assertTrue(tokens.active.isEmpty());
        assertThrows(
                ServiceException.class, () -> sessions.requireRefresh(original.getRefreshToken()));
        assertEquals(1, redis.entries.size());
    }

    @Test
    void accountDisableAndEnableBeforeRefreshStillInvalidatesTheOldVersion() {
        LoginResponse original = login("ctx-a");
        user.setStatusFlag(1);
        user.setSecurityVersion(1L);
        assertThrows(ServiceException.class, () -> auth.refreshToken(original.getRefreshToken()));
        assertTrue(tokens.active.isEmpty());
        assertThrows(
                ServiceException.class, () -> sessions.requireRefresh(original.getRefreshToken()));
    }

    @Test
    void membershipDisableAndEnableBeforeRefreshStillInvalidatesTheOldVersion() {
        LoginResponse original = login("ctx-a");
        companyA.setSecurityVersion(1L);
        assertThrows(ServiceException.class, () -> auth.refreshToken(original.getRefreshToken()));
        assertTrue(tokens.active.isEmpty());
        assertThrows(
                ServiceException.class, () -> sessions.requireRefresh(original.getRefreshToken()));
    }

    @Test
    void oldTicketCannotSurviveAccountSecurityVersionChangeWithAnUnchangedPassword() {
        LoginResponse pending = login(null);
        user.setSecurityVersion(1L);
        ServiceException failure =
                assertThrows(
                        ServiceException.class,
                        () -> auth.activateContext(activate(pending.getLoginTicket())));
        assertEquals(ResultCode.AUTH_LOGIN_TICKET_INVALID.getCode(), failure.getCode());
        assertTrue(tokens.active.isEmpty());
        assertTrue(redis.entries.isEmpty());
    }

    @Test
    void concurrentRefreshLoserDoesNotRevokeSuccessfulFamily() throws Exception {
        LoginResponse original = login("ctx-a");
        CyclicBarrier verified = new CyclicBarrier(2);
        when(remote.getUserById("user-1"))
                .thenAnswer(
                        invocation -> {
                            verified.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            return Result.success(user);
                        });
        AuthService other =
                service(new AuthSessionManager(remote, redis, tokens, new ObjectMapper()));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RefreshTokenResponse> one =
                    pool.submit(() -> refreshOrNull(auth, original.getRefreshToken()));
            Future<RefreshTokenResponse> two =
                    pool.submit(() -> refreshOrNull(other, original.getRefreshToken()));
            RefreshTokenResponse first = one.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second = two.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue((first == null) != (second == null));
            RefreshTokenResponse winner = first == null ? second : first;
            assertNotNull(sessions.requireRefresh(winner.getRefreshToken()));
            assertEquals(1, tokens.active.size());
            assertEquals(EXPECTED_FAMILY_AND_INDEX_KEYS, redis.entries.size());
        } finally {
            pool.shutdownNow();
        }
    }

    private RefreshTokenResponse refreshOrNull(AuthService service, String refresh) {
        try {
            return service.refreshToken(refresh);
        } catch (ServiceException e) {
            assertEquals(ResultCode.AUTH_REFRESH_TOKEN_INVALID.getCode(), e.getCode());
            return null;
        }
    }
}
