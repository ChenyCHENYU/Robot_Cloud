package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.robot.system.config.SystemProductionBootstrapGuard;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 配置完成声明不能替代实际平台交接、失效开发凭据和DB会话预算。 */
class SystemProductionBootstrapGuardTest {
    private static final int TEST_BCRYPT_COST = 4;
    private static final long LOCK_WAIT_TIMEOUT_SECONDS = 5L;
    private static final long QUERY_TIMEOUT_MILLIS = 5000L;

    @Test
    void knownDeveloperPasswordIsRejectedEvenWhenPlatformFactExists() {
        var jdbc = database();
        var passwords = new BCryptPasswordEncoder(TEST_BCRYPT_COST);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of(passwords.encode("admin123")));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new SystemProductionBootstrapGuard(environment(), jdbc, passwords)
                                .run(new org.springframework.boot.DefaultApplicationArguments()));
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of(passwords.encode("different-valid-password")));
        assertDoesNotThrow(
                () ->
                        new SystemProductionBootstrapGuard(environment(), jdbc, passwords)
                                .run(new org.springframework.boot.DefaultApplicationArguments()));
        when(jdbc.queryForObject(contains("COUNT"), eq(Long.class))).thenReturn(0L);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new SystemProductionBootstrapGuard(environment(), jdbc, passwords)
                                .run(new org.springframework.boot.DefaultApplicationArguments()));
    }

    @Test
    void missingNetworkBudgetOrUnboundedSessionCannotOpenProduction() {
        var env =
                environment()
                        .withProperty(
                                "spring.datasource.url",
                                "jdbc:mysql://localhost/db?sslMode=VERIFY_IDENTITY");
        assertThrows(
                IllegalStateException.class,
                () ->
                        new SystemProductionBootstrapGuard(
                                        env,
                                        database(),
                                        new BCryptPasswordEncoder(TEST_BCRYPT_COST))
                                .run(new org.springframework.boot.DefaultApplicationArguments()));
        var jdbc = database();
        when(jdbc.queryForObject(contains("max_execution_time"), eq(Long.class))).thenReturn(0L);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new SystemProductionBootstrapGuard(
                                        environment(),
                                        jdbc,
                                        new BCryptPasswordEncoder(TEST_BCRYPT_COST))
                                .run(new org.springframework.boot.DefaultApplicationArguments()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod-x", "PRE_rc", "Production blue"})
    void declaredProtectedEnvironmentRequiresDatabaseGuardWithoutProductionProfile(String signal) {
        var env = new MockEnvironment().withProperty("robot.env", signal);
        var jdbc = mock(JdbcTemplate.class);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new SystemProductionBootstrapGuard(
                                        env, jdbc, new BCryptPasswordEncoder(TEST_BCRYPT_COST))
                                .run(new org.springframework.boot.DefaultApplicationArguments()));
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROD", "production", "pRe"})
    void protectedProfilesAreCaseInsensitiveEvenWithoutBooleanFlag(String profile) {
        var env = new MockEnvironment().withProperty("robot.production.enabled", "false");
        env.setActiveProfiles(profile);
        var jdbc = mock(JdbcTemplate.class);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new SystemProductionBootstrapGuard(
                                        env, jdbc, new BCryptPasswordEncoder(TEST_BCRYPT_COST))
                                .run(new org.springframework.boot.DefaultApplicationArguments()));
        verifyNoInteractions(jdbc);
    }

    @Test
    void localEnvironmentDoesNotPerformProtectedStartupDatabaseOperations() {
        var jdbc = mock(JdbcTemplate.class);
        assertDoesNotThrow(
                () ->
                        new SystemProductionBootstrapGuard(
                                        new MockEnvironment().withProperty("robot.env", "local"),
                                        jdbc,
                                        new BCryptPasswordEncoder(TEST_BCRYPT_COST))
                                .run(new org.springframework.boot.DefaultApplicationArguments()));
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "connectTimeout=3000&socketTimeout=8000&connectTimeout=0",
                "connectTimeout=0&socketTimeout=8000&connectTimeout=3000",
                "connectTimeout=3000&socketTimeout=8000&socketTimeout=0",
                "connectTimeout=3000&socketTimeout=0&socketTimeout=8000",
                "connectTimeout=3000&socketTimeout=8000&ConnectTimeout=3000",
                "connectTimeout=3000&socketTimeout=8000&connect%54imeout=3000",
                "connectTimeout=3000&socketTimeout=8000#connectTimeout=0",
                "connectTimeout=3000&socketTimeout=8000&unrelated=%zz",
                "connectTimeout=5001&socketTimeout=8000",
                "connectTimeout=3000&socketTimeout=10001",
                "connectTimeout=-1&socketTimeout=8000",
                "connectTimeout=3000&socketTimeout=9223372036854775808",
                "connectTimeout=3000&socketTimeout=invalid"
            })
    void ambiguousOrUnboundedJdbcTimeoutCannotOpenProduction(String query) {
        var jdbc = mock(JdbcTemplate.class);
        var env =
                environment()
                        .withProperty(
                                "spring.datasource.url", "jdbc:mysql://localhost/db?" + query);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new SystemProductionBootstrapGuard(
                                        env, jdbc, new BCryptPasswordEncoder(TEST_BCRYPT_COST))
                                .run(new org.springframework.boot.DefaultApplicationArguments()));
        verifyNoInteractions(jdbc);
    }

    @Test
    void oneExactDecodedPositiveTimeoutPairOpensProductionWhenFactsAreValid() {
        var env =
                environment()
                        .withProperty(
                                "spring.datasource.url",
                                "jdbc:mysql://localhost/db?connectTimeout=%33%30%30%30"
                                        + "&socketTimeout=8000");
        assertDoesNotThrow(
                () ->
                        new SystemProductionBootstrapGuard(
                                        env,
                                        database(),
                                        new BCryptPasswordEncoder(TEST_BCRYPT_COST))
                                .run(new org.springframework.boot.DefaultApplicationArguments()));
    }

    @Test
    void unconfirmedLegacyActiveGrantBlocksStartupWithoutChangingTimesOrDeletingGrants() {
        var jdbc = database();
        when(jdbc.queryForList(
                        contains("sys_cache_epoch"),
                        eq(SystemProductionBootstrapGuard.AUTHORIZATION_UTC_CONTRACT_SCOPE)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(contains("sys_temp_authorization"), eq(Long.class)))
                .thenReturn(1L);
        assertThrows(
                IllegalStateException.class,
                () -> guard(jdbc).run(new org.springframework.boot.DefaultApplicationArguments()));
        verify(jdbc, never()).update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void noLegacyGrantInitializesUtcMarkerAndRestartRetainsNewUtcGrantsWithoutRewritingMarker() {
        var jdbc = database();
        when(jdbc.queryForList(
                        contains("sys_cache_epoch"),
                        eq(SystemProductionBootstrapGuard.AUTHORIZATION_UTC_CONTRACT_SCOPE)))
                .thenReturn(List.of(), validMarker(), validMarker());
        when(jdbc.queryForObject(contains("sys_temp_authorization"), eq(Long.class)))
                .thenReturn(0L);
        assertDoesNotThrow(
                () -> guard(jdbc).run(new org.springframework.boot.DefaultApplicationArguments()));
        when(jdbc.queryForObject(contains("sys_temp_authorization"), eq(Long.class)))
                .thenReturn(1L);
        assertDoesNotThrow(
                () -> guard(jdbc).run(new org.springframework.boot.DefaultApplicationArguments()));
        verify(jdbc)
                .update(
                        contains("WHERE NOT EXISTS"),
                        eq(SystemProductionBootstrapGuard.AUTHORIZATION_UTC_CONTRACT_SCOPE));
        verify(jdbc, times(1)).queryForObject(contains("sys_temp_authorization"), eq(Long.class));
    }

    @Test
    void concurrentGrantOrFailedMarkerInsertCannotFalselyOpenUtcStartup() {
        var jdbc = database();
        when(jdbc.queryForList(
                        contains("sys_cache_epoch"),
                        eq(SystemProductionBootstrapGuard.AUTHORIZATION_UTC_CONTRACT_SCOPE)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(contains("sys_temp_authorization"), eq(Long.class)))
                .thenReturn(0L);
        assertThrows(
                IllegalStateException.class,
                () -> guard(jdbc).run(new org.springframework.boot.DefaultApplicationArguments()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "AUTHORIZATION-TIME-UTC-V1",
                "authorization-TIME-utc-v1",
                "áuthorization-time-utc-v1"
            })
    void databaseCollationEquivalentMarkerAliasDoesNotConfirmExactUtcContract(String alias) {
        var jdbc = database();
        when(jdbc.queryForList(
                        contains("sys_cache_epoch"),
                        eq(SystemProductionBootstrapGuard.AUTHORIZATION_UTC_CONTRACT_SCOPE)))
                .thenReturn(List.of(Map.of("scope", alias, "version", 1L)));
        assertThrows(
                IllegalStateException.class,
                () -> guard(jdbc).run(new org.springframework.boot.DefaultApplicationArguments()));
        verify(jdbc).queryForObject(contains("sys_temp_authorization"), eq(Long.class));
        verify(jdbc, never()).update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void missingNumericVersionDoesNotConfirmUtcContract() {
        var jdbc = database();
        when(jdbc.queryForList(
                        contains("sys_cache_epoch"),
                        eq(SystemProductionBootstrapGuard.AUTHORIZATION_UTC_CONTRACT_SCOPE)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "scope",
                                        SystemProductionBootstrapGuard
                                                .AUTHORIZATION_UTC_CONTRACT_SCOPE)));
        assertThrows(
                IllegalStateException.class,
                () -> guard(jdbc).run(new org.springframework.boot.DefaultApplicationArguments()));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void unconfirmedNumericVersionDoesNotConfirmUtcContract(long version) {
        var jdbc = database();
        when(jdbc.queryForList(
                        contains("sys_cache_epoch"),
                        eq(SystemProductionBootstrapGuard.AUTHORIZATION_UTC_CONTRACT_SCOPE)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "scope",
                                        SystemProductionBootstrapGuard
                                                .AUTHORIZATION_UTC_CONTRACT_SCOPE,
                                        "version",
                                        version)));
        assertThrows(
                IllegalStateException.class,
                () -> guard(jdbc).run(new org.springframework.boot.DefaultApplicationArguments()));
    }

    private static List<Map<String, Object>> validMarker() {
        return List.of(
                Map.of(
                        "scope",
                        SystemProductionBootstrapGuard.AUTHORIZATION_UTC_CONTRACT_SCOPE,
                        "version",
                        1L));
    }

    private static SystemProductionBootstrapGuard guard(JdbcTemplate jdbc) {
        return new SystemProductionBootstrapGuard(
                environment(), jdbc, new BCryptPasswordEncoder(TEST_BCRYPT_COST));
    }

    private static MockEnvironment environment() {
        var environment =
                new MockEnvironment()
                        .withProperty("robot.production.enabled", "true")
                        .withProperty(
                                "spring.datasource.url",
                                "jdbc:mysql://localhost/db?sslMode=VERIFY_IDENTITY"
                                        + "&connectTimeout=3000&socketTimeout=8000");
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static JdbcTemplate database() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("innodb_lock_wait_timeout"), eq(Long.class)))
                .thenReturn(LOCK_WAIT_TIMEOUT_SECONDS);
        when(jdbc.queryForObject(contains("max_execution_time"), eq(Long.class)))
                .thenReturn(QUERY_TIMEOUT_MILLIS);
        when(jdbc.queryForObject(contains("time_zone"), eq(String.class))).thenReturn("+00:00");
        when(jdbc.queryForObject(contains("COUNT"), eq(Long.class))).thenReturn(1L);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        when(jdbc.queryForList(
                        contains("sys_cache_epoch"),
                        eq(SystemProductionBootstrapGuard.AUTHORIZATION_UTC_CONTRACT_SCOPE)))
                .thenReturn(validMarker());
        return jdbc;
    }
}
