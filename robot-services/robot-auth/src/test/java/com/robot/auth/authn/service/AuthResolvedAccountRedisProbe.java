package com.robot.auth.authn.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.core.util.Digests;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.redis.key.RedisKeys;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Actual shared Redis admission; account supply alone replaces the external System service. */
final class AuthResolvedAccountRedisProbe {
    private static final int TEST_BCRYPT_COST = 4;
    private static final int SOURCE_AND_TOTAL_LIMIT = 10;
    private static final int MAX_COUNTER_TTL_SECONDS = 120;
    private static final int EXPECTED_PRE_ADMISSIONS = 3;
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private static final String USER_ID = "probe-stored-canonical-id";

    private AuthResolvedAccountRedisProbe() {}

    static void verify(RedisHelper redis, ObjectMapper mapper) {
        var knownKeys = Stream.concat(keys("0", true).stream(), keys("1", false).stream()).toList();
        check(knownKeys.stream().allMatch(key -> redis.getString(key).isEmpty()), "fresh_id_gate");
        var encoder = new BCryptPasswordEncoder(TEST_BCRYPT_COST);
        var user =
                RemoteUserDTO.builder()
                        .id(USER_ID)
                        .username("admin")
                        .passwordHash(encoder.encode("synthetic-probe-password"))
                        .statusFlag(1)
                        .build();
        var received = new AtomicInteger();
        RemoteSystemService remote = supply(user, received);
        try {
            var one = credentials(redis, remote, encoder, CLOCK, mapper);
            var two = credentials(redis, remote, encoder, CLOCK, mapper);
            failure(one, "admin", ResultCode.AUTH_LOGIN_FAILED);
            failure(two, "àdmin", ResultCode.AUTH_LOGIN_FAILED);
            failure(two, "ádmin", ResultCode.TOO_MANY_REQUESTS);
            String prefix = prefix("0");
            String resolved = prefix + "resolved-account:" + Digests.sha256Hex(USER_ID);
            check(redis.getString(resolved).orElseThrow().equals("2"), "aliases_share_stored_id");
            check(
                    redis.getString(prefix + "total").orElseThrow().equals("3")
                            && redis.getString(prefix + "source:" + Digests.sha256Hex("unknown"))
                                    .orElseThrow()
                                    .equals("3"),
                    "resolved_gate_does_not_recount_source_total");
            check(
                    redis.ttlSeconds(resolved) > 0
                            && redis.ttlSeconds(resolved) <= MAX_COUNTER_TTL_SECONDS,
                    "resolved_gate_bounded_ttl");
            failure(
                    credentials(
                            redis,
                            remote,
                            encoder,
                            Clock.offset(CLOCK, Duration.ofMinutes(1)),
                            mapper),
                    "àdmin",
                    ResultCode.AUTH_LOGIN_FAILED);
            check(
                    redis.getString(prefix("1") + "resolved-account:" + Digests.sha256Hex(USER_ID))
                            .orElseThrow()
                            .equals("1"),
                    "resolved_gate_next_window_recovers");
            new AuthAuditQueue(redis, remote, mapper).dispatch();
            check(
                    received.get() == EXPECTED_PRE_ADMISSIONS
                            && redis.dueEventIds(
                                            AuthKeys.auditDue(),
                                            Long.MAX_VALUE,
                                            SOURCE_AND_TOTAL_LIMIT)
                                    .isEmpty(),
                    "alias_failures_reliably_acked");
        } finally {
            knownKeys.forEach(redis::delete);
        }
    }

    private static AuthCredentials credentials(
            RedisHelper redis,
            RemoteSystemService remote,
            BCryptPasswordEncoder encoder,
            Clock clock,
            ObjectMapper mapper) {
        return new AuthCredentials(
                remote,
                encoder,
                new AuthAttemptGate(
                        redis, clock, 2, SOURCE_AND_TOTAL_LIMIT, SOURCE_AND_TOTAL_LIMIT),
                new AuthAuditQueue(redis, remote, mapper));
    }

    private static RemoteSystemService supply(RemoteUserDTO user, AtomicInteger received) {
        return (RemoteSystemService)
                Proxy.newProxyInstance(
                        RemoteSystemService.class.getClassLoader(),
                        new Class<?>[] {RemoteSystemService.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("getUserByUsername")) {
                                return Result.success(user);
                            }
                            if (method.getName().equals("recordLogin")) {
                                received.incrementAndGet();
                                return Result.success();
                            }
                            throw new UnsupportedOperationException("probe不允许其他System调用");
                        });
    }

    private static List<String> keys(String window, boolean allAliases) {
        var names = allAliases ? List.of("admin", "àdmin", "ádmin") : List.of("àdmin");
        return Stream.concat(
                        names.stream()
                                .map(name -> prefix(window) + "account:" + Digests.sha256Hex(name)),
                        Stream.of(
                                prefix(window) + "total",
                                prefix(window) + "source:" + Digests.sha256Hex("unknown"),
                                prefix(window) + "resolved-account:" + Digests.sha256Hex(USER_ID)))
                .toList();
    }

    private static String prefix(String window) {
        return RedisKeys.base("auth") + ":attempt:" + window + ":";
    }

    private static void failure(AuthCredentials credentials, String alias, ResultCode expected) {
        try {
            credentials.verify(alias, "synthetic-wrong-password");
            throw new AssertionError("credentials should refuse");
        } catch (ServiceException rejected) {
            check(rejected.getCode() == expected.getCode(), "alias_credential_boundary");
        }
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
        System.out.println("{\"check\":\"" + name + "\",\"result\":\"passed\"}");
    }
}
