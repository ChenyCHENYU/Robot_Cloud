package com.robot.system.permission.service;

import com.robot.common.security.context.LoginUser;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemSessionSubjectVerifierTest {
    private final EffectiveRoleResolver roles=mock(EffectiveRoleResolver.class);
    private final SysUserMapper users=mock(SysUserMapper.class);
    private final SysUserCompanyMapper members=mock(SysUserCompanyMapper.class);
    private final SystemSessionSubjectVerifier verifier=new SystemSessionSubjectVerifier(roles,users,members);
    private LoginUser snapshot;
    private SysUser account;
    private SysUserCompany membership;
    private String fingerprint;

    @BeforeEach void setUp() throws Exception {
        snapshot=LoginUser.builder().userId("u1").contextId("m1").companyId("c1")
                .userSecurityVersion(2L).contextSecurityVersion(3L).build();
        account=new SysUser();account.setId("u1");account.setPasswordHash("bcrypt-hash");account.setSecurityVersion(2L);
        account.setDeleteFlag(1);account.setStatusFlag(1);
        membership=new SysUserCompany();membership.setId("m1");membership.setCompanyId("c1");membership.setUserId("u1");
        membership.setDeleteFlag(1);membership.setStatusFlag(1);membership.setSecurityVersion(3L);
        fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("bcrypt-hash".getBytes(StandardCharsets.UTF_8)));
        when(roles.isActiveSubject("u1","c1")).thenReturn(true);
        when(users.selectById("u1")).thenReturn(account);
        when(members.selectById("m1")).thenReturn(membership);
    }

    @Test void unchangedLiveSubjectIsAccepted() {
        assertTrue(verifier.verify(snapshot,fingerprint));
    }
    @Test void passwordOrAccountRevocationCannotBeReactivated() {
        account.setPasswordHash("changed");assertFalse(verifier.verify(snapshot,fingerprint));
        account.setPasswordHash("bcrypt-hash");account.setSecurityVersion(3L);
        assertFalse(verifier.verify(snapshot,fingerprint));
    }
    @Test void disablingThenReenablingMemberDoesNotReviveOldVersion() {
        membership.setStatusFlag(0);membership.setSecurityVersion(4L);
        assertFalse(verifier.verify(snapshot,fingerprint));
        membership.setStatusFlag(1);assertFalse(verifier.verify(snapshot,fingerprint));
    }
    @Test void deletingAndRecreatingAContextCannotSubstituteTheOldId() {
        when(members.selectById("m1")).thenReturn(null);
        assertFalse(verifier.verify(snapshot,fingerprint));
        when(members.selectById("m1")).thenReturn(membership);
        membership.setUserId("another");assertFalse(verifier.verify(snapshot,fingerprint));
        membership.setUserId("u1");membership.setCompanyId("another");
        assertFalse(verifier.verify(snapshot,fingerprint));
    }
    @Test void malformedOrLegacySessionFailsClosed() {
        snapshot.setContextSecurityVersion(null);assertFalse(verifier.verify(snapshot,fingerprint));
        snapshot.setContextSecurityVersion(3L);snapshot.setUserSecurityVersion(null);
        assertFalse(verifier.verify(snapshot,fingerprint));
        snapshot.setUserSecurityVersion(2L);snapshot.setContextId(null);
        assertFalse(verifier.verify(snapshot,fingerprint));
        assertFalse(verifier.verify(null,fingerprint));
    }
    @Test void inactiveSubjectCannotUseACorrectSnapshot() {
        when(roles.isActiveSubject("u1","c1")).thenReturn(false);
        assertFalse(verifier.verify(snapshot,fingerprint));
    }
}
