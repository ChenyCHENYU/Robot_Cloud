package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.dto.LoginResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Residual access indexes locate candidates, but only the current access digest owns family
 * revocation.
 */
class AuthLogoutOwnershipTest {
    private static final int WAIT_SECONDS = 5;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RemoteSystemService remote = mock(RemoteSystemService.class);
    private final RemoteUserDTO user =
            RemoteUserDTO.builder()
                    .id("user")
                    .username("alice")
                    .passwordHash("synthetic-hash")
                    .statusFlag(1)
                    .securityVersion(0L)
                    .build();
    private final RemoteUserContextDTO context =
            RemoteUserContextDTO.builder()
                    .contextId("member")
                    .companyId("company")
                    .securityVersion(0L)
                    .roles(List.of())
                    .build();

    @Test
    void failedOldIndexCleanupCannotGrantTheOldTokenAuthorityOverANewFamilyVersion() {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var tokens = new AuthStateTestSupport.MemoryAccessTokens();
        var manager = manager(redis, tokens, new AuthCleanupWorker(true));
        var old = manager.issue("login", user, context, List.of(context));
        String retainedIndex = AuthKeys.accessFamily(old.getToken());
        redis.beforeDelete =
                key -> {
                    if (key.equals(retainedIndex)) {
                        throw new IllegalStateException("synthetic index failure");
                    }
                };
        var next =
                manager.refresh(
                        manager.requireRefresh(old.getRefreshToken()),
                        user,
                        context,
                        List.of(context));
        assertTrue(redis.getString(retainedIndex).isPresent());
        tokens.current.set(old.getToken());
        manager.logoutCurrent();
        assertNotNull(manager.requireRefresh(next.getRefreshToken()));
        assertTrue(tokens.active.containsKey(next.getToken()));
        assertEquals(
                2,
                redis.events
                        .size()); // Login + refresh; stale logout has no committed success event.
    }

    @Test
    void blockedCleanupWithOldIndexesPresentCannotRevokeTheCommittedSuccessor() throws Exception {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var tokens = new AuthStateTestSupport.MemoryAccessTokens();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cleanup = new AuthCleanupWorker();
        try {
            var manager = manager(redis, tokens, cleanup);
            var old = manager.issue("login", user, context, List.of(context));
            String oldRefreshIndex = AuthKeys.refreshToken(old.getRefreshToken());
            redis.beforeDelete =
                    key -> {
                        if (key.equals(oldRefreshIndex)) {
                            entered.countDown();
                            try {
                                if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                                    throw new AssertionError("cleanup barrier timeout");
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    };
            var next =
                    manager.refresh(
                            manager.requireRefresh(old.getRefreshToken()),
                            user,
                            context,
                            List.of(context));
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(redis.getString(AuthKeys.accessFamily(old.getToken())).isPresent());
            tokens.current.set(old.getToken());
            manager.logoutCurrent();
            assertNotNull(manager.requireRefresh(next.getRefreshToken()));
            assertTrue(tokens.active.containsKey(next.getToken()));
            assertEquals(2, redis.events.size());
        } finally {
            release.countDown();
            cleanup.close();
        }
    }

    @Test
    void logoutCasRetryMustRecheckOwnershipAfterACompetingRefreshWins() {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var tokens = new AuthStateTestSupport.MemoryAccessTokens();
        var manager = manager(redis, tokens, new AuthCleanupWorker(true));
        var old = manager.issue("login", user, context, List.of(context));
        var raceOnce = new AtomicBoolean(true);
        var successor = new AtomicReference<LoginResponse>();
        redis.beforeStateEvent =
                transition -> {
                    if (transition.replacement() == null && raceOnce.getAndSet(false)) {
                        successor.set(
                                manager.refresh(
                                        manager.requireRefresh(old.getRefreshToken()),
                                        user,
                                        context,
                                        List.of(context)));
                    }
                };
        tokens.current.set(old.getToken());
        manager.logoutCurrent();
        assertNotNull(successor.get());
        assertNotNull(manager.requireRefresh(successor.get().getRefreshToken()));
        assertTrue(tokens.active.containsKey(successor.get().getToken()));
        assertEquals(2, redis.events.size());
    }

    private AuthSessionManager manager(
            AuthStateTestSupport.MemoryRedis redis,
            AuthStateTestSupport.MemoryAccessTokens tokens,
            AuthCleanupWorker cleanup) {
        return new AuthSessionManager(
                redis, tokens, mapper, new AuthAuditQueue(redis, remote, mapper), cleanup);
    }
}
