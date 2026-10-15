package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.dto.LoginResponse;
import com.robot.common.core.exception.ServiceException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthSessionManagerTest {
    private static final int EXPECTED_FAMILY_AND_INDEX_KEYS = 3;
    private static final int CONCURRENCY_TIMEOUT_SECONDS = 5;
    private static final long UPDATED_MEMBERSHIP_VERSION = 7L;
    private static final int EXPECTED_TWO_DEVICE_KEYS = 6;

    private AuthStateTestSupport.MemoryRedis redis;
    private AuthStateTestSupport.MemoryAccessTokens tokens;
    private AuthSessionManager sessions;
    private RemoteSystemService remote;
    private RemoteUserDTO user;

    @BeforeEach
    void setUp() {
        redis = new AuthStateTestSupport.MemoryRedis();
        tokens = new AuthStateTestSupport.MemoryAccessTokens();
        remote = mock(RemoteSystemService.class);
        sessions = manager();
        user =
                RemoteUserDTO.builder()
                        .id("user-1")
                        .username("user")
                        .statusFlag(1)
                        .securityVersion(0L)
                        .passwordHash("test-only-bcrypt-fingerprint")
                        .build();
    }

    private AuthSessionManager manager() {
        return new AuthSessionManager(remote, redis, tokens, new ObjectMapper());
    }

    private RemoteUserContextDTO context(String company) {
        return RemoteUserContextDTO.builder()
                .contextId("ctx-" + company)
                .companyId(company)
                .securityVersion(0L)
                .roles(List.of())
                .build();
    }

    private LoginResponse login(String company) {
        RemoteUserContextDTO context = context(company);
        return sessions.issue("login", user, context, List.of(context));
    }

    @Test
    void independentDevicesKeepCompanyAndLogoutOnlyTheirOwnFamily() {
        LoginResponse first = login("company-a"), second = login("company-b");
        assertEquals("company-a", tokens.active.get(first.getToken()).getCompanyId());
        assertEquals("company-b", tokens.active.get(second.getToken()).getCompanyId());
        assertEquals("ctx-company-a", sessions.requireRefresh(first.getRefreshToken()).contextId());
        tokens.current.set(first.getToken());
        sessions.logoutCurrent();
        assertThrows(
                ServiceException.class, () -> sessions.requireRefresh(first.getRefreshToken()));
        assertNotNull(sessions.requireRefresh(second.getRefreshToken()));
        assertFalse(tokens.active.containsKey(first.getToken()));
        assertTrue(tokens.active.containsKey(second.getToken()));
        assertEquals(EXPECTED_FAMILY_AND_INDEX_KEYS, redis.entries.size());
    }

    @Test
    void rotationRejectsReplayCleansOldIndexesAndPreservesExactTtl() {
        LoginResponse old = login("company-a");
        AuthSessionManager.RefreshSession pending = sessions.requireRefresh(old.getRefreshToken());
        LoginResponse next =
                sessions.refresh(
                        pending, user, context("company-a"), List.of(context("company-a")));
        assertThrows(ServiceException.class, () -> sessions.requireRefresh(old.getRefreshToken()));
        assertNotNull(sessions.requireRefresh(next.getRefreshToken()));
        assertFalse(tokens.active.containsKey(old.getToken()));
        assertEquals(EXPECTED_FAMILY_AND_INDEX_KEYS, redis.entries.size());
        assertTrue(
                redis.entries.values().stream()
                        .allMatch(e -> e.expiresAt() == AuthKeys.TTL_REFRESH_TOKEN.toMillis()));
        assertTrue(
                redis.entries.keySet().stream().noneMatch(k -> k.contains(next.getRefreshToken())));
        redis.now.set(AuthKeys.TTL_REFRESH_TOKEN.toMillis());
        assertThrows(ServiceException.class, () -> sessions.requireRefresh(next.getRefreshToken()));
    }

    @Test
    void concurrentRotationAcrossTwoManagersHasOneWinnerAndKeepsItValid() throws Exception {
        LoginResponse old = login("company-a");
        AuthSessionManager.RefreshSession first = sessions.requireRefresh(old.getRefreshToken());
        AuthSessionManager otherInstance = manager();
        AuthSessionManager.RefreshSession second =
                otherInstance.requireRefresh(old.getRefreshToken());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<LoginResponse> one = pool.submit(() -> rotateOrNull(sessions, first));
            Future<LoginResponse> two = pool.submit(() -> rotateOrNull(otherInstance, second));
            LoginResponse oneResult = one.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    twoResult = two.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue((oneResult == null) != (twoResult == null));
            LoginResponse winner = oneResult == null ? twoResult : oneResult;
            assertNotNull(sessions.requireRefresh(winner.getRefreshToken()));
            assertEquals(1, tokens.active.size());
            assertTrue(tokens.active.containsKey(winner.getToken()));
            assertEquals(EXPECTED_FAMILY_AND_INDEX_KEYS, redis.entries.size());
        } finally {
            pool.shutdownNow();
        }
    }

    private LoginResponse rotateOrNull(
            AuthSessionManager manager, AuthSessionManager.RefreshSession pending) {
        try {
            return manager.refresh(
                    pending, user, context("company-a"), List.of(context("company-a")));
        } catch (ServiceException e) {
            return null;
        }
    }

    @Test
    void logoutDuringCandidateCreationPreventsFamilyResurrection() throws Exception {
        LoginResponse old = login("company-a");
        AuthSessionManager.RefreshSession pending = sessions.requireRefresh(old.getRefreshToken());
        CountDownLatch staged = new CountDownLatch(1), release = new CountDownLatch(1);
        tokens.afterIssue =
                token -> {
                    staged.countDown();
                    await(release);
                };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<LoginResponse> rotation = pool.submit(() -> rotateOrNull(sessions, pending));
            assertTrue(staged.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            tokens.current.set(old.getToken());
            sessions.logoutCurrent();
            release.countDown();
            assertNull(rotation.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(tokens.active.isEmpty());
            assertTrue(redis.entries.isEmpty());
            assertThrows(
                    ServiceException.class, () -> sessions.requireRefresh(old.getRefreshToken()));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void oldTokenLogoutAfterRefreshCasCannotRevokeTheSuccessorEvenBeforeTheResponseArrives()
            throws Exception {
        LoginResponse old = login("company-a");
        AuthSessionManager.RefreshSession pending = sessions.requireRefresh(old.getRefreshToken());
        CountDownLatch committed = new CountDownLatch(1), release = new CountDownLatch(1);
        redis.afterCas =
                success -> {
                    if (success) {
                        committed.countDown();
                        await(release);
                    }
                };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<LoginResponse> rotation = pool.submit(() -> rotateOrNull(sessions, pending));
            assertTrue(committed.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            tokens.current.set(old.getToken());
            sessions.logoutCurrent();
            release.countDown();
            LoginResponse response = rotation.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(response);
            assertNotNull(sessions.requireRefresh(response.getRefreshToken()));
            assertEquals(1, tokens.active.size());
            assertTrue(tokens.active.containsKey(response.getToken()));
            assertEquals(EXPECTED_FAMILY_AND_INDEX_KEYS, redis.entries.size());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failedIndexWriteLeavesPreviousPairUsableAndDiscardsCandidate() {
        LoginResponse old = login("company-a");
        AuthSessionManager.RefreshSession pending = sessions.requireRefresh(old.getRefreshToken());
        AtomicBoolean firstFailure = new AtomicBoolean(true);
        redis.beforeSet =
                key -> {
                    if (key.contains(":access-family:") && firstFailure.getAndSet(false)) {
                        throw new IllegalStateException("模拟 Redis 写失败");
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () ->
                        sessions.refresh(
                                pending,
                                user,
                                context("company-a"),
                                List.of(context("company-a"))));
        assertNotNull(sessions.requireRefresh(old.getRefreshToken()));
        assertEquals(1, tokens.active.size());
        assertTrue(tokens.active.containsKey(old.getToken()));
        assertEquals(EXPECTED_FAMILY_AND_INDEX_KEYS, redis.entries.size());
    }

    @Test
    void unknownCasOutcomeNeverRestoresOldRefreshOrKeepsCandidateActive() {
        LoginResponse old = login("company-a");
        AuthSessionManager.RefreshSession pending = sessions.requireRefresh(old.getRefreshToken());
        AtomicBoolean failOnce = new AtomicBoolean(true);
        redis.afterCas =
                success -> {
                    if (success && failOnce.getAndSet(false)) {
                        throw new IllegalStateException("模拟提交后断连");
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () ->
                        sessions.refresh(
                                pending,
                                user,
                                context("company-a"),
                                List.of(context("company-a"))));
        assertThrows(ServiceException.class, () -> sessions.requireRefresh(old.getRefreshToken()));
        assertEquals(1, tokens.active.size());
        assertTrue(tokens.active.containsKey(old.getToken()));
        assertTrue(redis.entries.values().stream().anyMatch(e -> e.value().equals("{}")));
    }

    @Test
    void expiredAccessCanStillLogoutItsRefreshFamily() {
        LoginResponse old = login("company-a");
        tokens.active.remove(old.getToken());
        tokens.current.set(old.getToken());
        sessions.logoutCurrent();
        sessions.logoutCurrent();
        assertThrows(ServiceException.class, () -> sessions.requireRefresh(old.getRefreshToken()));
        assertTrue(redis.entries.isEmpty());
    }

    @Test
    void changingCredentialRevokesOldRefreshWithoutIssuingAnotherAccess() {
        LoginResponse old = login("company-a");
        AuthSessionManager.RefreshSession pending = sessions.requireRefresh(old.getRefreshToken());
        user.setPasswordHash("changed-test-only-fingerprint");
        assertThrows(
                ServiceException.class,
                () ->
                        sessions.refresh(
                                pending,
                                user,
                                context("company-a"),
                                List.of(context("company-a"))));
        assertTrue(tokens.active.isEmpty());
        assertThrows(ServiceException.class, () -> sessions.requireRefresh(old.getRefreshToken()));
        assertEquals(1, redis.entries.size());
        assertEquals("{}", redis.entries.values().iterator().next().value());
    }

    @Test
    void switchingContextReplacesOnlyCurrentDevicePair() {
        LoginResponse first = login("company-a"), second = login("company-b");
        tokens.current.set(first.getToken());
        RemoteUserContextDTO target = context("company-c");
        target.setSecurityVersion(UPDATED_MEMBERSHIP_VERSION);
        LoginResponse switched =
                sessions.switchCurrent(user, target, List.of(context("company-a"), target));
        assertThrows(
                ServiceException.class, () -> sessions.requireRefresh(first.getRefreshToken()));
        assertNotNull(sessions.requireRefresh(second.getRefreshToken()));
        assertEquals("company-c", tokens.active.get(switched.getToken()).getCompanyId());
        assertEquals("company-b", tokens.active.get(second.getToken()).getCompanyId());
        assertEquals(EXPECTED_TWO_DEVICE_KEYS, redis.entries.size());
        assertEquals(
                UPDATED_MEMBERSHIP_VERSION,
                tokens.active.get(switched.getToken()).getContextSecurityVersion());
    }

    @Test
    void reenabledAccountCannotReuseFamilyFromAnOlderSecurityVersion() {
        LoginResponse old = login("company-a");
        AuthSessionManager.RefreshSession pending = sessions.requireRefresh(old.getRefreshToken());
        user.setSecurityVersion(1L);
        assertThrows(
                ServiceException.class,
                () ->
                        sessions.refresh(
                                pending,
                                user,
                                context("company-a"),
                                List.of(context("company-a"))));
        assertTrue(tokens.active.isEmpty());
        assertThrows(ServiceException.class, () -> sessions.requireRefresh(old.getRefreshToken()));
    }

    @Test
    void reenabledMembershipCannotReuseFamilyFromAnOlderContextVersion() {
        LoginResponse old = login("company-a");
        AuthSessionManager.RefreshSession pending = sessions.requireRefresh(old.getRefreshToken());
        RemoteUserContextDTO activeAgain = context("company-a");
        activeAgain.setSecurityVersion(1L);
        assertThrows(
                ServiceException.class,
                () -> sessions.refresh(pending, user, activeAgain, List.of(activeAgain)));
        assertTrue(tokens.active.isEmpty());
        assertThrows(ServiceException.class, () -> sessions.requireRefresh(old.getRefreshToken()));
    }

    @Test
    void switchingCannotBypassRevocationOfTheSourceMembership() {
        LoginResponse old = login("company-a");
        tokens.current.set(old.getToken());
        assertThrows(
                ServiceException.class,
                () ->
                        sessions.switchCurrent(
                                user, context("company-b"), List.of(context("company-b"))));
        assertTrue(tokens.active.isEmpty());
        assertThrows(ServiceException.class, () -> sessions.requireRefresh(old.getRefreshToken()));
    }

    @Test
    void staleSnapshotFailureDoesNotRevokeASuccessfullySwitchedFamily() {
        LoginResponse old = login("company-a");
        AuthSessionManager.RefreshSession stale = sessions.requireRefresh(old.getRefreshToken());
        tokens.current.set(old.getToken());
        LoginResponse switched =
                sessions.switchCurrent(
                        user,
                        context("company-b"),
                        List.of(context("company-a"), context("company-b")));
        sessions.revoke(stale);
        assertNotNull(sessions.requireRefresh(switched.getRefreshToken()));
        assertTrue(tokens.active.containsKey(switched.getToken()));
        assertEquals(EXPECTED_FAMILY_AND_INDEX_KEYS, redis.entries.size());
    }

    @Test
    void missingSecurityVersionCannotIssueAnAccessToken() {
        user.setSecurityVersion(null);
        assertThrows(ServiceException.class, () -> login("company-a"));
        assertTrue(tokens.active.isEmpty());
        assertTrue(redis.entries.isEmpty());
        user.setSecurityVersion(0L);
        RemoteUserContextDTO incomplete = context("company-a");
        incomplete.setSecurityVersion(null);
        assertThrows(
                ServiceException.class,
                () -> sessions.issue("login", user, incomplete, List.of(incomplete)));
        assertTrue(tokens.active.isEmpty());
        assertTrue(redis.entries.isEmpty());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("等待并发屏障超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
