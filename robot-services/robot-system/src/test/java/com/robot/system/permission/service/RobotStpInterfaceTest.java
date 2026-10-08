package com.robot.system.permission.service;

import com.robot.common.core.constant.RobotConstants;
import com.robot.common.redis.core.RedisHelper;
import com.robot.system.support.SystemKeys;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限数据源测试：缓存载荷（超管标志 + 权限码）语义、命中零 DB、超管直通。
 */
@ExtendWith(MockitoExtension.class)
class RobotStpInterfaceTest {

    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private SysRoleMenuMapper roleMenuMapper;
    @Mock
    private SysMenuMapper menuMapper;
    @Mock
    private SysMenuButtonMapper menuButtonMapper;
    @Mock
    private SysTempAuthorizationMapper tempAuthorizationMapper;
    @Mock
    private RedisHelper redisHelper;

    /** 带租户缝的实现：单测注入固定公司上下文。 */
    private RobotStpInterface stpInterface() {
        return new RobotStpInterface(roleMapper, userRoleMapper, roleMenuMapper,
                menuMapper, menuButtonMapper, tempAuthorizationMapper, redisHelper) {
            @Override
            String currentCompanyId() {
                return "cmp-1";
            }
        };
    }

    private SysRole role(String id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode(code);
        role.setCompanyId("cmp-1");
        role.setStatusFlag(1);
        role.setDeleteFlag(1);
        return role;
    }

    @Test
    @DisplayName("缓存命中：零 DB 查询直接返回载荷")
    void cacheHitShouldSkipDatabase() {
        when(redisHelper.getObject(anyString(),
                eq(RobotStpInterface.CachedPermissions.class)))
                .thenReturn(Optional.of(
                        new RobotStpInterface.CachedPermissions(false,
                                List.of("sys:user:list"))));
        RobotStpInterface stp = stpInterface();

        List<String> permissions = stp.getPermissionList("usr-1", "login");

        assertEquals(List.of("sys:user:list"), permissions);
        verify(roleMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("缓存超管载荷：返回 * 直通")
    void cachedSuperAdminShouldReturnWildcard() {
        when(redisHelper.getObject(anyString(),
                eq(RobotStpInterface.CachedPermissions.class)))
                .thenReturn(Optional.of(
                        new RobotStpInterface.CachedPermissions(true, List.of())));
        RobotStpInterface stp = stpInterface();

        assertEquals(List.of("*"), stp.getPermissionList("usr-1", "login"));
    }

    @Test
    @DisplayName("缓存 miss：聚合页面码 + 按钮码并回填缓存（含超管标志）")
    void missShouldAggregateAndCache() {
        SysRole staff = role("role-1", "STAFF");
        lenient().when(roleMapper.selectList(any())).thenReturn(List.of(staff));

        SysUserRole binding = new SysUserRole();
        binding.setUserId("usr-1");
        binding.setRoleId("role-1");
        binding.setDeleteFlag(1);
        lenient().when(userRoleMapper.selectList(any())).thenReturn(List.of(binding));
        lenient().when(tempAuthorizationMapper.selectList(any())).thenReturn(List.of());

        SysRoleMenu grant = new SysRoleMenu();
        grant.setRoleId("role-1");
        grant.setMenuId("menu-1");
        lenient().when(roleMenuMapper.selectList(any())).thenReturn(List.of(grant));

        SysMenu userMenu = new SysMenu();
        userMenu.setId("menu-1");
        userMenu.setPermCode("sys:user:list");
        userMenu.setStatusFlag(1);
        userMenu.setDeleteFlag(1);
        lenient().when(menuMapper.selectList(any())).thenReturn(List.of(userMenu));

        SysMenuButton saveButton = new SysMenuButton();
        saveButton.setMenuId("menu-1");
        saveButton.setPermCode("sys:user:save");
        saveButton.setStatusFlag(1);
        saveButton.setDeleteFlag(1);
        lenient().when(menuButtonMapper.selectList(any())).thenReturn(List.of(saveButton));

        when(redisHelper.getObject(anyString(),
                eq(RobotStpInterface.CachedPermissions.class)))
                .thenReturn(Optional.empty());

        RobotStpInterface stp = stpInterface();
        List<String> permissions = stp.getPermissionList("usr-1", "login");

        assertTrue(permissions.containsAll(List.of("sys:user:list", "sys:user:save")));

        ArgumentCaptor<RobotStpInterface.CachedPermissions> captor =
                ArgumentCaptor.forClass(RobotStpInterface.CachedPermissions.class);
        verify(redisHelper).setObject(anyString(), captor.capture(), any());
        assertFalse(captor.getValue().superAdmin);
        assertTrue(captor.getValue().permissions.contains("sys:user:list"));
    }

    @Test
    @DisplayName("新鲜 DB 角色判定超管（非会话快照）：超管直通且不再查菜单")
    void freshSuperAdminShouldShortCircuit() {
        lenient().when(roleMapper.selectList(any()))
                .thenReturn(List.of(role("role-9", RobotConstants.SUPER_ADMIN_ROLE_CODE)));
        lenient().when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(redisHelper.getObject(anyString(),
                eq(RobotStpInterface.CachedPermissions.class)))
                .thenReturn(Optional.empty());

        RobotStpInterface stp = stpInterface();
        assertEquals(List.of("*"), stp.getPermissionList("usr-1", "login"));

        verify(roleMenuMapper, never()).selectList(any());
        verify(redisHelper).setObject(eq(SystemKeys.userPermissions("cmp-1", "usr-1")),
                any(), any());
    }
}
