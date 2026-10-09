package com.robot.auth.authn.service;

import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.context.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 Auth 请求主体判断，远程系统事实为可控制替身；不连接基础设施。 */
class AuthSessionSubjectVerifierTest {

    private RemoteSystemService remote;
    private AuthSessionSubjectVerifier verifier;
    private RemoteUserDTO current;
    private RemoteUserContextDTO context;
    private LoginUser snapshot;
    private String fingerprint;

    @BeforeEach
    void setUp() {
        remote = mock(RemoteSystemService.class);
        verifier = new AuthSessionSubjectVerifier(remote);
        current = RemoteUserDTO.builder().id("user-a").statusFlag(1).securityVersion(3L)
                .passwordHash("test-only-password-hash").build();
        context = RemoteUserContextDTO.builder().contextId("membership-a").companyId("company-a")
                .tenantId("tenant-a").securityVersion(7L).build();
        snapshot = LoginUser.builder().userId("user-a").contextId("membership-a")
                .companyId("company-a").tenantId("tenant-a")
                .userSecurityVersion(3L).contextSecurityVersion(7L).build();
        fingerprint = AuthTokenSecrets.digest(current.getPasswordHash());
        when(remote.getUserById("user-a")).thenReturn(Result.success(current));
        when(remote.getUserContexts("user-a")).thenReturn(Result.success(List.of(context)));
    }

    @Test
    void activeSubjectWithTheSameCredentialsAndVersionsIsAccepted() {
        assertTrue(verifier.verify(snapshot, fingerprint));
        verify(remote).getUserById("user-a");
        verify(remote).getUserContexts("user-a");
    }

    @Test
    void changedPasswordRejectsTheOldAccessSnapshot() {
        current.setPasswordHash("changed-test-only-hash");
        assertFalse(verifier.verify(snapshot, fingerprint));
        verify(remote, never()).getUserContexts("user-a");
    }

    @Test
    void changedAccountVersionRejectsEvenAnUnchangedPasswordFingerprint() {
        current.setSecurityVersion(4L);
        assertFalse(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void changedMembershipVersionRejectsAfterDisableAndReenable() {
        context.setSecurityVersion(8L);
        assertFalse(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void aNewMembershipInTheSameCompanyCannotReviveTheOldContext() {
        context.setContextId("new-membership-a");
        assertFalse(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void changedCompanyOrTenantCannotUseTheOldContextSnapshot() {
        context.setCompanyId("company-b");
        assertFalse(verifier.verify(snapshot, fingerprint));
        context.setCompanyId("company-a");
        context.setTenantId("tenant-b");
        assertFalse(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void successfulMissingOrDisabledAccountIsARevokedSubject() {
        when(remote.getUserById("user-a")).thenReturn(Result.success(null));
        assertFalse(verifier.verify(snapshot, fingerprint));
        when(remote.getUserById("user-a")).thenReturn(Result.success(current));
        current.setStatusFlag(0);
        assertFalse(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void successfulEmptyContextListIsARevokedSubject() {
        when(remote.getUserContexts("user-a")).thenReturn(Result.success(List.of()));
        assertFalse(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void missingVersionFieldsFailClosed() {
        current.setSecurityVersion(null);
        assertFalse(verifier.verify(snapshot, fingerprint));
        current.setSecurityVersion(3L);
        context.setSecurityVersion(null);
        assertFalse(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void transientAccountRpcFailureIsUnavailableNotARevokedSubject() {
        when(remote.getUserById("user-a")).thenReturn(Result.failed(ResultCode.FAILED));
        ServiceException error = assertThrows(ServiceException.class, () -> verifier.verify(snapshot, fingerprint));
        assertEquals(ResultCode.FAILED.getCode(), error.getCode());
        when(remote.getUserById("user-a")).thenReturn(null);
        ServiceException missingResponse = assertThrows(ServiceException.class,
                () -> verifier.verify(snapshot, fingerprint));
        assertEquals(ResultCode.FAILED.getCode(), missingResponse.getCode());
        when(remote.getUserById("user-a")).thenReturn(Result.success(current));
        assertTrue(verifier.verify(snapshot, fingerprint));
    }

    @Test
    void transientContextRpcFailureIsUnavailableAndAHealthyRetryIsAccepted() {
        when(remote.getUserContexts("user-a")).thenReturn(Result.failed(ResultCode.FAILED));
        ServiceException error = assertThrows(ServiceException.class, () -> verifier.verify(snapshot, fingerprint));
        assertEquals(ResultCode.FAILED.getCode(), error.getCode());
        when(remote.getUserContexts("user-a")).thenReturn(Result.success(List.of(context)));
        assertTrue(verifier.verify(snapshot, fingerprint));
    }
}
