package com.robot.system.menu.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuQueryServiceTest {
    @Mock SysMenuMapper menuMapper;
    @Mock SysMenuButtonMapper buttonMapper;
    @Mock SysRoleMenuMapper roleMenuMapper;
    @Mock SysRoleButtonMapper roleButtonMapper;
    @Mock EffectiveRoleResolver resolver;
    MenuQueryService service;
    SysMenu root, leaf, other;

    @BeforeEach
    void setUp() {
        service =
                new MenuQueryService(
                        menuMapper, buttonMapper, roleMenuMapper, roleButtonMapper, resolver);
        root = menu("m-root", null, "/sys", "directory:query");
        leaf = menu("m-users", "m-root", "users", "sys:user:list");
        other = menu("m-other", "m-root", "other", "other:query");
        SysRole role = new SysRole();
        role.setId("r-own");
        lenient()
                .when(resolver.resolve("u-1", "c-1"))
                .thenReturn(new EffectiveRoleResolver.EffectiveRoles(false, List.of(role)));
        lenient().when(menuMapper.selectList(any())).thenReturn(List.of(root, leaf, other));
        lenient().when(roleMenuMapper.selectList(any())).thenReturn(List.of());
        lenient().when(roleButtonMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void noRoleHasNoRoutesOrButtonsAndNoGlobalMenuScan() {
        when(resolver.resolve("u-1", "c-1")).thenReturn(EffectiveRoleResolver.EffectiveRoles.EMPTY);
        assertTrue(service.routesOfUser("u-1", "c-1").isEmpty());
        assertTrue(service.buttonListOfUser("u-1", "c-1").isEmpty());
        verifyNoInteractions(menuMapper, buttonMapper, roleMenuMapper, roleButtonMapper);
    }

    @Test
    void explicitPageIncludesNavigationAncestorWithoutAncestorPermissionOrButtons() {
        when(roleMenuMapper.selectList(any()))
                .thenReturn(
                        List.of(
                                menuBinding("r-own", "m-users"),
                                menuBinding("r-other", "m-other")));
        var routes = service.routesOfUser("u-1", "c-1");
        assertEquals(1, routes.size());
        assertEquals("/sys", routes.get(0).getPath());
        assertEquals("users", routes.get(0).getChildren().get(0).getPath());
        var permissions = service.buttonListOfUser("u-1", "c-1");
        assertEquals(Set.of("/sys/users"), permissions.keySet());
        assertEquals(List.of("sys:user:list"), permissions.get("/sys/users"));
        verifyNoInteractions(buttonMapper);
    }

    @Test
    void exactButtonNavigatesToParentButDoesNotGrantPageOrOtherButton() {
        when(roleButtonMapper.selectList(any()))
                .thenReturn(
                        List.of(
                                buttonBinding("r-own", "b-delete"),
                                buttonBinding("r-other", "b-reset")));
        when(buttonMapper.selectBatchIds(any()))
                .thenReturn(
                        List.of(
                                button("b-delete", "sys:user:delete"),
                                button("b-reset", "sys:user:resetPassword")));
        var permissions = service.buttonListOfUser("u-1", "c-1");
        assertEquals(List.of("sys:user:delete"), permissions.get("/sys/users"));
        assertFalse(permissions.containsKey("/sys"));
        var routes = service.routesOfUser("u-1", "c-1");
        assertEquals("users", routes.get(0).getChildren().get(0).getPath());
    }

    @Test
    void disabledParentMakesBoundButtonUnavailable() {
        leaf.setStatusFlag(0);
        when(roleButtonMapper.selectList(any()))
                .thenReturn(List.of(buttonBinding("r-own", "b-delete")));
        when(buttonMapper.selectBatchIds(any()))
                .thenReturn(List.of(button("b-delete", "sys:user:delete")));
        assertTrue(service.buttonListOfUser("u-1", "c-1").isEmpty());
        assertTrue(service.routesOfUser("u-1", "c-1").isEmpty());
    }

    @Test
    void permissionCatalogueIdsMatchBindableResourceIds() {
        when(buttonMapper.selectList(any()))
                .thenReturn(List.of(button("b-delete", "sys:user:delete")));
        var catalogue = service.permissionList();
        assertTrue(
                catalogue.stream()
                        .anyMatch(
                                item ->
                                        item.id().equals("m-users")
                                                && item.permCode().equals("sys:user:list")));
        assertTrue(
                catalogue.stream()
                        .anyMatch(
                                item ->
                                        item.id().equals("b-delete")
                                                && item.permCode().equals("sys:user:delete")));
    }

    @Test
    void managementTreeStillShowsDisabledMenuForReEnable() {
        leaf.setStatusFlag(0);
        assertEquals(2, service.menuTree().get(0).children().size());
    }

    private SysMenu menu(String id, String parent, String path, String code) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setParentId(parent);
        menu.setPath(path);
        menu.setPermCode(code);
        menu.setMenuType("menu");
        menu.setStatusFlag(1);
        menu.setDeleteFlag(1);
        return menu;
    }

    private SysRoleMenu menuBinding(String role, String menu) {
        SysRoleMenu binding = new SysRoleMenu();
        binding.setRoleId(role);
        binding.setMenuId(menu);
        binding.setDeleteFlag(1);
        return binding;
    }

    private SysRoleButton buttonBinding(String role, String button) {
        SysRoleButton binding = new SysRoleButton();
        binding.setRoleId(role);
        binding.setButtonId(button);
        binding.setDeleteFlag(1);
        return binding;
    }

    private SysMenuButton button(String id, String code) {
        SysMenuButton button = new SysMenuButton();
        button.setId(id);
        button.setMenuId("m-users");
        button.setPermCode(code);
        button.setStatusFlag(1);
        button.setDeleteFlag(1);
        return button;
    }
}
