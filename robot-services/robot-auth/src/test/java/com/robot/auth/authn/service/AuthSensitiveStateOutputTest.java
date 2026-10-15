package com.robot.auth.authn.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.robot.auth.authn.dto.ActivateContextRequest;
import com.robot.auth.authn.dto.LoginRequest;
import com.robot.auth.authn.dto.LoginResponse;
import com.robot.auth.authn.dto.RefreshTokenRequest;
import com.robot.auth.authn.dto.RefreshTokenResponse;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercise actual nested diagnostic rendering: every private state override must hide its complete
 * secret graph.
 */
class AuthSensitiveStateOutputTest {
    private static final long ACCOUNT_REVOCATION_VERSION = 3L;
    private static final long MEMBERSHIP_REVOCATION_VERSION = 4L;
    private static final int ACCESS_TTL_SECONDS = 1800;

    @Test
    void privateSessionTicketAndAuditStatesNeverExposeSecretsThroughNestedDiagnostics()
            throws Exception {
        String secret = "fixture-secret-do-not-log";
        Object family =
                state(
                        "AuthSessionManager$FamilyState",
                        new Class<?>[] {
                            String.class,
                            String.class,
                            String.class,
                            String.class,
                            String.class,
                            String.class,
                            Long.class,
                            Long.class,
                            String.class,
                            String.class
                        },
                        secret + "-user",
                        secret + "-member",
                        secret + "-access",
                        secret + "-access-digest",
                        secret + "-refresh-digest",
                        secret + "-fingerprint",
                        ACCOUNT_REVOCATION_VERSION,
                        MEMBERSHIP_REVOCATION_VERSION,
                        secret + "-username",
                        secret + "-company");
        Object logout =
                state(
                        "AuthSessionManager$LogoutIdentity",
                        new Class<?>[] {String.class, String.class, String.class},
                        secret + "-user",
                        secret + "-name",
                        secret + "-company");
        var access = new AuthAccessTokens.IssuedAccess(secret + "-access", ACCESS_TTL_SECONDS);
        Object prepared =
                state(
                        "AuthSessionManager$PreparedSession",
                        new Class<?>[] {
                            String.class,
                            String.class,
                            AuthAccessTokens.IssuedAccess.class,
                            family.getClass(),
                            String.class
                        },
                        secret + "-family",
                        secret + "-refresh",
                        access,
                        family,
                        "{\"accessToken\":\"" + secret + "\"}");
        Object ticket =
                state(
                        "AuthService$TicketState",
                        new Class<?>[] {String.class, String.class, Long.class},
                        secret + "-user",
                        secret + "-fingerprint",
                        ACCOUNT_REVOCATION_VERSION);
        var all = List.of(family, logout, prepared, ticket, access);
        String nested = Map.of("failureContext", List.of(Map.of("states", all))).toString();
        assertThat(nested)
                .doesNotContain(secret)
                .contains("FamilyState[", "LogoutIdentity[", "PreparedSession[", "TicketState[");
        assertThat(new IllegalStateException(nested).toString()).doesNotContain(secret);
        for (Object value : all) {
            assertThat(value.toString()).doesNotContain(secret);
        }
    }

    @Test
    void publicAuthRequestAndResponseModelsHideCredentialsInNestedOutput() {
        String secret = "fixture-password-or-token";
        var request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword(secret);
        var login =
                LoginResponse.builder()
                        .token(secret + "-access")
                        .refreshToken(secret + "-refresh")
                        .loginTicket(secret + "-ticket")
                        .build();
        var nested =
                Map.of(
                        "request",
                        request,
                        "login",
                        login,
                        "activate",
                        new ActivateContextRequest(secret + "-ticket", "member"),
                        "refreshRequest",
                        new RefreshTokenRequest(secret),
                        "refreshResponse",
                        new RefreshTokenResponse(
                                secret + "-access", secret + "-refresh", ACCESS_TTL_SECONDS));
        assertThat(nested.toString()).doesNotContain(secret);
    }

    private static Object state(String name, Class<?>[] parameters, Object... values)
            throws Exception {
        Class<?> type = Class.forName("com.robot.auth.authn.service." + name);
        Constructor<?> constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }
}
