package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.util.AuthHolder;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle boundary tests: real adapter calls are captured; failures must revoke the newly created
 * token.
 */
class SaTokenAccessTokensTest {
    private static final long ACCESS_TTL_SECONDS = 1800L;

    @Test
    void issuedAccessUsesIndependentSaSessionAndStoresTheDigestInsteadOfTheRawToken() {
        var user =
                LoginUser.builder()
                        .userId("usr-1")
                        .companyId("company-1")
                        .contextId("member-1")
                        .build();
        try (var stp = mockStatic(StpUtil.class);
                var holder = mockStatic(AuthHolder.class)) {
            stp.when(StpUtil::getTokenValue).thenReturn("fixture-access");
            stp.when(StpUtil::getTokenTimeout).thenReturn(ACCESS_TTL_SECONDS);
            var issued = new SaTokenAccessTokens().issue(user, "family-1", "fingerprint");
            assertEquals("fixture-access", issued.token);
            assertEquals(ACCESS_TTL_SECONDS, issued.expiresIn);
            stp.verify(() -> StpUtil.login(eq("usr-1"), any(SaLoginParameter.class)));
            holder.verify(() -> AuthHolder.setSessionAttr(AuthHolder.SESSION_LOGIN_USER, user));
            holder.verify(
                    () -> AuthHolder.setSessionAttr(AuthHolder.SESSION_AUTH_FAMILY, "family-1"));
            holder.verify(
                    () ->
                            AuthHolder.setSessionAttr(
                                    AuthHolder.SESSION_AUTH_ACCESS_DIGEST,
                                    AuthTokenSecrets.digest("fixture-access")));
            holder.verify(
                    () ->
                            AuthHolder.setSessionAttr(
                                    AuthHolder.SESSION_CREDENTIAL_FINGERPRINT, "fingerprint"));
            stp.verify(() -> StpUtil.logoutByTokenValue(anyString()), never());
        }
    }

    @Test
    void sessionAttributeFailureRevokesCandidateTokenBeforePropagating() {
        var user = LoginUser.builder().userId("usr-1").build();
        try (var stp = mockStatic(StpUtil.class);
                var holder = mockStatic(AuthHolder.class)) {
            stp.when(StpUtil::getTokenValue).thenReturn("fixture-access");
            holder.when(() -> AuthHolder.setSessionAttr(AuthHolder.SESSION_AUTH_FAMILY, "family-1"))
                    .thenThrow(new IllegalStateException("session unavailable"));
            assertThrows(
                    IllegalStateException.class,
                    () -> new SaTokenAccessTokens().issue(user, "family-1", "fingerprint"));
            stp.verify(() -> StpUtil.logoutByTokenValue("fixture-access"));
            stp.verify(StpUtil::getTokenTimeout, never());
        }
    }

    @Test
    void emptyRevocationIsNoOpAndCurrentTokenMayBeAbsent() {
        var adapter = new SaTokenAccessTokens();
        try (var stp = mockStatic(StpUtil.class)) {
            assertNull(adapter.currentToken());
            adapter.revoke(null);
            adapter.revoke("");
            adapter.revoke("  ");
            stp.verify(() -> StpUtil.logoutByTokenValue(anyString()), never());
            stp.when(StpUtil::getTokenValue).thenReturn("fixture-access");
            assertEquals("fixture-access", adapter.currentToken());
            adapter.revoke("fixture-access");
            stp.verify(() -> StpUtil.logoutByTokenValue("fixture-access"));
        }
    }
}
