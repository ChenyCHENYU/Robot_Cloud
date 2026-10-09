package com.robot.system.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.user.UserPageQuery;
import com.robot.system.api.dto.user.UserSaveRequest;
import com.robot.system.api.dto.user.UserStatusRequest;
import com.robot.system.api.dto.user.UserPasswordResetRequest;
import com.robot.system.api.entity.*;
import com.robot.system.dept.mapper.SysDepartmentMapper;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.permission.service.DataScopeService;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceMembershipTest {
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

    @BeforeEach void setUp() {
        for (Class<?> type : List.of(SysUser.class, SysUserCompany.class, SysUserRole.class, SysTempAuthorization.class, SysRole.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), type);
        service = spy(new UserService(membershipMapper, userRoleMapper, roleMapper, departmentMapper,
                tempMapper, policy, new BCryptPasswordEncoder(4), recorder, Clock.systemDefaultZone(), dataScopes));
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
        auth = mockStatic(AuthUtil.class);
        auth.when(AuthUtil::getLoginUserId).thenReturn("operator-A");
        lenient().when(policy.requireCompany()).thenReturn("cmp-A");
        lenient().when(dataScopes.userReadScope(any(), eq("cmp-A"))).thenReturn(
                new DataScopeService.UserReadScope("operator-A", "cmp-A", true, java.util.Set.of()));
        SysRole admin = new SysRole(); admin.setId("r-admin");
        lenient().when(policy.requireOperator()).thenReturn(new PermissionGrantPolicy.Operator("operator-A", "cmp-A",
                new EffectiveRoleResolver.EffectiveRoles(true, List.of(admin))));
        user = new SysUser(); user.setId("target"); user.setUsername("target"); user.setNickname("Target");
        user.setStatusFlag(1); user.setDeleteFlag(1); user.setRevision(3);
        member = membership("cmp-A");
        lenient().when(userMapper.selectById("target")).thenReturn(user);
        lenient().when(membershipMapper.selectList(any())).thenReturn(List.of(member));
        lenient().when(membershipMapper.updateById(any(SysUserCompany.class))).thenReturn(1);
        lenient().when(membershipMapper.delete(any())).thenReturn(1);
        lenient().when(membershipMapper.update(any(SysUserCompany.class), any())).thenReturn(1);
        lenient().when(userMapper.updateById(any(SysUser.class))).thenReturn(1);
    }

    @AfterEach void close() { auth.close(); }

    @Test void crossCompanyIdRejectedForEveryCompanyOperation() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(membership("cmp-B")));
        assertCode(ResultCode.FORBIDDEN, () -> service.getDetail("target"));
        assertCode(ResultCode.FORBIDDEN, () -> service.resetPassword("target", new UserPasswordResetRequest("newpass123", user.getRevision(), member.getRevision())));
        assertCode(ResultCode.FORBIDDEN, () -> service.toggleStatus("target", new UserStatusRequest(0, member.getRevision())));
        assertCode(ResultCode.FORBIDDEN, () -> service.update("target", request()));
        assertCode(ResultCode.FORBIDDEN, () -> service.delete("target", member.getRevision()));
        verify(userMapper, never()).updateById(any(SysUser.class)); verify(membershipMapper, never()).delete(any());
    }

    @Test void concurrentMemberRemovalDuringDetailReturnsBusinessDenial() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(member), List.of());
        doReturn(user).when(service).getOne(any());
        assertCode(ResultCode.FORBIDDEN, () -> service.getDetail("target"));
    }

    @Test void sharedAccountStatusOnlyUpdatesCurrentMembership() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(member, membership("cmp-B")));
        service.toggleStatus("target", new UserStatusRequest(0, member.getRevision()));
        verify(membershipMapper).updateById(argThat((SysUserCompany value) ->
                "cmp-A".equals(value.getCompanyId()) && value.getStatusFlag() == 0));
        assertEquals(1, user.getStatusFlag()); verify(userMapper, never()).updateById(any(SysUser.class));
        verify(recorder).record(eq("member_status"), eq("target"), any());
    }

    @Test void removeSharedMemberNeverDeletesAccountOrOtherCompany() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(member, membership("cmp-B")));
        service.delete("target", member.getRevision());
        ArgumentCaptor<Wrapper<SysUserCompany>> memberships = ArgumentCaptor.forClass(Wrapper.class);
        verify(membershipMapper).update(any(SysUserCompany.class), memberships.capture());
        String sql = memberships.getValue().getSqlSegment();
        assertTrue(sql.contains("company_id")); assertTrue(sql.contains("revision"));
        assertEquals(1, user.getDeleteFlag()); verify(userMapper, never()).delete(any());
        assertEquals(1L, member.getSecurityVersion());
        verify(userMapper, never()).updateById(any(SysUser.class));
        ArgumentCaptor<Wrapper<SysUserRole>> roles = ArgumentCaptor.forClass(Wrapper.class);
        verify(userRoleMapper).delete(roles.capture()); assertTrue(roles.getValue().getSqlSegment().contains("company_id"));
    }

    @Test void companyCannotResetOrChangeSharedAccountProfile() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(member, membership("cmp-B")));
        assertCode(ResultCode.FORBIDDEN, () -> service.resetPassword("target", new UserPasswordResetRequest("newpass123", user.getRevision(), member.getRevision())));
        UserSaveRequest request = request(); request.setNickname("Changed");
        assertCode(ResultCode.FORBIDDEN, () -> service.update("target", request));
        verify(userMapper, never()).updateById(any(SysUser.class)); verify(membershipMapper, never()).updateById(any(SysUserCompany.class));
    }

    @Test void membershipConflictStopsRebindingAndSuccessAudit() {
        when(membershipMapper.updateById(any(SysUserCompany.class))).thenReturn(0);
        UserSaveRequest request = request(); request.setRoleIds(List.of());
        assertCode(ResultCode.DATA_CONCURRENT_CONFLICT, () -> service.update("target", request));
        verify(userRoleMapper, never()).delete(any()); verifyNoInteractions(recorder);
    }

    @Test void staleClientMembershipRevisionRejectedBeforeWrite() {
        UserSaveRequest request = request(); request.setMembershipRevision(1);
        assertCode(ResultCode.DATA_CONCURRENT_CONFLICT, () -> service.update("target", request));
        verify(membershipMapper, never()).updateById(any(SysUserCompany.class)); verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test void accountConflictStopsMembershipAndAssociatedWrites() {
        when(userMapper.updateById(any(SysUser.class))).thenReturn(0);
        UserSaveRequest request = request(); request.setNickname("Changed");
        assertCode(ResultCode.DATA_CONCURRENT_CONFLICT, () -> service.update("target", request));
        verify(membershipMapper, never()).updateById(any(SysUserCompany.class)); verify(userRoleMapper, never()).delete(any());
    }

    @Test void nullRolesPreserveAndExplicitEmptyClearsOnlyCompanyBindings() {
        service.update("target", request());
        verify(userRoleMapper, never()).delete(any());
        UserSaveRequest request = request(); request.setRoleIds(List.of());
        service.update("target", request);
        verify(userRoleMapper).delete(any());
    }

    @Test void listUsesParameterizedCompanyMembershipPredicateAndStableSort() {
        Page<SysUser> page = new Page<>(1, 20); page.setRecords(List.of()); page.setTotal(0);
        doReturn(page).when(service).page(any(Page.class), any());
        service.queryPage(new UserPageQuery());
        ArgumentCaptor<Wrapper<SysUser>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).page(any(Page.class), query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("EXISTS")); assertTrue(sql.contains("uc.company_id"));
        assertTrue(sql.contains("id DESC")); assertFalse(sql.contains("cmp-A"));
    }

    @Test void selfServiceCannotChangeAnotherAccount() {
        assertCode(ResultCode.FORBIDDEN, () -> service.changeOwnPassword("target", "oldpass", "newpass123"));
        assertCode(ResultCode.FORBIDDEN, () -> service.updateOwnProfile("target", "Changed", null, null));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test void ordinaryManagerCannotResetOwnPasswordWithoutOldPassword() {
        when(policy.requireOperator()).thenReturn(new PermissionGrantPolicy.Operator("target", "cmp-A",
                new EffectiveRoleResolver.EffectiveRoles(false, List.of())));
        assertCode(ResultCode.FORBIDDEN, () -> service.resetPassword("target", new UserPasswordResetRequest("newpass123", user.getRevision(), member.getRevision())));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test void disableThenEnableRetainsAdvancedMembershipSecurityVersion() {
        service.toggleStatus("target", new UserStatusRequest(0, member.getRevision()));
        assertEquals(1L, member.getSecurityVersion());
        service.toggleStatus("target", new UserStatusRequest(1, member.getRevision()));
        assertEquals(1L, member.getSecurityVersion());
        assertEquals(0L, user.getSecurityVersion());
    }

    @Test void adminPasswordUpdateAndResetAdvanceAccountSecurityVersion() {
        user.setSecurityVersion(null);
        UserSaveRequest request = request(); request.setPassword("newpass123");
        service.update("target", request);
        assertEquals(1L, user.getSecurityVersion());
        service.resetPassword("target", new UserPasswordResetRequest("otherpass123", user.getRevision(), member.getRevision()));
        assertEquals(2L, user.getSecurityVersion());
    }

    @Test void ownPasswordChangeAdvancesSecurityVersionButProfileChangeDoesNot() {
        auth.when(AuthUtil::getLoginUserId).thenReturn("target");
        user.setPasswordHash(new BCryptPasswordEncoder(4).encode("oldpass123"));
        service.changeOwnPassword("target", "oldpass123", "newpass123");
        assertEquals(1L, user.getSecurityVersion());
        service.updateOwnProfile("target", "Other", null, null);
        assertEquals(1L, user.getSecurityVersion());
    }

    @Test void disabledTargetAndFutureTemporaryRoleStillPassThroughManagementCeiling() {
        when(policy.requireOperator()).thenReturn(new PermissionGrantPolicy.Operator("operator-A", "cmp-A",
                new EffectiveRoleResolver.EffectiveRoles(false, List.of())));
        member.setStatusFlag(0);
        SysTempAuthorization future = new SysTempAuthorization(); future.setUserId("target"); future.setCompanyId("cmp-A");
        future.setRoleId("r-super"); future.setStatusFlag(1); future.setDeleteFlag(1);
        future.setEffectFrom(java.time.LocalDateTime.now().plusDays(1));
        future.setEffectTo(java.time.LocalDateTime.now().plusDays(2));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(tempMapper.selectList(any())).thenReturn(List.of(future));
        SysRole superRole = new SysRole(); superRole.setId("r-super"); superRole.setCompanyId("cmp-A");
        superRole.setDeleteFlag(1); superRole.setRoleCode("SUPER_ADMIN"); superRole.setStatusFlag(0);
        when(roleMapper.selectList(any())).thenReturn(List.of(superRole));
        doThrow(new ServiceException(ResultCode.FORBIDDEN)).when(policy).assertManagedRoles(any(), any());
        assertCode(ResultCode.FORBIDDEN, () -> service.toggleStatus("target", new UserStatusRequest(1, member.getRevision())));
        verify(policy).assertManagedRoles(any(), eq(List.of(superRole)));
        verify(membershipMapper, never()).updateById(any(SysUserCompany.class));
    }

    private SysUserCompany membership(String companyId) {
        SysUserCompany membership = new SysUserCompany(); membership.setId("member-" + companyId);
        membership.setUserId("target"); membership.setCompanyId(companyId); membership.setStatusFlag(1);
        membership.setDeleteFlag(1); membership.setRevision(2); return membership;
    }

    private UserSaveRequest request() {
        UserSaveRequest request = new UserSaveRequest(); request.setUsername("target"); request.setNickname("Target");
        request.setRevision(3); request.setMembershipRevision(2); return request;
    }

    private void assertCode(ResultCode expected, Runnable action) {
        assertEquals(expected.getCode(), assertThrows(ServiceException.class, action::run).getCode());
    }
}
