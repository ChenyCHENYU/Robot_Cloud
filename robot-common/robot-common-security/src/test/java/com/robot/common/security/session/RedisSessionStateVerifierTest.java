package com.robot.common.security.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.dev33.satoken.exception.NotLoginException;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.context.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisSessionStateVerifierTest {

    private RedisHelper redis;
    private RedisSessionStateVerifier verifier;
    private final String family = "a".repeat(64);
    private final LoginUser user = LoginUser.builder().userId("user").companyId("company")
            .contextId("context").userSecurityVersion(0L).contextSecurityVersion(0L).build();

    @BeforeEach
    void setUp() {
        redis = mock(RedisHelper.class);
        verifier = new RedisSessionStateVerifier(redis, new ObjectMapper());
    }

    @Test
    void matchingCurrentAccessIsAcceptedButOldAccessIsRejected() throws Exception {
        when(redis.getString(anyString())).thenReturn(Optional.of(state("current-access")));
        assertDoesNotThrow(() -> verifier.verify(family, "current-access", user));
        assertThrows(NotLoginException.class, () -> verifier.verify(family, "previous-access", user));
    }

    @Test
    void removedOrTombstonedFamilyRejectsResidualSaTokenMapping() {
        when(redis.getString(anyString())).thenReturn(Optional.empty());
        assertThrows(NotLoginException.class, () -> verifier.verify(family, "residual-access"));
        when(redis.getString(anyString())).thenReturn(Optional.of("{}"));
        assertThrows(NotLoginException.class, () -> verifier.verify(family, "residual-access"));
        when(redis.getString(anyString())).thenReturn(Optional.of("malformed-state"));
        assertThrows(NotLoginException.class, () -> verifier.verify(family, "residual-access"));
    }

    @Test
    void storageFailureDoesNotFallBackToAuthenticatedSuccess() {
        when(redis.getString(anyString())).thenThrow(new IllegalStateException("模拟 Redis 故障"));
        assertThrows(IllegalStateException.class, () -> verifier.verify(family, "access"));
    }

    @Test
    void missingFamilyCannotUseLegacyAccountSessionAndDoesNotReadRedis() {
        assertThrows(NotLoginException.class, () -> verifier.verify(null, "access"));
        verifyNoInteractions(redis);
    }

    @Test
    void subjectVerifierCanRejectChangedCredentialEvenWhenFamilyAndSaMappingStillExist() throws Exception {
        String token = "current-access";
        String fingerprint = "b".repeat(64);
        when(redis.getString(anyString())).thenReturn(Optional.of(state(token)));
        SessionSubjectVerifier subject = mock(SessionSubjectVerifier.class);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("subject", subject);
        RedisSessionStateVerifier guarded = new RedisSessionStateVerifier(redis, new ObjectMapper(),
                beans.getBeanProvider(SessionSubjectVerifier.class));
        when(subject.verify(user, fingerprint)).thenReturn(true);
        assertDoesNotThrow(() -> guarded.verify(family, token, user));
        when(subject.verify(user, fingerprint)).thenReturn(false);
        assertThrows(NotLoginException.class, () -> guarded.verify(family, token, user));
        verify(subject, times(2)).verify(user, fingerprint);
    }

    @Test
    void familyAndTokenSnapshotMustReferToTheSameSubjectAndContext() throws Exception {
        when(redis.getString(anyString())).thenReturn(Optional.of(state("current-access")));
        user.setContextId("another-context");
        assertThrows(NotLoginException.class, () -> verifier.verify(family, "current-access", user));
        user.setContextId("context");
        user.setUserId("another-user");
        assertThrows(NotLoginException.class, () -> verifier.verify(family, "current-access", user));
    }

    @Test
    void missingSecurityVersionsCannotUseAFamilyEvenWithoutASubjectProvider() throws Exception {
        var legacy = new ObjectMapper().readTree(state("current-access"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("contextSecurityVersion");
        when(redis.getString(anyString())).thenReturn(Optional.of(legacy.toString()));
        assertThrows(NotLoginException.class, () -> verifier.verify(family, "current-access", user));
    }

    @Test
    void familySecurityVersionsMustMatchTheAccessSnapshot() throws Exception {
        when(redis.getString(anyString())).thenReturn(Optional.of(state("current-access")));
        user.setUserSecurityVersion(1L);
        assertThrows(NotLoginException.class, () -> verifier.verify(family, "current-access", user));
        user.setUserSecurityVersion(0L);
        user.setContextSecurityVersion(null);
        assertThrows(NotLoginException.class, () -> verifier.verify(family, "current-access", user));
    }

    private String state(String token) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        return new ObjectMapper().writeValueAsString(Map.of("userId", "user", "contextId", "context",
                "accessDigest", digest, "credentialFingerprint", "b".repeat(64),
                "userSecurityVersion", 0L, "contextSecurityVersion", 0L));
    }
}
