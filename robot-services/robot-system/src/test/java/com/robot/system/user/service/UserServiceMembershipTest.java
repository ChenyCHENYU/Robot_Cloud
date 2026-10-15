package com.robot.system.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.user.UserPageQuery;
import com.robot.system.api.dto.user.UserPasswordResetRequest;
import com.robot.system.api.dto.user.UserSaveRequest;
import com.robot.system.api.dto.user.UserStatusRequest;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.dept.mapper.SysDepartmentMapper;
import com.robot.system.permission.service.DataScopeService;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import java.time.Clock;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserServiceMembershipTest {
    private static final int TEST_BCRYPT_COST = 4;
    private static final int ACCOUNT_REVISION = 3;
    private static final int DEFAULT_PAGE_SIZE = 20;

    @Mock SysUserMapper userMapper;
    @Mock SysUserCompanyMapper membershipMapper;
    @Mock SysUserRoleMapper userRoleMapper;
    @Mock SysRoleMapper roleMapper;
    @Mock SysDepartmentMapper departmentMapper;
    @Mock SysTempAuthorizationMapper tempMapper;
    @Mock PermissionGrantPolicy policy;
    @Mock PermissionChangeRecorder recorder;
    @Mock DataScopeService dataScopes;
    UserService service;
    MockedStatic<AuthUtil> auth;
    SysUser user;
    SysUserCompany member;

    @BeforeEach
    void setUp() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        for (Class<?> type :
                List.of(
                        SysUser.class,
                        SysUserCompany.class,
                        SysUserRole.class,
                        SysTempAuthorization.class,
                        SysRole.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
        var queries =
                new UserQueryService(
                        userMapper,
                        membershipMapper,
                        userRoleMapper,
                        roleMapper,
                        policy,
                        dataScopes);
        var memberRoles =
                new MemberRoleCommandSupport(
                        userRoleMapper, roleMapper, tempMapper, policy, Clock.systemDefaultZone());
        service =
                spy(
                        new UserService(
                                membershipMapper,
                                departmentMapper,
                                policy,
                                new BCryptPasswordEncoder(TEST_BCRYPT_COST),
                                recorder,
                                mock(
                                        com.robot.system.permission.service.PermissionStateStore
                                                .class),
                                queries,
                                memberRoles,
                                mock(
                                        com.robot.system.permission.service.GlobalDirectoryPolicy
                                                .class)));
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
        auth = mockStatic(AuthUtil.class);
        auth.when(AuthUtil::getLoginUserId).thenReturn("operator-A");
        lenient().when(policy.requireCompany()).thenReturn("cmp-A");
        lenient()
                .when(dataScopes.userReadScope(any(), eq("cmp-A")))
                .thenReturn(
                        new DataScopeService.UserReadScope(
                                "operator-A", "cmp-A", true, java.util.Set.of()));
        SysRole admin = new SysRole();
        admin.setId("r-admin");
        lenient()
                .when(policy.requireOperator())
                .thenReturn(
                        new PermissionGrantPolicy.Operator(
                                "operator-A",
                                "cmp-A",
                                new EffectiveRoleResolver.EffectiveRoles(true, List.of(admin))));
        user = new SysUser();
        user.setId("target");
        user.setUsername("target");
        user.setNickname("Target");
        user.setStatusFlag(1);
        user.setDeleteFlag(1);
        user.setRevision(ACCOUNT_REVISION);
        member = membership("cmp-A");
        lenient().when(userMapper.selectById("target")).thenReturn(user);
        lenient().when(membershipMapper.selectList(any())).thenReturn(List.of(member));
        lenient().when(membershipMapper.updateById(any(SysUserCompany.class))).thenReturn(1);
        lenient().when(membershipMapper.delete(any())).thenReturn(1);
        lenient().when(membershipMapper.update(any(SysUserCompany.class), any())).thenReturn(1);
        lenient().when(userMapper.updateById(any(SysUser.class))).thenReturn(1);
        lenient().when(userMapper.update(any(SysUser.class), any())).thenReturn(1);
    }

    @AfterEach
    void close() {
        auth.close();
    }

    @Test
    void crossCompanyIdRejectedForEveryCompanyOperation() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(membership("cmp-B")));
        assertCode(ResultCode.FORBIDDEN, () -> service.getDetail("target"));
        assertCode(
                ResultCode.FORBIDDEN,
                () ->
                        service.resetPassword(
                                "target",
                                new UserPasswordResetRequest(
                                        "newpass123", user.getRevision(), member.getRevision())));
        assertCode(
                ResultCode.FORBIDDEN,
                () ->
                        service.toggleStatus(
                                "target", new UserStatusRequest(0, member.getRevision())));
        assertCode(ResultCode.FORBIDDEN, () -> service.update("target", request()));
        assertCode(ResultCode.FORBIDDEN, () -> service.delete("target", member.getRevision()));
        verify(userMapper, never()).updateById(any(SysUser.class));
        verify(membershipMapper, never()).delete(any());
    }

    @Test
    void concurrentMemberRemovalDuringDetailReturnsBusinessDenial() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(member), List.of());
        when(userMapper.selectOne(any())).thenReturn(user);
        assertCode(ResultCode.FORBIDDEN, () -> service.getDetail("target"));
    }

    @Test
    void sharedAccountStatusOnlyUpdatesCurrentMembership() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(member, membership("cmp-B")));
        service.toggleStatus("target", new UserStatusRequest(0, member.getRevision()));
        verify(membershipMapper)
                .updateById(
                        argThat(
                                (SysUserCompany value) ->
                                        "cmp-A".equals(value.getCompanyId())
                                                && value.getStatusFlag() == 0));
        assertEquals(1, user.getStatusFlag());
        verify(userMapper, never()).updateById(any(SysUser.class));
        verify(recorder).record(eq("member_status"), eq("target"), any());
    }

    @Test
    void removeSharedMemberNeverDeletesAccountOrOtherCompany() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(member, membership("cmp-B")));
        service.delete("target", member.getRevision());
        ArgumentCaptor<Wrapper<SysUserCompany>> memberships =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(membershipMapper).update(any(SysUserCompany.class), memberships.capture());
        String sql = memberships.getValue().getSqlSegment();
        assertTrue(sql.contains("company_id"));
        assertTrue(sql.contains("revision"));
        assertEquals(1, user.getDeleteFlag());
        verify(userMapper, never()).delete(any());
        assertEquals(1L, member.getSecurityVersion());
        verify(userMapper, never()).updateById(any(SysUser.class));
        ArgumentCaptor<Wrapper<SysUserRole>> roles = ArgumentCaptor.forClass(Wrapper.class);
        verify(userRoleMapper).delete(roles.capture());
        assertTrue(roles.getValue().getSqlSegment().contains("company_id"));
    }

    @Test
    void companyCannotResetOrChangeSharedAccountProfile() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(member, membership("cmp-B")));
        assertCode(
                ResultCode.FORBIDDEN,
                () ->
                        service.resetPassword(
                                "target",
                                new UserPasswordResetRequest(
                                        "newpass123", user.getRevision(), member.getRevision())));
        UserSaveRequest request = request();
        request.setNickname("Changed");
        assertCode(ResultCode.FORBIDDEN, () -> service.update("target", request));
        verify(userMapper, never()).updateById(any(SysUser.class));
        verify(membershipMapper, never()).updateById(any(SysUserCompany.class));
    }

    @Test
    void membershipConflictStopsRebindingAndSuccessAudit() {
        when(membershipMapper.updateById(any(SysUserCompany.class))).thenReturn(0);
        UserSaveRequest request = request();
        request.setRoleIds(List.of());
        assertCode(ResultCode.DATA_CONCURRENT_CONFLICT, () -> service.update("target", request));
        verify(userRoleMapper, never()).delete(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void staleClientMembershipRevisionRejectedBeforeWrite() {
        UserSaveRequest request = request();
        request.setMembershipRevision(1);
        assertCode(ResultCode.DATA_CONCURRENT_CONFLICT, () -> service.update("target", request));
        verify(membershipMapper, never()).updateById(any(SysUserCompany.class));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void accountConflictStopsMembershipAndAssociatedWrites() {
        when(userMapper.update(any(SysUser.class), any())).thenReturn(0);
        UserSaveRequest request = request();
        request.setNickname("Changed");
        assertCode(ResultCode.DATA_CONCURRENT_CONFLICT, () -> service.update("target", request));
        verify(membershipMapper, never()).updateById(any(SysUserCompany.class));
        verify(userRoleMapper, never()).delete(any());
    }

    @Test
    void nullRolesPreserveAndExplicitEmptyClearsOnlyCompanyBindings() {
        service.update("target", request());
        verify(userRoleMapper, never()).delete(any());
        UserSaveRequest request = request();
        request.setRoleIds(List.of());
        service.update("target", request);
        verify(userRoleMapper).delete(any());
    }

    @Test
    void listUsesParameterizedCompanyMembershipPredicateAndStableSort() {
        Page<SysUser> page = new Page<>(1, DEFAULT_PAGE_SIZE);
        page.setRecords(List.of());
        page.setTotal(0);
        when(userMapper.selectPage(any(Page.class), any())).thenReturn(page);
        service.queryPage(new UserPageQuery());
        ArgumentCaptor<Wrapper<SysUser>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper).selectPage(any(Page.class), query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("EXISTS"));
        assertTrue(sql.contains("uc.company_id"));
        assertTrue(sql.contains("id DESC"));
        assertFalse(sql.contains("cmp-A"));
    }

    @Test
    void selfServiceCannotChangeAnotherAccount() {
        assertCode(
                ResultCode.FORBIDDEN,
                () -> ownCommands().changePassword("target", "oldpass", "newpass123"));
        assertCode(
                ResultCode.FORBIDDEN,
                () -> ownCommands().updateProfile("target", profile("Changed")));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void ordinaryManagerCannotResetOwnPasswordWithoutOldPassword() {
        when(policy.requireOperator())
                .thenReturn(
                        new PermissionGrantPolicy.Operator(
                                "target",
                                "cmp-A",
                                new EffectiveRoleResolver.EffectiveRoles(false, List.of())));
        assertCode(
                ResultCode.FORBIDDEN,
                () ->
                        service.resetPassword(
                                "target",
                                new UserPasswordResetRequest(
                                        "newpass123", user.getRevision(), member.getRevision())));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void disableThenEnableRetainsAdvancedMembershipSecurityVersion() {
        service.toggleStatus("target", new UserStatusRequest(0, member.getRevision()));
        assertEquals(1L, member.getSecurityVersion());
        service.toggleStatus("target", new UserStatusRequest(1, member.getRevision()));
        assertEquals(1L, member.getSecurityVersion());
        assertEquals(0L, user.getSecurityVersion());
    }

    @Test
    void adminPasswordUpdateAndResetAdvanceAccountSecurityVersion() {
        user.setSecurityVersion(null);
        UserSaveRequest request = request();
        request.setPassword("newpass123");
        service.update("target", request);
        assertEquals(1L, user.getSecurityVersion());
        service.resetPassword(
                "target",
                new UserPasswordResetRequest(
                        "otherpass123", user.getRevision(), member.getRevision()));
        assertEquals(2L, user.getSecurityVersion());
    }

    @Test
    void ownPasswordChangeAdvancesSecurityVersionButProfileChangeDoesNot() {
        auth.when(AuthUtil::getLoginUserId).thenReturn("target");
        user.setPasswordHash(new BCryptPasswordEncoder(TEST_BCRYPT_COST).encode("oldpass123"));
        ownCommands().changePassword("target", "oldpass123", "newpass123");
        assertEquals(1L, user.getSecurityVersion());
        ownCommands().updateProfile("target", profile("Other"));
        assertEquals(1L, user.getSecurityVersion());
    }

    @Test
    void disabledTargetAndFutureTemporaryRoleStillPassThroughManagementCeiling() {
        when(policy.requireOperator())
                .thenReturn(
                        new PermissionGrantPolicy.Operator(
                                "operator-A",
                                "cmp-A",
                                new EffectiveRoleResolver.EffectiveRoles(false, List.of())));
        member.setStatusFlag(0);
        SysTempAuthorization future = new SysTempAuthorization();
        future.setUserId("target");
        future.setCompanyId("cmp-A");
        future.setRoleId("r-super");
        future.setStatusFlag(1);
        future.setDeleteFlag(1);
        future.setEffectFrom(java.time.LocalDateTime.now().plusDays(1));
        future.setEffectTo(java.time.LocalDateTime.now().plusDays(2));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(tempMapper.selectList(any())).thenReturn(List.of(future));
        SysRole superRole = new SysRole();
        superRole.setId("r-super");
        superRole.setCompanyId("cmp-A");
        superRole.setDeleteFlag(1);
        superRole.setRoleCode("SUPER_ADMIN");
        superRole.setStatusFlag(0);
        when(roleMapper.selectList(any())).thenReturn(List.of(superRole));
        doThrow(new ServiceException(ResultCode.FORBIDDEN))
                .when(policy)
                .assertManagedRoles(any(), any());
        assertCode(
                ResultCode.FORBIDDEN,
                () ->
                        service.toggleStatus(
                                "target", new UserStatusRequest(1, member.getRevision())));
        verify(policy).assertManagedRoles(any(), eq(List.of(superRole)));
        verify(membershipMapper, never()).updateById(any(SysUserCompany.class));
    }

    private SysUserCompany membership(String companyId) {
        SysUserCompany membership = new SysUserCompany();
        membership.setId("member-" + companyId);
        membership.setUserId("target");
        membership.setCompanyId(companyId);
        membership.setStatusFlag(1);
        membership.setDeleteFlag(1);
        membership.setRevision(2);
        return membership;
    }

    private UserSaveRequest request() {
        UserSaveRequest request = new UserSaveRequest();
        request.setUsername("target");
        request.setNickname("Target");
        request.setRevision(ACCOUNT_REVISION);
        request.setMembershipRevision(2);
        return request;
    }

    private com.robot.system.account.service.OwnAccountCommandService ownCommands() {
        return new com.robot.system.account.service.OwnAccountCommandService(
                userMapper, policy, new BCryptPasswordEncoder(TEST_BCRYPT_COST), recorder);
    }

    private static com.robot.system.api.dto.account.ProfileUpdateRequest profile(String nickname) {
        var request = new com.robot.system.api.dto.account.ProfileUpdateRequest();
        request.setNickname(nickname);
        request.setEmail(null);
        request.setPhone(null);
        return request;
    }

    private void assertCode(ResultCode expected, Runnable action) {
        assertEquals(
                expected.getCode(), assertThrows(ServiceException.class, action::run).getCode());
    }
}
