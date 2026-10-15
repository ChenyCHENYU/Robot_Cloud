package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.common.core.domain.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthAuditQueueTest {
    private static final int RETENTION_SIMULATION_DAYS = 100;
    private static final int CLEANUP_BARRIER_TIMEOUT_SECONDS = 5;
    private static final int RESPONSE_TIMEOUT_SECONDS = 3;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RemoteUserDTO user =
            RemoteUserDTO.builder()
                    .id("user")
                    .username("account")
                    .passwordHash("fixture-hash")
                    .securityVersion(0L)
                    .build();
    private final RemoteUserContextDTO context =
            RemoteUserContextDTO.builder()
                    .contextId("context")
                    .companyId("company")
                    .securityVersion(0L)
                    .roles(List.of())
                    .build();

    @Test
    void responseDoesNotWaitForAuditRpcAndRestartedWorkerDeliversSameDurableEvent()
            throws Exception {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var remote = mock(RemoteSystemService.class);
        var queue = new AuthAuditQueue(redis, remote, mapper);
        var worker = new AuthCleanupWorker(true);
        var sessions =
                new AuthSessionManager(
                        redis,
                        new AuthStateTestSupport.MemoryAccessTokens(),
                        mapper,
                        queue,
                        worker);
        var response = sessions.issue("login", user, context, List.of(context));
        assertNotNull(sessions.requireRefresh(response.getRefreshToken()));
        verifyNoInteractions(remote);
        assertEquals(1, redis.events.size());
        var payload = redis.events.values().iterator().next();
        assertFalse(payload.contains(response.getToken()));
        assertFalse(payload.contains(response.getRefreshToken()));
        assertFalse(payload.contains("fixture-hash"));
        String eventId = mapper.readValue(payload, RemoteLoginLogDTO.class).getEventId();
        when(remote.recordLogin(any())).thenThrow(new IllegalStateException("network-secret"));
        new AuthAuditQueue(redis, remote, mapper).dispatch();
        assertEquals(1, redis.events.size());
        redis.due.replaceAll((ignored, time) -> 0L);
        doReturn(Result.success()).when(remote).recordLogin(any());
        new AuthAuditQueue(redis, remote, mapper).dispatch();
        assertTrue(redis.events.isEmpty());
        verify(remote, times(2)).recordLogin(argThat(dto -> eventId.equals(dto.getEventId())));
        worker.close();
    }

    @Test
    void capacityIsCheckedBeforeFamilyCommitAndPendingHasNoExpiry() {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var remote = mock(RemoteSystemService.class);
        var queue = new AuthAuditQueue(redis, remote, mapper);
        var tokens = new AuthStateTestSupport.MemoryAccessTokens();
        var sessions =
                new AuthSessionManager(redis, tokens, mapper, queue, new AuthCleanupWorker(true));
        var original = sessions.issue("login", user, context, List.of(context));
        for (int i = redis.events.size(); i < AuthAuditQueue.CAPACITY; i++) {
            redis.events.put("fixture-" + i, "pending");
        }
        assertThrows(
                IllegalStateException.class,
                () ->
                        sessions.refresh(
                                sessions.requireRefresh(original.getRefreshToken()),
                                user,
                                context,
                                List.of(context)));
        assertNotNull(sessions.requireRefresh(original.getRefreshToken()));
        assertEquals(1, tokens.active.size());
        redis.now.set(java.time.Duration.ofDays(RETENTION_SIMULATION_DAYS).toMillis());
        assertEquals(AuthAuditQueue.CAPACITY, redis.events.size());
    }

    @Test
    void committedRefreshResponseDoesNotWaitForBlockedNonAuthoritativeCleanup() throws Exception {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var remote = mock(RemoteSystemService.class);
        var delegate = new AuthStateTestSupport.MemoryAccessTokens();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var tokens =
                new AuthAccessTokens() {
                    @Override
                    public IssuedAccess issue(
                            com.robot.common.security.context.LoginUser identity,
                            String family,
                            String fingerprint) {
                        return delegate.issue(identity, family, fingerprint);
                    }

                    @Override
                    public String currentToken() {
                        return delegate.currentToken();
                    }

                    @Override
                    public void revoke(String token) {
                        entered.countDown();
                        try {
                            if (!release.await(
                                    CLEANUP_BARRIER_TIMEOUT_SECONDS,
                                    java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new AssertionError("cleanup barrier timeout");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        delegate.revoke(token);
                    }
                };
        var cleanup = new AuthCleanupWorker();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var sessions =
                    new AuthSessionManager(
                            redis,
                            tokens,
                            mapper,
                            new AuthAuditQueue(redis, remote, mapper),
                            cleanup);
            var original = sessions.issue("login", user, context, List.of(context));
            var next =
                    pool.submit(
                                    () ->
                                            sessions.refresh(
                                                    sessions.requireRefresh(
                                                            original.getRefreshToken()),
                                                    user,
                                                    context,
                                                    List.of(context)))
                            .get(RESPONSE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(
                    entered.await(RESPONSE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS));
            assertNotNull(sessions.requireRefresh(next.getRefreshToken()));
            assertThrows(
                    com.robot.common.core.exception.ServiceException.class,
                    () -> sessions.requireRefresh(original.getRefreshToken()));
            verifyNoInteractions(remote);
        } finally {
            release.countDown();
            pool.shutdownNow();
            cleanup.close();
        }
    }
}
