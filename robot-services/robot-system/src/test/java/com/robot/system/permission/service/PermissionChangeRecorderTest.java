package com.robot.system.permission.service;

import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.mapper.SysPermissionAuditLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 权限变更记录器契约测试：一次调用 = 审计落库 + 版本自增；
 * 任一副作用失败只记录日志，不阻断业务（降级语义）。
 */
@ExtendWith(MockitoExtension.class)
class PermissionChangeRecorderTest {

    @Mock
    private SysPermissionAuditLogMapper auditLogMapper;

    @Mock
    private RedisHelper redisHelper;

    @InjectMocks
    private PermissionChangeRecorder recorder;

    private static MockedStatic<AuthUtil> loginAs(String company, String username) {
        MockedStatic<AuthUtil> auth = mockStatic(AuthUtil.class);
        auth.when(AuthUtil::getLoginCompanyId).thenReturn(company);
        auth.when(AuthUtil::getLoginUsername).thenReturn(username);
        return auth;
    }

    @Test
    @DisplayName("record：审计插入（操作人兜底 SYSTEM）+ 版本自增一次完成")
    void recordShouldAuditAndBumpVersion() {
        try (MockedStatic<AuthUtil> auth = loginAs("cmp-1", null)) {
            recorder.record("save_role", "role-1", java.util.Map.of("name", "管理员"));
        }

        ArgumentCaptor<SysPermissionAuditLog> captor =
                ArgumentCaptor.forClass(SysPermissionAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertEquals("save_role", captor.getValue().getAction());
        assertEquals("role-1", captor.getValue().getTarget());
        assertEquals("{\"name\":\"管理员\"}", captor.getValue().getDetail());
        assertEquals("SYSTEM", captor.getValue().getActorUserNo());

        verify(redisHelper).bumpVersion(anyString());
    }

    @Test
    @DisplayName("有登录人时审计操作人取登录名")
    void actorShouldPreferLoginUsername() {
        try (MockedStatic<AuthUtil> auth = loginAs("cmp-1", "zhangsan")) {
            recorder.record("update_button", "btn-1", null);
        }

        ArgumentCaptor<SysPermissionAuditLog> captor =
                ArgumentCaptor.forClass(SysPermissionAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertEquals("zhangsan", captor.getValue().getActorUserNo());
        assertNull(captor.getValue().getDetail());
    }

    @Test
    @DisplayName("无公司上下文：跳过审计但仍自增版本（缓存一致性不丢）")
    void noCompanyContextShouldSkipAuditOnly() {
        try (MockedStatic<AuthUtil> auth = loginAs(null, null)) {
            recorder.record("delete_menu", "menu-1", null);
        }

        verify(auditLogMapper, never())
                .insert(any(com.robot.system.api.entity.SysPermissionAuditLog.class));
        verify(redisHelper).bumpVersion(anyString());
    }

    @Test
    @DisplayName("审计失败不阻断：版本自增仍执行（降级语义）")
    void auditFailureShouldNotBlockBump() {
        doThrow(new RuntimeException("db down")).when(auditLogMapper)
                .insert(any(SysPermissionAuditLog.class));

        try (MockedStatic<AuthUtil> auth = loginAs("cmp-1", "zhangsan")) {
            assertDoesNotThrow(() -> recorder.record("delete_menu", "menu-1", null));
        }

        verify(redisHelper).bumpVersion(anyString());
    }

    @Test
    @DisplayName("版本自增失败不阻断：审计已落库（降级语义）")
    void bumpFailureShouldNotBlockAudit() {
        doThrow(new RuntimeException("redis down")).when(redisHelper).bumpVersion(anyString());

        try (MockedStatic<AuthUtil> auth = loginAs("cmp-1", "zhangsan")) {
            assertDoesNotThrow(() -> recorder.record("update_button", "btn-1", null));
        }

        verify(auditLogMapper).insert(any(SysPermissionAuditLog.class));
    }
}
