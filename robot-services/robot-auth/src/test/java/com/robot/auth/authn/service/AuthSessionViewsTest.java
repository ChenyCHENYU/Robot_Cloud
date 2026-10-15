package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verify authority and serialization boundaries of the pure session assembly used by actual issuing
 * flows.
 */
class AuthSessionViewsTest {
    private static final long MEMBERSHIP_VERSION = 3L;
    private static final long ACCOUNT_VERSION = 9L;
    private static final long CURRENT_MEMBERSHIP_VERSION = 11L;
    private static final int ACCESS_TTL_SECONDS = 1800;

    @Test
    void currentTrustedContextControlsCompanyRolesAndSuperAdminAndCannotAliasTheSourceList() {
        var user =
                RemoteUserDTO.builder().id("user-1").username("alice").securityVersion(2L).build();
        var context =
                RemoteUserContextDTO.builder()
                        .contextId("member-1")
                        .companyId("company-1")
                        .tenantId("tenant-1")
                        .securityVersion(MEMBERSHIP_VERSION)
                        .build();
        var ordinary = AuthSessionViews.loginUser(context, user);
        assertFalse(ordinary.isSuperAdmin());
        assertEquals(List.of(), ordinary.getRoleCodes());
        context.setRoles(
                List.of(
                        RemoteUserContextDTO.RoleItem.builder()
                                .id("role-1")
                                .name("普通")
                                .code("ADMIN")
                                .build()));
        var adminNamed = AuthSessionViews.loginUser(context, user);
        assertFalse(adminNamed.isSuperAdmin());
        assertEquals(List.of("ADMIN"), adminNamed.getRoleCodes());
        context.setRoles(
                List.of(
                        RemoteUserContextDTO.RoleItem.builder()
                                .id("role-2")
                                .name("超级")
                                .code("SUPER_ADMIN")
                                .build()));
        var privileged = AuthSessionViews.loginUser(context, user);
        assertTrue(privileged.isSuperAdmin());
        assertEquals("company-1", privileged.getCompanyId());
        assertEquals("member-1", privileged.getContextId());
        assertEquals(2L, privileged.getUserSecurityVersion());
        assertEquals(MEMBERSHIP_VERSION, privileged.getContextSecurityVersion());
        context.setRoles(List.of());
        assertEquals(List.of("SUPER_ADMIN"), privileged.getRoleCodes());
        assertThrows(UnsupportedOperationException.class, () -> privileged.getRoleCodes().clear());
    }

    @Test
    void credentialsAndRevocationVersionsAreRequiredBeforeIssuingFamilyState() {
        assertFalse(AuthSessionViews.validSecurityVersion(null));
        assertFalse(AuthSessionViews.validSecurityVersion(-1L));
        assertTrue(AuthSessionViews.validSecurityVersion(0L));
        assertEquals(
                ResultCode.AUTH_REFRESH_TOKEN_INVALID.getCode(),
                assertThrows(
                                ServiceException.class,
                                () -> AuthSessionViews.requireSecurityVersion(null))
                        .getCode());
        assertThrows(ServiceException.class, () -> AuthSessionViews.requireSecurityVersion(-1L));
        assertDoesNotThrow(() -> AuthSessionViews.requireSecurityVersion(1L));
        var user = RemoteUserDTO.builder().id("user").build();
        assertThrows(ServiceException.class, () -> AuthSessionViews.credentialFingerprint(user));
        user.setPasswordHash("synthetic-digest");
        assertEquals(
                AuthTokenSecrets.digest("synthetic-digest"),
                AuthSessionViews.credentialFingerprint(user));
    }

    @Test
    void issuedFrontendViewKeepsOnlyPublicUserFieldsAndExplicitCredentials() throws Exception {
        var user =
                RemoteUserDTO.builder()
                        .id("user")
                        .username("alice")
                        .nickname("Alice")
                        .passwordHash("private-password-digest")
                        .securityVersion(ACCOUNT_VERSION)
                        .build();
        var context =
                RemoteUserContextDTO.builder()
                        .contextId("member")
                        .companyId("company")
                        .securityVersion(CURRENT_MEMBERSHIP_VERSION)
                        .roles(List.of())
                        .build();
        var result =
                AuthSessionViews.response(
                        new AuthAccessTokens.IssuedAccess("issued-access", ACCESS_TTL_SECONDS),
                        "issued-refresh",
                        user,
                        context,
                        List.of(context));
        String json = new ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("passwordHash"));
        assertFalse(json.contains("private-password-digest"));
        assertFalse(json.contains("securityVersion"));
        var tree = new ObjectMapper().readTree(json);
        assertEquals("issued-access", tree.path("token").asText());
        assertEquals("issued-refresh", tree.path("refreshToken").asText());
        assertEquals("Alice", tree.path("user").path("displayName").asText());
        assertEquals("member", tree.path("activeContext").path("id").asText());
    }
}
