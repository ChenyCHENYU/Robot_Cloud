package com.robot.system.role.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.role.TempAuthorizationCreateRequest;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUser;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class TemporaryAuthorizationBoundaryTest {
    private static final int MAX_GRANT_DAYS = 30;
    private static final int MAX_REASON_LENGTH = 255;
    private static final LocalDateTime GRANT_START = LocalDateTime.parse("2026-10-14T00:00:00");

    @Test
    void missingReversedExpiredOverlongWindowsAndReasonsNeverReachTargetQueries() {
        var f = new Fixture();
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::getLoginCompanyId).thenReturn("c");
            for (java.util.function.Consumer<TempAuthorizationCreateRequest> invalid :
                    List.<java.util.function.Consumer<TempAuthorizationCreateRequest>>of(
                            dto -> dto.setEffectFrom(null),
                            dto -> dto.setEffectTo(null),
                            dto -> dto.setEffectTo(dto.getEffectFrom()),
                            dto -> dto.setEffectTo(dto.getEffectFrom().minusSeconds(1)),
                            dto ->
                                    dto.setEffectTo(
                                            dto.getEffectFrom()
                                                    .plusDays(MAX_GRANT_DAYS)
                                                    .plusSeconds(1)),
                            dto -> {
                                dto.setEffectFrom(LocalDateTime.parse("2026-01-01T00:00:00"));
                                dto.setEffectTo(dto.getEffectFrom().plusDays(1));
                            },
                            dto -> dto.setReason(" "),
                            dto -> dto.setReason("a".repeat(MAX_REASON_LENGTH + 1)))) {
                var dto = request();
                invalid.accept(dto);
                assertThrows(ServiceException.class, () -> f.service.createTempAuthorization(dto));
            }
            verifyNoInteractions(f.roles, f.users, f.members, f.temporary, f.recorder);
        }
    }

    @Test
    void selfDelegationWrongCompanyDeletedRoleAndInactiveTargetsFailClosed() {
        var f = new Fixture();
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::getLoginCompanyId).thenReturn("c");
            when(f.policy.requireOperator())
                    .thenReturn(
                            new PermissionGrantPolicy.Operator(
                                    "u", "c", EffectiveRoleResolver.EffectiveRoles.EMPTY));
            assertThrows(
                    ServiceException.class, () -> f.service.createTempAuthorization(request()));
            when(f.policy.requireOperator())
                    .thenReturn(
                            new PermissionGrantPolicy.Operator(
                                    "operator",
                                    "c",
                                    new EffectiveRoleResolver.EffectiveRoles(true, List.of())));
            var role = role();
            role.setCompanyId("foreign");
            when(f.roles.selectById("r")).thenReturn(role);
            assertThrows(
                    ServiceException.class, () -> f.service.createTempAuthorization(request()));
            role.setCompanyId("c");
            role.setDeleteFlag(0);
            assertThrows(
                    ServiceException.class, () -> f.service.createTempAuthorization(request()));
            role.setDeleteFlag(1);
            var user = user();
            when(f.users.selectById("u")).thenReturn(user);
            user.setStatusFlag(0);
            assertThrows(
                    ServiceException.class, () -> f.service.createTempAuthorization(request()));
            user.setStatusFlag(1);
            user.setDeleteFlag(0);
            assertThrows(
                    ServiceException.class, () -> f.service.createTempAuthorization(request()));
            user.setDeleteFlag(1);
            when(f.members.selectCount(any())).thenReturn(0L);
            assertThrows(
                    ServiceException.class, () -> f.service.createTempAuthorization(request()));
            when(f.members.selectCount(any())).thenReturn(1L);
            when(f.temporary.insert(any(SysTempAuthorization.class))).thenReturn(0);
            assertThrows(
                    ServiceException.class, () -> f.service.createTempAuthorization(request()));
            verifyNoInteractions(f.recorder);
        }
    }

    @Test
    void absentMissingNegativeStaleRevokedAndLostCasCannotReportSuccessfulRevoke() {
        var f = new Fixture();
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::getLoginCompanyId).thenReturn("c");
            assertThrows(ServiceException.class, () -> f.service.revoke("missing", 0));
            var grant = new SysTempAuthorization();
            grant.setId("g");
            grant.setRoleId("r");
            grant.setUserId("u");
            grant.setRevision(2);
            grant.setStatusFlag(1);
            when(f.temporary.selectOne(any())).thenReturn(grant);
            for (Integer revision : new Integer[] {null, -1, 1}) {
                assertThrows(ServiceException.class, () -> f.service.revoke("g", revision));
            }
            grant.setStatusFlag(0);
            assertThrows(ServiceException.class, () -> f.service.revoke("g", 2));
            grant.setStatusFlag(1);
            when(f.roles.selectById("r")).thenReturn(role());
            when(f.temporary.updateById(any(SysTempAuthorization.class))).thenReturn(0);
            assertThrows(ServiceException.class, () -> f.service.revoke("g", 2));
            verifyNoInteractions(f.recorder);
        }
    }

    private static TempAuthorizationCreateRequest request() {
        var dto = new TempAuthorizationCreateRequest();
        dto.setUserId("u");
        dto.setRoleId("r");
        dto.setEffectFrom(GRANT_START);
        dto.setEffectTo(dto.getEffectFrom().plusDays(1));
        dto.setReason("support");
        return dto;
    }

    private static SysRole role() {
        var role = new SysRole();
        role.setId("r");
        role.setCompanyId("c");
        role.setDeleteFlag(1);
        return role;
    }

    private static SysUser user() {
        var user = new SysUser();
        user.setId("u");
        user.setStatusFlag(1);
        user.setDeleteFlag(1);
        return user;
    }

    private static class Fixture {
        final SysRoleMapper roles = mock(SysRoleMapper.class);
        final SysTempAuthorizationMapper temporary = mock(SysTempAuthorizationMapper.class);
        final SysUserMapper users = mock(SysUserMapper.class);
        final SysUserCompanyMapper members = mock(SysUserCompanyMapper.class);
        final PermissionGrantPolicy policy = mock(PermissionGrantPolicy.class);
        final PermissionChangeRecorder recorder = mock(PermissionChangeRecorder.class);
        final TemporaryAuthorizationCommandService service;

        Fixture() {
            when(policy.requireOperator())
                    .thenReturn(
                            new PermissionGrantPolicy.Operator(
                                    "operator",
                                    "c",
                                    new EffectiveRoleResolver.EffectiveRoles(true, List.of())));
            service =
                    new TemporaryAuthorizationCommandService(
                            roles,
                            temporary,
                            users,
                            members,
                            policy,
                            recorder,
                            Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC),
                            mock(PermissionStateStore.class));
        }
    }
}
