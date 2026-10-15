package com.robot.auth.authn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.redis.core.RedisHelper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Stored-identity admission precedes real BCrypt and collapses database username aliases. */
class AuthResolvedAccountGateTest {
    private static final int TEST_BCRYPT_COST = 4;
    private static final int SOURCE_LIMIT = 10;
    private static final int GLOBAL_LIMIT = 4;
    private static final int EXPECTED_PRE_ADMISSIONS = 3;
    private static final int MAX_ID_CHARACTERS = 64;
    private static final int MAX_ID_UTF16_UNITS = 128;
    private static final Duration COUNTER_TTL = Duration.ofMinutes(2);
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private static final String PASSWORD = "synthetic-password";
    private static final String USER_ID = "stored-canonical-id";

    private final RemoteSystemService remote = mock(RemoteSystemService.class);
    private final BCryptPasswordEncoder encoder = spy(new BCryptPasswordEncoder(TEST_BCRYPT_COST));

    @ParameterizedTest
    @CsvSource({"0", "1"})
    void unicodeUsernameAliasesShareTheStoredAccountBudgetAcrossTwoInstancesThenRecover(int status)
            throws Exception {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var user = account(USER_ID, status);
        // Only account supply is substituted: production MySQL general_ci maps these aliases
        // to the same persisted row; credential, Redis-counter and reliable audit paths are real.
        when(remote.getUserByUsername("admin")).thenReturn(Result.success(user));
        when(remote.getUserByUsername("àdmin")).thenReturn(Result.success(user));
        when(remote.getUserByUsername("ádmin")).thenReturn(Result.success(user));
        var one = credentials(redis, CLOCK);
        var two = credentials(redis, CLOCK);
        assertFailureFromPeer(one, "admin", "192.0.2.1", ResultCode.AUTH_LOGIN_FAILED);
        assertFailureFromPeer(two, "àdmin", "192.0.2.2", ResultCode.AUTH_LOGIN_FAILED);
        assertFailureFromPeer(two, "ádmin", "192.0.2.3", ResultCode.TOO_MANY_REQUESTS);
        verify(encoder, times(2)).matches(anyString(), anyString());
        assertThat(redis.events).hasSize(2);
        assertThat(redis.entries.keySet())
                .filteredOn(key -> key.contains(":resolved-account:"))
                .hasSize(1);
        var stored =
                redis.entries.entrySet().stream()
                        .filter(entry -> entry.getKey().contains(":resolved-account:"))
                        .findFirst()
                        .orElseThrow();
        assertThat(stored.getValue().value()).isEqualTo("2");
        assertThat(stored.getValue().expiresAt()).isEqualTo(COUNTER_TTL.toMillis());
        assertCounter(redis, ":total", EXPECTED_PRE_ADMISSIONS);
        assertThat(redis.entries.entrySet())
                .filteredOn(entry -> entry.getKey().contains(":source:"))
                .hasSize(EXPECTED_PRE_ADMISSIONS)
                .allMatch(entry -> entry.getValue().value().equals("1"));
        redis.now.set(Duration.ofMinutes(1).toMillis());
        assertFailure(
                credentials(redis, Clock.offset(CLOCK, Duration.ofMinutes(1))),
                "àdmin",
                ResultCode.AUTH_LOGIN_FAILED);
        verify(encoder, times(EXPECTED_PRE_ADMISSIONS)).matches(anyString(), anyString());
        assertThat(redis.events).hasSize(EXPECTED_PRE_ADMISSIONS);
        assertThat(redis.entries.get(stored.getKey()).expiresAt())
                .isEqualTo(COUNTER_TTL.toMillis());
    }

    @Test
    void resolvedAdmissionDoesNotDoubleConsumePreGateBudgetsAndUnknownAccountsStayBounded() {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var user = account(USER_ID, 1);
        when(remote.getUserByUsername("admin")).thenReturn(Result.success(user));
        when(remote.getUserByUsername("àdmin")).thenReturn(Result.success(user));
        when(remote.getUserByUsername("unknown")).thenReturn(Result.success(null));
        var credentials = credentials(redis, CLOCK);
        assertFailure(credentials, "admin", ResultCode.AUTH_LOGIN_FAILED);
        assertFailure(credentials, "àdmin", ResultCode.AUTH_LOGIN_FAILED);
        assertFailure(credentials, "unknown", ResultCode.AUTH_LOGIN_FAILED);
        assertCounter(redis, ":total", EXPECTED_PRE_ADMISSIONS);
        assertCounter(redis, ":source:", EXPECTED_PRE_ADMISSIONS);
        assertThat(redis.entries.keySet())
                .filteredOn(key -> key.contains(":resolved-account:"))
                .hasSize(1);
        assertThat(redis.entries.keySet())
                .noneMatch(key -> key.contains(USER_ID) || key.contains("unknown"));
        assertThat(redis.entries.values())
                .allMatch(entry -> entry.expiresAt() == COUNTER_TTL.toMillis());
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void invalidTrustedIdentityFailsAsSupplyFailureBeforeBcryptOrResolvedKeyAllocation(String id) {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var user = account(id, 1);
        when(remote.getUserByUsername("admin")).thenReturn(Result.success(user));
        assertFailure(credentials(redis, CLOCK), "admin", ResultCode.FAILED);
        verify(encoder, never()).matches(anyString(), anyString());
        assertThat(redis.events).isEmpty();
        assertThat(redis.entries.keySet()).noneMatch(key -> key.contains(":resolved-account:"));
    }

    @ParameterizedTest
    @MethodSource("validIds")
    void validHistoricalIdsUseTheDatabaseCharacterContractWithoutAsciiOrTrimRestrictions(
            String id) {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var gate = new AuthAttemptGate(redis, CLOCK, 2, SOURCE_LIMIT, GLOBAL_LIMIT);
        gate.checkResolvedAccount(id);
        assertThat(redis.entries).hasSize(1);
        assertThat(redis.entries.keySet()).allMatch(key -> key.contains(":resolved-account:"));
    }

    @Test
    void resolvedRedisFailureIsFixedAndCannotReachBcryptOrInventAFailedPasswordAudit() {
        var redis = mock(RedisHelper.class);
        when(redis.incrementWithinLimit(anyString(), anyInt(), any()))
                .thenAnswer(
                        invocation -> {
                            if (invocation.<String>getArgument(0).contains(":resolved-account:")) {
                                throw new IllegalStateException("fixture-private-redis-message");
                            }
                            return true;
                        });
        var user = account(USER_ID, 1);
        when(remote.getUserByUsername("admin")).thenReturn(Result.success(user));
        var failure = assertFailure(credentials(redis, CLOCK), "admin", ResultCode.FAILED);
        assertThat(failure.getMessage()).doesNotContain("fixture-private-redis-message", USER_ID);
        verify(encoder, never()).matches(anyString(), anyString());
        verify(redis, never()).appendEvent(any());
    }

    private AuthCredentials credentials(RedisHelper redis, Clock clock) {
        var queue = new AuthAuditQueue(redis, remote, new ObjectMapper());
        return new AuthCredentials(
                remote,
                encoder,
                new AuthAttemptGate(redis, clock, 2, SOURCE_LIMIT, GLOBAL_LIMIT),
                queue);
    }

    private RemoteUserDTO account(String id, int status) {
        return RemoteUserDTO.builder()
                .id(id)
                .username("admin")
                .passwordHash(encoder.encode(PASSWORD))
                .statusFlag(status)
                .securityVersion(0L)
                .build();
    }

    private static ServiceException assertFailure(
            AuthCredentials credentials, String username, ResultCode expected) {
        var failure =
                assertThrows(ServiceException.class, () -> credentials.verify(username, "wrong"));
        assertThat(failure.getCode()).isEqualTo(expected.getCode());
        return failure;
    }

    private static void assertFailureFromPeer(
            AuthCredentials credentials, String username, String peer, ResultCode expected) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertFailure(credentials, username, expected);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static void assertCounter(
            AuthStateTestSupport.MemoryRedis redis, String suffix, int expected) {
        var counters =
                redis.entries.entrySet().stream()
                        .filter(entry -> entry.getKey().contains(suffix))
                        .toList();
        assertThat(counters).hasSize(1);
        assertThat(counters.get(0).getValue().value()).isEqualTo(String.valueOf(expected));
    }

    private static Stream<String> invalidIds() {
        return Stream.of(
                null,
                "",
                " ",
                "x".repeat(MAX_ID_CHARACTERS + 1),
                "x".repeat(MAX_ID_UTF16_UNITS + 1),
                "stored\nid",
                "stored\u0000id",
                "stored\ud800");
    }

    private static Stream<String> validIds() {
        return Stream.of(
                USER_ID,
                " leading and trailing ",
                "字".repeat(MAX_ID_CHARACTERS),
                "😀".repeat(MAX_ID_CHARACTERS));
    }
}
