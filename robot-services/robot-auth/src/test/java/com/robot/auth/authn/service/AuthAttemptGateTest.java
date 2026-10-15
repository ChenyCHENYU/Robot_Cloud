package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthAttemptGateTest {
    private static final int INVALID_LIMIT_OVER_MAXIMUM = 10_001;
    private static final int DEFAULT_SOURCE_LIMIT = 200;
    private static final int DEFAULT_TOTAL_LIMIT = 1000;
    private static final int DEFAULT_ACCOUNT_LIMIT = 10;
    private static final int MAX_CONFIGURED_LIMIT = 10_000;
    private static final int UNKNOWN_IDENTITY_CAPACITY = 3;
    private static final int FIXED_EPOCH_SECONDS = 120;
    private static final int SOURCE_TEST_CAPACITY = 5;
    private static final int EXPECTED_BOUNDED_COUNTER_KEYS = 7;

    @ParameterizedTest
    @ValueSource(ints = {0, -1, INVALID_LIMIT_OVER_MAXIMUM})
    void invalidLimitsCannotCreateAnUnboundedOrPermanentlyClosedGate(int invalid) {
        var redis = new AuthStateTestSupport.MemoryRedis();
        assertThrows(
                IllegalStateException.class,
                () ->
                        new AuthAttemptGate(
                                redis,
                                Clock.systemUTC(),
                                invalid,
                                DEFAULT_SOURCE_LIMIT,
                                DEFAULT_TOTAL_LIMIT));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new AuthAttemptGate(
                                redis,
                                Clock.systemUTC(),
                                DEFAULT_ACCOUNT_LIMIT,
                                invalid,
                                DEFAULT_TOTAL_LIMIT));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new AuthAttemptGate(
                                redis,
                                Clock.systemUTC(),
                                DEFAULT_ACCOUNT_LIMIT,
                                DEFAULT_SOURCE_LIMIT,
                                invalid));
    }

    @Test
    void minimumAndMaximumConfiguredLimitsRemainUsable() {
        var redis = new AuthStateTestSupport.MemoryRedis();
        assertDoesNotThrow(() -> new AuthAttemptGate(redis, Clock.systemUTC(), 1, 1, 1));
        assertDoesNotThrow(
                () ->
                        new AuthAttemptGate(
                                redis,
                                Clock.systemUTC(),
                                MAX_CONFIGURED_LIMIT,
                                MAX_CONFIGURED_LIMIT,
                                MAX_CONFIGURED_LIMIT));
    }

    @Test
    void anonymousAndLoginAttemptsShareActualRemoteSourceAndIgnoreSpoofedForwardedHeaders() {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var gate =
                new AuthAttemptGate(
                        redis,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        DEFAULT_ACCOUNT_LIMIT,
                        2,
                        DEFAULT_ACCOUNT_LIMIT);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.1");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            gate.checkAnonymous();
            gate.check("alice");
            var rejected = assertThrows(ServiceException.class, gate::checkAnonymous);
            assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), rejected.getCode());
            assertTrue(
                    redis.entries.keySet().stream()
                            .noneMatch(
                                    key ->
                                            key.contains("192.0.2.1")
                                                    || key.contains("203.0.113.99")));
            assertEquals(UNKNOWN_IDENTITY_CAPACITY, redis.entries.size());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void anonymousGateWorksWithoutHttpContextAndStorageFailureIsFixedAndClosed() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        var gate =
                new AuthAttemptGate(
                        new AuthStateTestSupport.MemoryRedis(),
                        Clock.systemUTC(),
                        DEFAULT_ACCOUNT_LIMIT,
                        1,
                        DEFAULT_ACCOUNT_LIMIT);
        gate.checkAnonymous();
        assertEquals(
                ResultCode.TOO_MANY_REQUESTS.getCode(),
                assertThrows(ServiceException.class, gate::checkAnonymous).getCode());
        var broken = org.mockito.Mockito.mock(com.robot.common.redis.core.RedisHelper.class);
        org.mockito.Mockito.when(
                        broken.incrementWithinLimit(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyInt(),
                                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("fixture-secret"));
        var failure =
                assertThrows(
                        ServiceException.class, () -> new AuthAttemptGate(broken).checkAnonymous());
        assertEquals(ResultCode.FAILED.getCode(), failure.getCode());
        assertFalse(failure.getMessage().contains("fixture-secret"));
    }

    @Test
    void sharesAccountWindowAcrossInstancesAndChangingIpThenRecovers() {
        var redis = new AuthStateTestSupport.MemoryRedis();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(FIXED_EPOCH_SECONDS), ZoneOffset.UTC);
        var one = new AuthAttemptGate(redis, clock, 2, SOURCE_TEST_CAPACITY, DEFAULT_ACCOUNT_LIMIT);
        var two = new AuthAttemptGate(redis, clock, 2, SOURCE_TEST_CAPACITY, DEFAULT_ACCOUNT_LIMIT);
        one.check("Alice", "10.0.0.1");
        two.check("ALICE", "10.0.0.2");
        var failure = assertThrows(ServiceException.class, () -> two.check("alice", "10.0.0.3"));
        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), failure.getCode());
        new AuthAttemptGate(
                        redis,
                        Clock.offset(clock, java.time.Duration.ofMinutes(1)),
                        2,
                        SOURCE_TEST_CAPACITY,
                        DEFAULT_ACCOUNT_LIMIT)
                .check("alice", "10.0.0.1");
    }

    @Test
    void globalCapacityBoundsUnknownIdentityKeysAndRedisFaultFailsClosed() {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var gate =
                new AuthAttemptGate(
                        redis,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        2,
                        DEFAULT_ACCOUNT_LIMIT,
                        UNKNOWN_IDENTITY_CAPACITY);
        for (int i = 0; i < UNKNOWN_IDENTITY_CAPACITY; i++) {
            gate.check("unknown-" + i, "source-" + i);
        }
        assertThrows(ServiceException.class, () -> gate.check("four", "four"));
        assertEquals(EXPECTED_BOUNDED_COUNTER_KEYS, redis.entries.size());
        assertTrue(redis.entries.keySet().stream().noneMatch(key -> key.contains("unknown-")));
        var broken = org.mockito.Mockito.mock(com.robot.common.redis.core.RedisHelper.class);
        org.mockito.Mockito.when(
                        broken.incrementWithinLimit(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyInt(),
                                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("fixture-secret"));
        var failure =
                assertThrows(
                        ServiceException.class,
                        () -> new AuthAttemptGate(broken).check("account", "ip"));
        assertEquals(ResultCode.FAILED.getCode(), failure.getCode());
        assertFalse(failure.getMessage().contains("fixture-secret"));
    }
}
