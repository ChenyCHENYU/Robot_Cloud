package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.mapper.SysPermissionAuditLogMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/** 敏感审计拒绝静默降级，详情不包含可重用凭据。 */
class PermissionChangeRecorderTest {
    private static final int OVERSIZED_DETAIL_CHARACTERS = 3000;
    private final SysPermissionAuditLogMapper mapper = mock(SysPermissionAuditLogMapper.class);
    private final CommittedCacheChanges changes = mock(CommittedCacheChanges.class);
    private final PermissionChangeRecorder recorder =
            new PermissionChangeRecorder(mapper, changes, new ObjectMapper());

    private MockedStatic<AuthUtil> login() {
        MockedStatic<AuthUtil> auth = mockStatic(AuthUtil.class);
        auth.when(AuthUtil::getLoginCompanyId).thenReturn("company-a");
        auth.when(AuthUtil::getLoginUsername).thenReturn("operator");
        return auth;
    }

    @Test
    void recordsActorCompanyAndRedactsNestedCredentials() {
        when(mapper.insert(any(SysPermissionAuditLog.class))).thenReturn(1);
        try (MockedStatic<AuthUtil> ignored = login()) {
            recorder.record(
                    "save_role",
                    "role-a",
                    Map.of("name", "reader", "nested", Map.of("password", "unsafe-secret")));
        }
        ArgumentCaptor<SysPermissionAuditLog> entry =
                ArgumentCaptor.forClass(SysPermissionAuditLog.class);
        verify(mapper).insert(entry.capture());
        assertEquals("company-a", entry.getValue().getCompanyId());
        assertEquals("operator", entry.getValue().getActorUserNo());
        assertFalse(entry.getValue().getDetail().contains("unsafe-secret"));
        assertTrue(entry.getValue().getDetail().contains("[REDACTED]"));
        verify(changes).permissionsChanged();
    }

    @Test
    void auditFailureDoesNotPublishAuthorizationChange() {
        when(mapper.insert(any(SysPermissionAuditLog.class)))
                .thenThrow(
                        new org.springframework.dao.DataIntegrityViolationException(
                                "audit unavailable"));
        try (MockedStatic<AuthUtil> ignored = login()) {
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> recorder.record("grant_role", "role-a", null));
        }
        verifyNoInteractions(changes);
    }

    @Test
    void missingIdentityCannotSilentlySkipAudit() {
        try (MockedStatic<AuthUtil> ignored = mockStatic(AuthUtil.class)) {
            assertThrows(
                    ServiceException.class, () -> recorder.record("delete_menu", "menu-a", null));
        }
        verifyNoInteractions(mapper, changes);
    }

    @Test
    void oversizeDetailRemainsValidJson() throws Exception {
        when(mapper.insert(any(SysPermissionAuditLog.class))).thenReturn(1);
        try (MockedStatic<AuthUtil> ignored = login()) {
            recorder.record(
                    "save_role",
                    "role-a",
                    Map.of("description", "x".repeat(OVERSIZED_DETAIL_CHARACTERS)));
        }
        ArgumentCaptor<SysPermissionAuditLog> entry =
                ArgumentCaptor.forClass(SysPermissionAuditLog.class);
        verify(mapper).insert(entry.capture());
        assertTrue(
                new ObjectMapper()
                        .readTree(entry.getValue().getDetail())
                        .get("truncated")
                        .asBoolean());
    }
}
