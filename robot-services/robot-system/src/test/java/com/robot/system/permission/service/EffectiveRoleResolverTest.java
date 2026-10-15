package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.robot.system.api.entity.SysCompany;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysTenant;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.mapper.SysCompanyMapper;
import com.robot.system.mapper.SysTenantMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EffectiveRoleResolverTest {
    private static final int ACTIVE_WINDOW_SECONDS = 10;
    private static final int EARLIEST_FUTURE_BOUNDARY_SECONDS = 5;
    @Mock SysRoleMapper roleMapper;
    @Mock SysUserRoleMapper userRoleMapper;
    @Mock SysTempAuthorizationMapper tempMapper;
    @Mock SysUserCompanyMapper membershipMapper;
    @Mock SysUserMapper userMapper;
    @Mock SysCompanyMapper companyMapper;
    @Mock SysTenantMapper tenantMapper;
    final Clock clock =
            Clock.fixed(Instant.parse("2026-10-08T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    final LocalDateTime now = LocalDateTime.now(clock);
    EffectiveRoleResolver resolver;
    SysUser user;
    SysCompany company;
    SysUserCompany membership;

    @BeforeEach
    void setUp() {
        resolver =
                new EffectiveRoleResolver(
                        roleMapper,
                        userRoleMapper,
                        tempMapper,
                        new ActiveCompanySubject(
                                membershipMapper, userMapper, companyMapper, tenantMapper),
                        clock);
        user = new SysUser();
        user.setId("u-1");
        user.setStatusFlag(1);
        user.setDeleteFlag(1);
        company = new SysCompany();
        company.setId("c-1");
        company.setTenantId("t-1");
        company.setStatusFlag(1);
        company.setDeleteFlag(1);
        SysTenant tenant = new SysTenant();
        tenant.setId("t-1");
        tenant.setStatusFlag(1);
        tenant.setDeleteFlag(1);
        membership = new SysUserCompany();
        membership.setId("member-1");
        membership.setUserId("u-1");
        membership.setCompanyId("c-1");
        membership.setStatusFlag(1);
        membership.setDeleteFlag(1);
        lenient().when(userMapper.selectById("u-1")).thenReturn(user);
        lenient().when(companyMapper.selectById("c-1")).thenReturn(company);
        lenient().when(tenantMapper.selectById("t-1")).thenReturn(tenant);
        lenient().when(membershipMapper.selectList(any())).thenReturn(List.of(membership));
        lenient().when(userRoleMapper.selectList(any())).thenReturn(List.of());
        lenient().when(tempMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void emptyIdentityNeverQueriesRoles() {
        assertTrue(resolver.resolve(null, "c-1").isEmpty());
        assertTrue(resolver.resolve("u-1", " ").isEmpty());
        verifyNoInteractions(roleMapper, userRoleMapper, tempMapper);
    }

    @Test
    void companySuperAdminDoesNotLeakToUnboundUser() {
        assertFalse(resolver.resolve("u-1", "c-1").superAdmin());
        verifyNoInteractions(roleMapper);
    }

    @Test
    void boundSuperAdminIsSuper() {
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(binding("u-1", "c-1", "r-super")));
        when(roleMapper.selectList(any()))
                .thenReturn(List.of(role("r-super", "c-1", "SUPER_ADMIN")));
        assertTrue(resolver.resolve("u-1", "c-1").superAdmin());
        assertTrue(resolver.resolvePermanent("u-1", "c-1").superAdmin());
    }

    @Test
    void temporarySuperCanUseBusinessPermissionsButCannotBeDelegationSource() {
        when(tempMapper.selectList(any()))
                .thenReturn(List.of(temp("r-super", now, now.plusHours(1))));
        when(roleMapper.selectList(any()))
                .thenReturn(List.of(role("r-super", "c-1", "SUPER_ADMIN")));
        assertTrue(resolver.resolve("u-1", "c-1").superAdmin());
        var permanent = resolver.resolvePermanent("u-1", "c-1");
        assertTrue(permanent.isEmpty());
        assertFalse(permanent.superAdmin());
        assertNull(permanent.nextChangeAt());
        verify(tempMapper, times(1)).selectList(any());
    }

    @Test
    void permanentScopeDoesNotIncludeTemporaryElevatedRole() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(binding("u-1", "c-1", "r-own")));
        lenient()
                .when(tempMapper.selectList(any()))
                .thenReturn(List.of(temp("r-super", now, now.plusHours(1))));
        when(roleMapper.selectList(any()))
                .thenReturn(
                        List.of(
                                role("r-own", "c-1", "STAFF"),
                                role("r-super", "c-1", "SUPER_ADMIN")));
        var permanent = resolver.resolvePermanent("u-1", "c-1");
        assertEquals(java.util.Set.of("r-own"), permanent.roleIds());
        assertFalse(permanent.superAdmin());
        verifyNoInteractions(tempMapper);
    }

    @Test
    void mapperReturnsCannotCrossUserCompanyOrDeletedBinding() {
        SysUserRole deleted = binding("u-1", "c-1", "r-old");
        deleted.setDeleteFlag(0);
        when(userRoleMapper.selectList(any()))
                .thenReturn(
                        List.of(
                                binding("u-2", "c-1", "r-evil"),
                                binding("u-1", "c-2", "r-cross"),
                                deleted,
                                binding("u-1", "c-1", "r-own")));
        when(roleMapper.selectList(any()))
                .thenReturn(
                        List.of(
                                role("r-own", "c-1", "STAFF"),
                                role("r-evil", "c-1", "SUPER_ADMIN"),
                                role("r-own-copy", "c-2", "SUPER_ADMIN")));
        var result = resolver.resolve("u-1", "c-1");
        assertFalse(result.superAdmin());
        assertEquals(java.util.Set.of("r-own"), result.roleIds());
    }

    @Test
    void temporaryRoleUsesHalfOpenWindowAndNextBoundary() {
        when(tempMapper.selectList(any()))
                .thenReturn(
                        List.of(
                                temp("r-active", now, now.plusSeconds(ACTIVE_WINDOW_SECONDS)),
                                temp("r-expired", now.minusMinutes(2), now),
                                temp(
                                        "r-future",
                                        now.plusSeconds(EARLIEST_FUTURE_BOUNDARY_SECONDS),
                                        now.plusMinutes(1))));
        when(roleMapper.selectList(any()))
                .thenReturn(
                        List.of(
                                role("r-active", "c-1", "STAFF"),
                                role("r-expired", "c-1", "SUPER_ADMIN"),
                                role("r-future", "c-1", "SUPER_ADMIN")));
        var result = resolver.resolve("u-1", "c-1");
        assertEquals(java.util.Set.of("r-active"), result.roleIds());
        assertFalse(result.superAdmin());
        assertEquals(now.plusSeconds(EARLIEST_FUTURE_BOUNDARY_SECONDS), result.nextChangeAt());
    }

    @Test
    void futureRoleKeepsEmptyPermissionSetButHasWakeupBoundary() {
        when(tempMapper.selectList(any()))
                .thenReturn(
                        List.of(
                                temp(
                                        "r-future",
                                        now.plusSeconds(ACTIVE_WINDOW_SECONDS),
                                        now.plusMinutes(1))));
        when(roleMapper.selectList(any())).thenReturn(List.of(role("r-future", "c-1", "STAFF")));
        var result = resolver.resolve("u-1", "c-1");
        assertTrue(result.isEmpty());
        assertEquals(now.plusSeconds(ACTIVE_WINDOW_SECONDS), result.nextChangeAt());
    }

    @Test
    void disabledRoleDoesNotGrantOrProduceBoundary() {
        SysRole role = role("r-1", "c-1", "SUPER_ADMIN");
        role.setStatusFlag(0);
        when(tempMapper.selectList(any()))
                .thenReturn(List.of(temp("r-1", now, now.plusMinutes(1))));
        when(roleMapper.selectList(any())).thenReturn(List.of(role));
        var result = resolver.resolve("u-1", "c-1");
        assertTrue(result.isEmpty());
        assertNull(result.nextChangeAt());
    }

    @Test
    void disabledAccountCompanyOrMembershipFailsClosed() {
        user.setStatusFlag(0);
        assertFalse(resolver.isActiveSubject("u-1", "c-1"));
        user.setStatusFlag(1);
        company.setStatusFlag(0);
        assertFalse(resolver.isActiveSubject("u-1", "c-1"));
        company.setStatusFlag(1);
        membership.setStatusFlag(0);
        assertFalse(resolver.isActiveSubject("u-1", "c-1"));
        assertTrue(resolver.resolve("u-1", "c-1").isEmpty());
        verifyNoInteractions(roleMapper);
    }

    @Test
    void invalidTenantAndDuplicateMembershipFailClosed() {
        when(tenantMapper.selectById("t-1")).thenReturn(null);
        assertFalse(resolver.isActiveSubject("u-1", "c-1"));
        SysTenant tenant = new SysTenant();
        tenant.setId("t-1");
        tenant.setStatusFlag(1);
        tenant.setDeleteFlag(1);
        when(tenantMapper.selectById("t-1")).thenReturn(tenant);
        when(membershipMapper.selectList(any())).thenReturn(List.of(membership, membership));
        assertFalse(resolver.isActiveSubject("u-1", "c-1"));
    }

    private SysRole role(String id, String companyId, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCompanyId(companyId);
        role.setRoleCode(code);
        role.setDeleteFlag(1);
        role.setStatusFlag(1);
        role.setDataScope("self");
        return role;
    }

    private SysUserRole binding(String userId, String companyId, String roleId) {
        SysUserRole binding = new SysUserRole();
        binding.setUserId(userId);
        binding.setCompanyId(companyId);
        binding.setRoleId(roleId);
        binding.setDeleteFlag(1);
        return binding;
    }

    private SysTempAuthorization temp(String roleId, LocalDateTime from, LocalDateTime to) {
        SysTempAuthorization auth = new SysTempAuthorization();
        auth.setUserId("u-1");
        auth.setCompanyId("c-1");
        auth.setRoleId(roleId);
        auth.setEffectFrom(from);
        auth.setEffectTo(to);
        auth.setStatusFlag(1);
        auth.setDeleteFlag(1);
        return auth;
    }
}
