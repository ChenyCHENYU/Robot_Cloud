package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.dto.LoginRequest;
import com.robot.common.core.domain.Result;
import com.robot.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthFailureAuditTest {
    private static final int TEST_BCRYPT_COST = 4;
    private static final int INVALID_CREDENTIAL_CODE = 40101;
    private static final int MAX_AUDIT_USERNAME_LENGTH = 30;

    @Test
    void trustedKnownUserIdLinksFailedLoginWithoutStoringCredentialsOrPubliclyRevealingStatus()
            throws Exception {
        var redis = new AuthStateTestSupport.MemoryRedis();
        var remote = mock(RemoteSystemService.class);
        var encoder = new BCryptPasswordEncoder(TEST_BCRYPT_COST);
        var mapper = new ObjectMapper();
        var user =
                RemoteUserDTO.builder()
                        .id("trusted-user-id")
                        .username("known_user")
                        .passwordHash(encoder.encode("synthetic-correct-password"))
                        .statusFlag(0)
                        .securityVersion(0L)
                        .build();
        when(remote.getUserByUsername("known_user")).thenReturn(Result.success(user));
        var queue = new AuthAuditQueue(redis, remote, mapper);
        var service =
                new AuthService(
                        remote,
                        redis,
                        mock(AuthSessionManager.class),
                        mapper,
                        new AuthCredentials(remote, encoder, new AuthAttemptGate(redis), queue));
        var request = new LoginRequest();
        request.setUsername("known_user");
        request.setPassword("synthetic-wrong-password");
        var failure = assertThrows(ServiceException.class, () -> service.login(request));
        assertEquals(INVALID_CREDENTIAL_CODE, failure.getCode());
        String payload = redis.events.values().iterator().next();
        RemoteLoginLogDTO event = mapper.readValue(payload, RemoteLoginLogDTO.class);
        assertEquals("trusted-user-id", event.getUserId());
        assertNull(event.getCompanyId());
        assertEquals(0, event.getStatus());
        assertTrue(event.getUsername().startsWith("attempt-"));
        assertTrue(event.getUsername().length() <= MAX_AUDIT_USERNAME_LENGTH);
        assertFalse(payload.contains(request.getPassword()));
        assertFalse(payload.contains(user.getPasswordHash()));
        assertFalse(payload.contains("known_user"));
    }

    @Test
    void fullFailureAuditQueueRejectsExplicitlyAndCountsWithoutCredentialLabels() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            beans.registerSingleton("registry", registry);
            var redis = new AuthStateTestSupport.MemoryRedis();
            var queue =
                    new AuthAuditQueue(
                            redis,
                            mock(RemoteSystemService.class),
                            new ObjectMapper(),
                            beans.getBeanProvider(
                                    io.micrometer.core.instrument.MeterRegistry.class));
            for (int i = 0; i < AuthAuditQueue.CAPACITY; i++) {
                redis.events.put("fixture-" + i, "pending");
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> queue.append(queue.event("login", null, "attempt-abcdef", null, 0)));
            assertEquals(AuthAuditQueue.CAPACITY, redis.events.size());
            assertEquals(1, registry.get("robot.auth.audit.append.rejected").counter().count());
            assertTrue(registry.getMeters().get(0).getId().getTags().isEmpty());
        } finally {
            registry.close();
        }
    }
}
