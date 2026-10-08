package com.robot.system.permission.service;

import com.robot.common.redis.core.RedisHelper;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.EffectiveRoleResolver.EffectiveRoles;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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
 * 权限数据源测试（RC01/RC02/RC05 整改回归）：
 * 超管只来自用户自身有效角色；缓存键携带版本实际值；命中零 DB。
 */
@ExtendWith(MockitoExtension.class)
class RobotStpInterfaceTest {

    @Mock
    private SysRoleMenuMapper roleMenuMapper;
    @Mock
    private SysRoleButtonMapper roleButtonMapper;
    @Mock
    private SysMenuMapper menuMapper;
    @Mock
    private SysMenuButtonMapper menuButtonMapper;
    @Mock
    private EffectiveRoleResolver effectiveRoleResolver;
    @Mock
    private RedisHelper redisHelper;

    private RobotStpInterface stpInterface() {
        return new RobotStpInterface(roleMenuMapper, roleButtonMapper, menuMapper,
                menuButtonMapper, effectiveRoleResolver, redisHelper) {
            @Override
            String currentCompanyId() {
                return "cmp-1";
            }
        };
    }

    private void cacheMiss() {
        lenient().when(redisHelper.readVersion(anyString())).thenReturn(1L);
        when(redisHelper.getObject(anyString(),
                eq(RobotStpInterface.CachedPermissions.class)))
                .thenReturn(java.util.Optional.empty());
    }

    /** RC01 核心负向：公司存在超管角色，但用户未绑定 → 绝无通配权限。 */
    @Test
    @DisplayName("RC01: 公司有超管而用户零绑定 → 无 * 且无任何权限码")
    void companySuperAdminMustNotLeakToUnboundUser() {
        when(effectiveRoleResolver.resolve("usr-1", "cmp-1"))
                .thenReturn(EffectiveRoles.EMPTY);
        cacheMiss();

        List<String> permissions = stpInterface().getPermissionList("usr-1", "login");

        assertTrue(permissions.isEmpty(), "零绑定用户不得获得任何权限，实际: " + permissions);
        verify(roleMenuMapper, never()).selectList(any());
        verify(menuMapper, never()).selectList(any());
    }

    /** RC01 正向：用户自身绑定超管 → 通配。 */
    @Test
    @DisplayName("用户自身绑定超管角色 → 返回 *")
    void boundSuperAdminReturnsWildcard() {
        SysRole superRole = new SysRole();
        superRole.setId("role-super");
        superRole.setRoleCode("SUPER_ADMIN");
        when(effectiveRoleResolver.resolve("usr-1", "cmp-1"))
                .thenReturn(new EffectiveRoles(true, List.of(superRole)));
        cacheMiss();

        assertEquals(List.of("*"), stpInterface().getPermissionList("usr-1", "login"));
        verify(roleMenuMapper, never()).selectList(any());
    }

    /** 普通角色：只获得自身角色授权的页面码 + 按钮码。 */
    @Test
    @DisplayName("普通绑定用户 → 页面码 + 按钮码，回填缓存载荷")
    void normalRoleGetsOwnPermissionsOnly() {
        SysRole staff = new SysRole();
        staff.setId("role-1");
        when(effectiveRoleResolver.resolve("usr-1", "cmp-1"))
                .thenReturn(new EffectiveRoles(false, List.of(staff)));
        cacheMiss();

        SysRoleMenu grant = new SysRoleMenu();
        grant.setRoleId("role-1");
        grant.setMenuId("menu-1");
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(grant));

        SysMenu userMenu = new SysMenu();
        userMenu.setId("menu-1");
        userMenu.setPermCode("sys:user:list");
        userMenu.setStatusFlag(1);
        userMenu.setDeleteFlag(1);
        when(menuMapper.selectList(any())).thenReturn(List.of(userMenu));

        // RC11：按钮只来自 sys_role_button 精确绑定
        SysRoleButton buttonBinding = new SysRoleButton();
        buttonBinding.setRoleId("role-1");
        buttonBinding.setButtonId("btn-save");
        when(roleButtonMapper.selectList(any())).thenReturn(List.of(buttonBinding));

        SysMenuButton saveButton = new SysMenuButton();
        saveButton.setId("btn-save");
        saveButton.setMenuId("menu-1");
        saveButton.setPermCode("sys:user:save");
        saveButton.setStatusFlag(1);
        saveButton.setDeleteFlag(1);
        when(menuButtonMapper.selectBatchIds(any())).thenReturn(List.of(saveButton));

        List<String> permissions = stpInterface().getPermissionList("usr-1", "login");

        assertTrue(permissions.contains("sys:user:list"));
        assertTrue(permissions.contains("sys:user:save"));
        // RC11：按钮仅来自精确绑定集合（selectBatchIds 仅收到绑定的 btn-save）
        org.mockito.ArgumentCaptor<java.util.Collection<String>> ids =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(menuButtonMapper).selectBatchIds(ids.capture());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("btn-save"),
                List.copyOf(ids.getValue()));

        ArgumentCaptor<RobotStpInterface.CachedPermissions> captor =
                ArgumentCaptor.forClass(RobotStpInterface.CachedPermissions.class);
        verify(redisHelper).setObject(anyString(), captor.capture(), any());
        assertFalse(captor.getValue().superAdmin);
        assertTrue(captor.getValue().permissions.contains("sys:user:list"));
    }

    /** RC05 回归：键携带版本实际值；版本自增后使用新键（旧缓存不再命中）。 */
    @Test
    @DisplayName("RC05: 版本自增后缓存键改变，旧版本缓存不命中")
    void versionBumpChangesCacheKey() {
        when(effectiveRoleResolver.resolve("usr-1", "cmp-1"))
                .thenReturn(EffectiveRoles.EMPTY);
        when(redisHelper.readVersion(anyString())).thenReturn(1L);
        when(redisHelper.getObject(anyString(),
                eq(RobotStpInterface.CachedPermissions.class)))
                .thenReturn(java.util.Optional.empty());

        stpInterface().getPermissionList("usr-1", "login");
        verify(redisHelper).setObject(
                eq(com.robot.system.support.SystemKeys.userPermissions("cmp-1", "usr-1", 1L)),
                any(), any());

        when(redisHelper.readVersion(anyString())).thenReturn(2L);
        stpInterface().getPermissionList("usr-1", "login");
        verify(redisHelper).setObject(
                eq(com.robot.system.support.SystemKeys.userPermissions("cmp-1", "usr-1", 2L)),
                any(), any());
    }

    /** 缓存命中：零 DB 查询直接返回载荷。 */
    @Test
    @DisplayName("缓存命中：零 DB 查询")
    void cacheHitSkipsResolverAndDb() {
        lenient().when(redisHelper.readVersion(anyString())).thenReturn(1L);
        when(redisHelper.getObject(anyString(),
                eq(RobotStpInterface.CachedPermissions.class)))
                .thenReturn(java.util.Optional.of(
                        new RobotStpInterface.CachedPermissions(false,
                                List.of("sys:user:list"))));

        List<String> permissions = stpInterface().getPermissionList("usr-1", "login");

        assertEquals(List.of("sys:user:list"), permissions);
        verify(effectiveRoleResolver, never()).resolve(anyString(), anyString());
    }

    /** 两公司隔离：解析器按 (userId, companyId) 取数，缓存键含公司段。 */
    @Test
    @DisplayName("两公司：各自解析互不串扰，缓存键按公司隔离")
    void twoCompaniesAreIsolated() {
        when(effectiveRoleResolver.resolve("usr-1", "cmp-A"))
                .thenReturn(EffectiveRoles.EMPTY);
        when(effectiveRoleResolver.resolve("usr-1", "cmp-B"))
                .thenReturn(EffectiveRoles.EMPTY);
        cacheMiss();

        RobotStpInterface cmpA = new RobotStpInterface(roleMenuMapper, roleButtonMapper,
                menuMapper, menuButtonMapper, effectiveRoleResolver, redisHelper) {
            @Override String currentCompanyId() { return "cmp-A"; }
        };
        RobotStpInterface cmpB = new RobotStpInterface(roleMenuMapper, roleButtonMapper,
                menuMapper, menuButtonMapper, effectiveRoleResolver, redisHelper) {
            @Override String currentCompanyId() { return "cmp-B"; }
        };

        assertTrue(cmpA.getPermissionList("usr-1", "login").isEmpty());
        assertTrue(cmpB.getPermissionList("usr-1", "login").isEmpty());
        verify(effectiveRoleResolver).resolve("usr-1", "cmp-A");
        verify(effectiveRoleResolver).resolve("usr-1", "cmp-B");
    }
}
