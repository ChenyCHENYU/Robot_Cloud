package com.robot.system.role.service;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RC11 权限绑定行为测试：菜单/按钮分流、未知 ID 拒绝、超授/自授禁令。
 */
@ExtendWith(MockitoExtension.class)
class RoleServicePermissionBindingTest {

    @Mock private SysRoleMapper roleMapper;
    @Mock private SysRoleMenuMapper roleMenuMapper;
    @Mock private SysRoleButtonMapper roleButtonMapper;
    @Mock private SysMenuMapper menuMapper;
    @Mock private SysMenuButtonMapper menuButtonMapper;
    @Mock private EffectiveRoleResolver resolver;
    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private SysTempAuthorizationMapper tempAuthMapper;
    @Mock private SysUserMapper userMapper;
    @Mock private PermissionChangeRecorder recorder;

    private RoleService service;
    private MockedStatic<AuthUtil> auth;

    @BeforeEach
    void setUp() {
        service = new RoleService(roleMenuMapper, roleButtonMapper, menuMapper,
                menuButtonMapper, resolver, userRoleMapper, tempAuthMapper,
                userMapper, recorder);
        // ServiceImpl.baseMapper 无 Spring 环境时手动注入
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "baseMapper", roleMapper);
        SysRole targetRole = new SysRole();
        targetRole.setId("role-1");
        lenient().when(roleMapper.selectById(any())).thenReturn(targetRole);
        auth = mockStatic(AuthUtil.class);
        auth.when(AuthUtil::getLoginUserId).thenReturn("operator-1");
        auth.when(AuthUtil::isSuperAdmin).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        auth.close();
    }

    private SysMenu menu(String id) {
        SysMenu m = new SysMenu();
        m.setId(id);
        m.setDeleteFlag(1);
        return m;
    }

    private SysMenuButton button(String id) {
        SysMenuButton b = new SysMenuButton();
        b.setId(id);
        b.setDeleteFlag(1);
        return b;
    }

    @Test
    @DisplayName("混合 permissionIds 分流：菜单进 role_menu，按钮进 role_button")
    void mixedIdsArePartitioned() {
        when(menuMapper.selectBatchIds(anyList())).thenReturn(List.of(menu("m-1")));
        when(menuButtonMapper.selectBatchIds(anyList())).thenReturn(List.of(button("b-1")));

        service.assignPermissions("role-1", List.of("m-1", "b-1"));

        verify(roleMenuMapper).insert(any(SysRoleMenu.class));
        verify(roleButtonMapper).insert(any(SysRoleButton.class));
        verify(recorder).record(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("未知权限 ID 拒绝（防静默丢授权）")
    void unknownIdRejected() {
        when(menuMapper.selectBatchIds(anyList())).thenReturn(List.of());
        when(menuButtonMapper.selectBatchIds(anyList())).thenReturn(List.of());

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.assignPermissions("role-1", List.of("ghost-id")));
        assertEquals(ResultCode.PARAM_INVALID.getCode(), e.getCode());
        verify(roleMenuMapper, never()).insert(any(SysRoleMenu.class));
        verify(roleButtonMapper, never()).insert(any(SysRoleButton.class));
    }

    @Test
    @DisplayName("非超管授予超出自身集合的资源被拒（超授禁令）")
    void overGrantRejectedForNonSuperAdmin() {
        auth.when(AuthUtil::isSuperAdmin).thenReturn(false);
        auth.when(AuthUtil::getLoginCompanyId).thenReturn("cmp-1");
        when(resolver.resolve("operator-1", "cmp-1"))
                .thenReturn(EffectiveRoleResolver.EffectiveRoles.EMPTY);
        lenient().when(menuMapper.selectBatchIds(anyList())).thenReturn(List.of(menu("m-1")));
        lenient().when(menuButtonMapper.selectBatchIds(anyList())).thenReturn(List.of());

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.assignPermissions("role-1", List.of("m-1")));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(roleMenuMapper, never()).insert(any(SysRoleMenu.class));
    }

    @Test
    @DisplayName("非超管修改自己持有角色的权限被拒（自授禁令）")
    void selfRoleEditRejectedForNonSuperAdmin() {
        auth.when(AuthUtil::isSuperAdmin).thenReturn(false);
        auth.when(AuthUtil::getLoginCompanyId).thenReturn("cmp-1");
        SysRole ownRole = new SysRole();
        ownRole.setId("role-self");
        when(resolver.resolve("operator-1", "cmp-1")).thenReturn(
                new EffectiveRoleResolver.EffectiveRoles(false, List.of(ownRole)));
        lenient().when(menuMapper.selectBatchIds(anyList())).thenReturn(List.of());
        lenient().when(menuButtonMapper.selectBatchIds(anyList())).thenReturn(List.of());

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.assignPermissions("role-self", List.of()));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    @DisplayName("重绑前清空旧绑定（菜单 + 按钮双表）")
    void rebindClearsBothTables() {
        when(menuMapper.selectBatchIds(anyList())).thenReturn(List.of(menu("m-1")));
        when(menuButtonMapper.selectBatchIds(anyList())).thenReturn(List.of());

        service.assignPermissions("role-1", List.of("m-1"));

        verify(roleMenuMapper).delete(any());
        verify(roleButtonMapper).delete(any());
        verify(roleMenuMapper).insert(any(SysRoleMenu.class));
        verify(roleButtonMapper, never()).insert(any(SysRoleButton.class));
    }
}
