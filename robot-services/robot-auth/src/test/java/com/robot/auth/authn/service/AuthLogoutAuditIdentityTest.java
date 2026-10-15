package com.robot.auth.authn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import com.robot.common.security.context.LoginUser;
import com.robot.common.security.util.AuthUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Logout audit fallback may use only an exact trusted account and membership snapshot. */
class AuthLogoutAuditIdentityTest {
    private static final int SECRET_HEX_LENGTH = 64;

    @Test
    void persistedAuditIdentityDoesNotDependOnACurrentAccessSession() {
        try (var access = mockStatic(AuthUtil.class)) {
            var identity = AuthLogoutAudit.identity(state("stored-name", "stored-company"));
            assertThat(identity.username()).isEqualTo("stored-name");
            assertThat(identity.companyId()).isEqualTo("stored-company");
            access.verify(AuthUtil::getLoginUser, never());
        }
    }

    @ParameterizedTest
    @CsvSource({"stored-name,", ",stored-company", ","})
    void legacyMissingFieldsAreFilledOnlyFromTheSameAccountAndMembership(
            String storedName, String storedCompany) {
        var current = currentUser("account", "membership");
        try (var access = mockStatic(AuthUtil.class)) {
            access.when(AuthUtil::getLoginUser).thenReturn(current);
            var identity = AuthLogoutAudit.identity(state(storedName, storedCompany));
            assertThat(identity.userId()).isEqualTo("account");
            assertThat(identity.username())
                    .isEqualTo(storedName == null ? "current-name" : storedName);
            assertThat(identity.companyId())
                    .isEqualTo(storedCompany == null ? "current-company" : storedCompany);
        }
    }

    @ParameterizedTest
    @CsvSource({"different-account,membership", "account,different-membership"})
    void anUnrelatedAccessSnapshotCannotSupplyMissingLogoutAuditIdentity(
            String account, String membership) {
        try (var access = mockStatic(AuthUtil.class)) {
            access.when(AuthUtil::getLoginUser).thenReturn(currentUser(account, membership));
            var identity = AuthLogoutAudit.identity(state(null, null));
            assertThat(identity.userId()).isEqualTo("account");
            assertThat(identity.username()).isNull();
            assertThat(identity.companyId()).isNull();
        }
    }

    @Test
    void missingOrBrokenAccessSnapshotRetainsStoredIdentityWithoutFailingRevocation() {
        try (var access = mockStatic(AuthUtil.class)) {
            assertThat(AuthLogoutAudit.identity(state(null, null)).username()).isNull();
            access.when(AuthUtil::getLoginUser)
                    .thenThrow(new IllegalStateException("fixture-secret"));
            var identity = AuthLogoutAudit.identity(state("stored-name", null));
            assertThat(identity.username()).isEqualTo("stored-name");
            assertThat(identity.companyId()).isNull();
            assertThat(identity.toString()).doesNotContain("fixture-secret", "stored-name");
        }
    }

    private static LoginUser currentUser(String account, String membership) {
        return LoginUser.builder()
                .userId(account)
                .contextId(membership)
                .username("current-name")
                .companyId("current-company")
                .build();
    }

    private static AuthSessionManager.FamilyState state(String username, String company) {
        return new AuthSessionManager.FamilyState(
                "account",
                "membership",
                "fixture-access",
                "a".repeat(SECRET_HEX_LENGTH),
                "b".repeat(SECRET_HEX_LENGTH),
                "c".repeat(SECRET_HEX_LENGTH),
                0L,
                0L,
                username,
                company);
    }
}
