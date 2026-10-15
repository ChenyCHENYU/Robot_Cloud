package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.robot.common.security.context.LoginUser;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SystemSessionSubjectVerifierTest {
    private static final long MEMBERSHIP_SECURITY_VERSION = 3L;
    private static final long CHANGED_MEMBERSHIP_SECURITY_VERSION = 4L;

    private final com.robot.system.mapper.SysCompanyMapper companies =
            mock(com.robot.system.mapper.SysCompanyMapper.class);
    private final com.robot.system.mapper.SysTenantMapper tenants =
            mock(com.robot.system.mapper.SysTenantMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final SysUserCompanyMapper members = mock(SysUserCompanyMapper.class);
    private final SystemSessionSubjectVerifier verifier =
            new SystemSessionSubjectVerifier(
                    new ActiveCompanySubject(members, users, companies, tenants));
    private LoginUser snapshot;
    private SysUser account;
    private SysUserCompany membership;
    private String fingerprint;

    @BeforeEach
    void setUp() throws Exception {
        snapshot =
                LoginUser.builder()
                        .userId("u1")
                        .contextId("m1")
                        .companyId("c1")
                        .userSecurityVersion(2L)
                        .contextSecurityVersion(MEMBERSHIP_SECURITY_VERSION)
                        .build();
        account = new SysUser();
        account.setId("u1");
        account.setPasswordHash("bcrypt-hash");
        account.setSecurityVersion(2L);
        account.setDeleteFlag(1);
        account.setStatusFlag(1);
        membership = new SysUserCompany();
        membership.setId("m1");
        membership.setCompanyId("c1");
        membership.setUserId("u1");
        membership.setDeleteFlag(1);
        membership.setStatusFlag(1);
        membership.setSecurityVersion(MEMBERSHIP_SECURITY_VERSION);
        fingerprint =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest("bcrypt-hash".getBytes(StandardCharsets.UTF_8)));
        var company = new com.robot.system.api.entity.SysCompany();
        company.setId("c1");
        company.setTenantId("t1");
        company.setDeleteFlag(1);
        company.setStatusFlag(1);
        var tenant = new com.robot.system.api.entity.SysTenant();
        tenant.setId("t1");
        tenant.setDeleteFlag(1);
        tenant.setStatusFlag(1);
        when(companies.selectById("c1")).thenReturn(company);
        when(tenants.selectById("t1")).thenReturn(tenant);
        when(users.selectById("u1")).thenReturn(account);
        when(members.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(membership));
    }

    @Test
    void unchangedLiveSubjectIsAccepted() {
        assertTrue(verifier.verify(snapshot, fingerprint));
        org.mockito.Mockito.verify(users).selectById("u1");
        org.mockito.Mockito.verify(companies).selectById("c1");
        org.mockito.Mockito.verify(tenants).selectById("t1");
        org.mockito.Mockito.verify(members).selectList(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(members, org.mockito.Mockito.never())
                .selectById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void passwordOrAccountRevocationCannotBeReactivated() {
        account.setPasswordHash("changed");
        assertFalse(verifier.verify(snapshot, fingerprint));
        account.setPasswordHash("bcrypt-hash");
        account.setSecurityVersion(snapshot.getUserSecurityVersion() + 1);
        assertFalse(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void disablingThenReenablingMemberDoesNotReviveOldVersion() {
        membership.setStatusFlag(0);
        membership.setSecurityVersion(CHANGED_MEMBERSHIP_SECURITY_VERSION);
        assertFalse(verifier.verify(snapshot, fingerprint));
        membership.setStatusFlag(1);
        assertFalse(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void deletingAndRecreatingAContextCannotSubstituteTheOldId() {
        when(members.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        assertFalse(verifier.verify(snapshot, fingerprint));
        when(members.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(membership));
        membership.setUserId("another");
        assertFalse(verifier.verify(snapshot, fingerprint));
        membership.setUserId("u1");
        membership.setCompanyId("another");
        assertFalse(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void malformedOrLegacySessionFailsClosed() {
        snapshot.setContextSecurityVersion(null);
        assertFalse(verifier.verify(snapshot, fingerprint));
        snapshot.setContextSecurityVersion(MEMBERSHIP_SECURITY_VERSION);
        snapshot.setUserSecurityVersion(null);
        assertFalse(verifier.verify(snapshot, fingerprint));
        snapshot.setUserSecurityVersion(2L);
        snapshot.setContextId(null);
        assertFalse(verifier.verify(snapshot, fingerprint));
        assertFalse(verifier.verify(null, fingerprint));
    }

    @Test
    void inactiveSubjectCannotUseACorrectSnapshot() {
        when(companies.selectById("c1")).thenReturn(null);
        assertFalse(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void preciseContextIdCompanyTenantAndCurrentCredentialAreRecheckedOnEveryGuard() {
        assertTrue(verifier.verify(snapshot, fingerprint));
        snapshot.setContextId("recreated-context");
        assertFalse(verifier.verify(snapshot, fingerprint));
        snapshot.setContextId("m1");
        var tenant = tenants.selectById("t1");
        tenant.setStatusFlag(0);
        assertFalse(verifier.verify(snapshot, fingerprint));
        tenant.setStatusFlag(1);
        var company = companies.selectById("c1");
        company.setStatusFlag(0);
        assertFalse(verifier.verify(snapshot, fingerprint));
        company.setStatusFlag(1);
        account.setPasswordHash(null);
        assertFalse(verifier.verify(snapshot, fingerprint));
        account.setPasswordHash("bcrypt-hash");
        assertFalse(verifier.verify(snapshot, "forged-fingerprint"));
        assertTrue(verifier.verify(snapshot, fingerprint));
        var subjects = new ActiveCompanySubject(members, users, companies, tenants);
        var facts = subjects.loadActive("u1", "c1", "m1");
        org.junit.jupiter.api.Assertions.assertFalse(
                java.util.List.of(facts).toString().contains(fingerprint));
        org.junit.jupiter.api.Assertions.assertFalse(
                java.util.List.of(facts).toString().contains("bcrypt-hash"));
        when(members.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(membership, membership));
        assertFalse(verifier.verify(snapshot, fingerprint));
    }
}
