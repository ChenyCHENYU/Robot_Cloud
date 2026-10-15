package com.robot.system.role.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.role.RoleSaveRequest;
import com.robot.system.api.dto.role.TempAuthorizationCreateRequest;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RoleCommandServicePermissionBindingTest {
    private static final int MAX_ROLE_NAME_LENGTH = 30;
    private static final int MAX_REMARK_LENGTH = 255;
    private static final int MAX_RESOURCE_ID_LENGTH = 64;
    private static final int ORIGINAL_ROLE_SORT = 7;
    private static final int UPDATED_ROLE_SORT = 3;

    @Mock SysRoleMapper roleMapper;
    @Mock SysRoleMenuMapper roleMenuMapper;
    @Mock SysRoleButtonMapper roleButtonMapper;
    @Mock SysMenuMapper menuMapper;
    @Mock SysMenuButtonMapper menuButtonMapper;
    @Mock EffectiveRoleResolver resolver;
    @Mock SysUserRoleMapper userRoleMapper;
    @Mock SysTempAuthorizationMapper tempMapper;
    @Mock SysUserMapper userMapper;
    @Mock SysUserCompanyMapper membershipMapper;
    @Mock PermissionChangeRecorder recorder;
    RoleCommandService service;
    TemporaryAuthorizationCommandService temporaryCommands;
    MockedStatic<AuthUtil> auth;
    SysRole target;

    @BeforeEach
    void setUp() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "role-command");
        for (var type :
                List.of(
                        SysRole.class,
                        SysRoleMenu.class,
                        SysRoleButton.class,
                        SysMenu.class,
                        SysMenuButton.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
        service =
                new RoleCommandService(
                        roleMenuMapper,
                        roleButtonMapper,
                        menuMapper,
                        menuButtonMapper,
                        new PermissionGrantPolicy(resolver, roleMenuMapper, roleButtonMapper),
                        userRoleMapper,
                        tempMapper,
                        recorder,
                        Clock.systemDefaultZone(),
                        mock(com.robot.system.permission.service.PermissionStateStore.class));
        temporaryCommands =
                new TemporaryAuthorizationCommandService(
                        roleMapper,
                        tempMapper,
                        userMapper,
                        membershipMapper,
                        new PermissionGrantPolicy(resolver, roleMenuMapper, roleButtonMapper),
                        recorder,
                        Clock.systemDefaultZone(),
                        mock(com.robot.system.permission.service.PermissionStateStore.class));
        ReflectionTestUtils.setField(service, "baseMapper", roleMapper);
        auth = mockStatic(AuthUtil.class);
        auth.when(AuthUtil::getLoginUserId).thenReturn("operator");
        auth.when(AuthUtil::getLoginCompanyId).thenReturn("c-1");
        lenient()
                .when(resolver.resolvePermanent("operator", "c-1"))
                .thenReturn(
                        new EffectiveRoleResolver.EffectiveRoles(
                                true, List.of(role("r-admin", "SUPER_ADMIN"))));
        lenient()
                .when(resolver.resolve("operator", "c-1"))
                .thenReturn(
                        new EffectiveRoleResolver.EffectiveRoles(
                                true, List.of(role("r-admin", "SUPER_ADMIN"))));
        target = role("r-target", "STAFF");
        lenient().when(roleMapper.selectById(any())).thenReturn(target);
        lenient().when(roleMapper.updateById(any(SysRole.class))).thenReturn(1);
        lenient().when(roleMenuMapper.insert(any(SysRoleMenu.class))).thenReturn(1);
        lenient().when(roleButtonMapper.insert(any(SysRoleButton.class))).thenReturn(1);
        lenient()
                .when(menuMapper.selectBatchIds(any()))
                .thenAnswer(
                        invocation -> {
                            Collection<?> ids = invocation.getArgument(0);
                            return ids.contains("m-1") ? List.of(menu("m-1")) : List.of();
                        });
        lenient()
                .when(menuButtonMapper.selectBatchIds(any()))
                .thenAnswer(
                        invocation -> {
                            Collection<?> ids = invocation.getArgument(0);
                            return ids.contains("b-1") ? List.of(button("b-1")) : List.of();
                        });
    }

    @AfterEach
    void close() {
        auth.close();
    }

    @Test
    void mixedIdsBindExactlyAndNotImplicitPageButtons() {
        service.assignPermissions("r-target", List.of("m-1", "b-1"), 2);
        verify(roleMenuMapper)
                .insert(argThat((SysRoleMenu binding) -> "m-1".equals(binding.getMenuId())));
        verify(roleButtonMapper)
                .insert(argThat((SysRoleButton binding) -> "b-1".equals(binding.getButtonId())));
        verify(recorder).record(eq("assign_permissions"), eq("r-target"), any());
    }

    @Test
    void pageOnlyDoesNotInsertButtons() {
        service.assignPermissions("r-target", List.of("m-1"), 2);
        verify(roleButtonMapper, never()).insert(any(SysRoleButton.class));
    }

    @Test
    void unknownDuplicateAndInactiveIdsFailBeforeDeletingOldBindings() {
        assertCode(
                ResultCode.PARAM_INVALID,
                () -> service.assignPermissions("r-target", List.of("ghost"), 2));
        assertCode(
                ResultCode.PARAM_INVALID,
                () -> service.assignPermissions("r-target", List.of("m-1", "m-1"), 2));
        SysMenu disabled = menu("m-1");
        disabled.setStatusFlag(0);
        doReturn(List.of(disabled)).when(menuMapper).selectBatchIds(any());
        assertCode(
                ResultCode.PARAM_INVALID,
                () -> service.assignPermissions("r-target", List.of("m-1"), 2));
        verify(roleMenuMapper, never()).delete(any());
        verify(roleMapper, never()).updateById(any(SysRole.class));
    }

    @Test
    void zeroAffectedRowsStopsAssociatedWritesAndSuccessAudit() {
        when(roleMapper.updateById(any(SysRole.class))).thenReturn(0);
        assertCode(
                ResultCode.DATA_CONCURRENT_CONFLICT,
                () -> service.assignPermissions("r-target", List.of("m-1"), 2));
        verify(roleMenuMapper, never()).delete(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void clientRevisionConflictStopsBeforeAnyWrite() {
        assertCode(
                ResultCode.DATA_CONCURRENT_CONFLICT,
                () -> service.assignPermissions("r-target", List.of("m-1"), 1));
        verify(roleMapper, never()).updateById(any(SysRole.class));
        verify(roleMenuMapper, never()).delete(any());
    }

    @Test
    void staleSessionSuperAdminCannotCreateSuperRole() {
        ordinaryOperator();
        auth.when(AuthUtil::isSuperAdmin).thenReturn(true);
        RoleSaveRequest request = request();
        request.setRoleCode("SUPER_ADMIN");
        assertCode(ResultCode.FORBIDDEN, () -> service.create(request));
        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    void temporaryOnlyOperatorCannotGrantPermanentRoleOrLongerTemporaryAuthorization() {
        when(resolver.resolvePermanent("operator", "c-1"))
                .thenReturn(EffectiveRoleResolver.EffectiveRoles.EMPTY);
        lenient()
                .when(resolver.resolve("operator", "c-1"))
                .thenReturn(
                        new EffectiveRoleResolver.EffectiveRoles(
                                true, List.of(role("r-temp", "SUPER_ADMIN"))));
        assertCode(ResultCode.FORBIDDEN, () -> service.create(request()));
        assertCode(
                ResultCode.FORBIDDEN,
                () ->
                        temporaryCommands.createTempAuthorization(
                                new TempAuthorizationCreateRequest()));
        verify(roleMapper, never()).insert(any(SysRole.class));
        verifyNoInteractions(tempMapper);
    }

    @Test
    void createAndUpdateCannotBypassGrantCeilingWithLegacyFields() {
        ordinaryOperator();
        RoleSaveRequest create = request();
        create.setMenuIds(List.of("m-secret"));
        assertCode(ResultCode.FORBIDDEN, () -> service.create(create));
        RoleSaveRequest update = request();
        update.setButtonIds(List.of("b-secret"));
        assertCode(ResultCode.FORBIDDEN, () -> service.update("r-target", update));
        verify(roleMapper, never()).insert(any(SysRole.class));
        verify(roleMapper, never()).updateById(any(SysRole.class));
    }

    @Test
    void editingOwnRoleIsRejectedBeforeFieldsOrBindingsChange() {
        when(resolver.resolvePermanent("operator", "c-1"))
                .thenReturn(new EffectiveRoleResolver.EffectiveRoles(false, List.of(target)));
        RoleSaveRequest request = request();
        request.setRoleCode("SUPER_ADMIN");
        assertCode(ResultCode.FORBIDDEN, () -> service.update("r-target", request));
        assertEquals("STAFF", target.getRoleCode());
        verify(roleMapper, never()).updateById(any(SysRole.class));
    }

    @Test
    void ordinaryOperatorCannotEditTemporarilyHeldRoleEither() {
        ordinaryOperator();
        when(resolver.resolve("operator", "c-1"))
                .thenReturn(
                        new EffectiveRoleResolver.EffectiveRoles(
                                false, List.of(role("r-own", "STAFF"), target)));
        assertCode(ResultCode.FORBIDDEN, () -> service.update("r-target", request()));
        verify(roleMapper, never()).updateById(any(SysRole.class));
        verifyNoInteractions(recorder);
    }

    @Test
    void missingIdentityAndCrossCompanyRoleNeverBypassGuards() {
        auth.when(AuthUtil::getLoginUserId).thenReturn(null);
        assertCode(
                ResultCode.UNAUTHORIZED, () -> service.assignPermissions("r-target", List.of(), 2));
        auth.when(AuthUtil::getLoginUserId).thenReturn("operator");
        target.setCompanyId("c-other");
        assertCode(ResultCode.FORBIDDEN, () -> service.assignPermissions("r-target", List.of(), 2));
    }

    @Test
    void absentScopePreservesLegacyCustomButNewCustomIsRejected() {
        target.setDataScope("custom");
        service.update("r-target", request());
        assertEquals("custom", target.getDataScope());
        RoleSaveRequest request = request();
        request.setDataScope("custom");
        assertCode(ResultCode.PARAM_INVALID, () -> service.update("r-target", request));
    }

    @Test
    void malformedFieldsMissingRevisionAndInvalidResourceIdsNeverReachWrites() {
        for (java.util.function.Consumer<RoleSaveRequest> invalid :
                List.<java.util.function.Consumer<RoleSaveRequest>>of(
                        body -> body.setRoleName(" "),
                        body -> body.setRoleName("a".repeat(MAX_ROLE_NAME_LENGTH + 1)),
                        body -> body.setRoleCode("lowercase"),
                        body -> body.setStatus(2),
                        body -> body.setRemark("a".repeat(MAX_REMARK_LENGTH + 1)))) {
            RoleSaveRequest body = request();
            invalid.accept(body);
            assertCode(ResultCode.PARAM_INVALID, () -> service.update("r-target", body));
        }
        assertCode(
                ResultCode.PARAM_INVALID,
                () -> service.assignPermissions("r-target", List.of(), null));
        assertCode(
                ResultCode.PARAM_INVALID,
                () -> service.assignPermissions("r-target", List.of(), -1));
        assertCode(ResultCode.PARAM_INVALID, () -> service.assignPermissions("r-target", null, 2));
        assertCode(
                ResultCode.PARAM_INVALID,
                () ->
                        service.assignPermissions(
                                "r-target", java.util.Arrays.asList((String) null), 2));
        assertCode(
                ResultCode.PARAM_INVALID,
                () -> service.assignPermissions("r-target", List.of(" "), 2));
        assertCode(
                ResultCode.PARAM_INVALID,
                () ->
                        service.assignPermissions(
                                "r-target", List.of("a".repeat(MAX_RESOURCE_ID_LENGTH + 1)), 2));
        verify(roleMapper, never()).updateById(any(SysRole.class));
        verifyNoInteractions(recorder);
    }

    @Test
    void superRoleCannotBeDisabledOrDeletedAndReferencedRolesCannotBeRemoved() {
        target.setRoleCode("SUPER_ADMIN");
        RoleSaveRequest disable = request();
        disable.setRoleCode("SUPER_ADMIN");
        disable.setStatus(0);
        assertCode(ResultCode.FORBIDDEN, () -> service.update("r-target", disable));
        assertCode(ResultCode.FORBIDDEN, () -> service.delete("r-target", 2));
        target.setRoleCode("STAFF");
        when(userRoleMapper.selectCount(any())).thenReturn(1L);
        assertCode(ResultCode.DATA_REFERENCED, () -> service.delete("r-target", 2));
        when(userRoleMapper.selectCount(any())).thenReturn(0L);
        when(tempMapper.selectCount(any())).thenReturn(1L);
        assertCode(ResultCode.DATA_REFERENCED, () -> service.delete("r-target", 2));
        verify(roleMapper, never()).delete(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void explicitEmptyBindingsClearWhileOmittedFieldsPreserveExistingValuesAndDuplicateCodeFails() {
        target.setSortNo(ORIGINAL_ROLE_SORT);
        target.setStatusFlag(0);
        var body = request();
        service.update("r-target", body);
        assertEquals(ORIGINAL_ROLE_SORT, target.getSortNo());
        assertEquals(0, target.getStatusFlag());
        verify(roleMenuMapper, never()).delete(any());
        body.setPermissionIds(List.of());
        body.setSortNo(UPDATED_ROLE_SORT);
        body.setStatus(1);
        body.setDataScope("all");
        service.update("r-target", body);
        assertEquals(UPDATED_ROLE_SORT, target.getSortNo());
        assertEquals(1, target.getStatusFlag());
        assertEquals("all", target.getDataScope());
        verify(roleMenuMapper).delete(any());
        verify(roleButtonMapper).delete(any());
        when(roleMapper.selectCount(any())).thenReturn(1L);
        assertCode(ResultCode.ROLE_CODE_DUPLICATED, () -> service.create(request()));
    }

    @Test
    void invalidButtonParentInactiveButtonAndAmbiguousResourceFailBeforeClearingBindings() {
        SysMenu disabledParent = menu("m-1");
        disabledParent.setDeleteFlag(0);
        doReturn(List.of(disabledParent)).when(menuMapper).selectBatchIds(any());
        assertCode(
                ResultCode.PARAM_INVALID,
                () -> service.assignPermissions("r-target", List.of("b-1"), 2));
        SysMenuButton disabledButton = button("b-1");
        disabledButton.setStatusFlag(0);
        doReturn(List.of(disabledButton)).when(menuButtonMapper).selectBatchIds(any());
        assertCode(
                ResultCode.PARAM_INVALID,
                () -> service.assignPermissions("r-target", List.of("b-1"), 2));
        disabledButton.setStatusFlag(1);
        disabledButton.setId("m-1");
        doReturn(List.of(menu("m-1"))).when(menuMapper).selectBatchIds(any());
        assertCode(
                ResultCode.PARAM_INVALID,
                () -> service.assignPermissions("r-target", List.of("m-1"), 2));
        verify(roleMenuMapper, never()).delete(any());
    }

    private void ordinaryOperator() {
        when(resolver.resolvePermanent("operator", "c-1"))
                .thenReturn(
                        new EffectiveRoleResolver.EffectiveRoles(
                                false, List.of(role("r-own", "STAFF"))));
        lenient()
                .when(resolver.resolve("operator", "c-1"))
                .thenReturn(
                        new EffectiveRoleResolver.EffectiveRoles(
                                false, List.of(role("r-own", "STAFF"))));
        lenient().when(roleMenuMapper.selectList(any())).thenReturn(List.of());
        lenient().when(roleButtonMapper.selectList(any())).thenReturn(List.of());
    }

    private RoleSaveRequest request() {
        RoleSaveRequest request = new RoleSaveRequest();
        request.setRoleName("Staff");
        request.setRoleCode("STAFF");
        request.setRevision(2);
        return request;
    }

    private SysRole role(String id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCompanyId("c-1");
        role.setRoleCode(code);
        role.setDataScope("self");
        role.setDeleteFlag(1);
        role.setStatusFlag(1);
        role.setRevision(2);
        return role;
    }

    private SysMenu menu(String id) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setDeleteFlag(1);
        menu.setStatusFlag(1);
        return menu;
    }

    private SysMenuButton button(String id) {
        SysMenuButton button = new SysMenuButton();
        button.setId(id);
        button.setMenuId("m-1");
        button.setDeleteFlag(1);
        button.setStatusFlag(1);
        return button;
    }

    private void assertCode(ResultCode expected, Runnable action) {
        assertEquals(
                expected.getCode(), assertThrows(ServiceException.class, action::run).getCode());
    }
}
