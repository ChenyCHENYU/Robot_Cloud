package com.robot.system.menu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.api.system.dto.RemoteMenuRouteDTO;
import com.robot.common.core.constant.RobotConstants;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.api.vo.menu.MenuButtonVO;
import com.robot.system.api.vo.menu.MenuTreeNode;
import com.robot.system.api.vo.permission.PermissionItem;
import com.robot.system.api.vo.permission.PermissionNode;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.EffectiveRoleResolver.EffectiveRoles;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 菜单读侧（CQRS-lite）：树/路由/按钮映射/权限树/权限码清单等全部查询装配。
 * 写侧见 {@link MenuService}；两侧共享菜单表但互不依赖，读侧无事务。
 */
@Service
@RequiredArgsConstructor
public class MenuQueryService {

    private final SysMenuMapper menuMapper;
    private final SysMenuButtonMapper menuButtonMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleButtonMapper roleButtonMapper;
    private final EffectiveRoleResolver effectiveRoleResolver;

    /** 全量菜单树（管理端；菜单为全局表，可见性由角色授权控制）。 */
    public List<MenuTreeNode> menuTree() {
        return buildTree(activeMenus(), null);
    }

    /** 用户可用路由树（/auth/menu-list 数据源）。 */
    public List<RemoteMenuRouteDTO> routesOfUser(String userId, String companyId) {
        Set<String> grantedIds = grantedMenuIds(userId, companyId);
        List<SysMenu> menus = grantedIds == null
                ? activeMenus()
                : activeMenus().stream().filter(m -> grantedIds.contains(m.getId())).toList();
        return toRoutes(menus, null);
    }

    /**
     * 用户按钮权限映射：菜单 path → 权限码集合（/auth/button-list 数据源）。
     * RC11：按钮码只来自 sys_role_button 精确绑定且挂在已授权菜单下。
     */
    public Map<String, List<String>> buttonListOfUser(String userId, String companyId) {
        EffectiveRoleResolver.EffectiveRoles effective =
                effectiveRoleResolver.resolve(userId, companyId);
        Set<String> grantedIds = effective.superAdmin() ? null
                : menuIdsOfRoles(effective.roleIds());
        List<SysMenu> menus = activeMenus().stream()
                .filter(menu -> menu.getPath() != null && !menu.getPath().isBlank())
                .filter(menu -> grantedIds == null || grantedIds.contains(menu.getId()))
                .toList();
        if (menus.isEmpty()) {
            return Map.of();
        }
        Map<String, List<SysMenuButton>> byMenu = boundButtons(effective).stream()
                .collect(Collectors.groupingBy(SysMenuButton::getMenuId));

        Map<String, List<String>> result = new HashMap<>();
        for (SysMenu menu : menus) {
            List<String> codes = new ArrayList<>(byMenu
                    .getOrDefault(menu.getId(), List.of()).stream()
                    .map(SysMenuButton::getPermCode).toList());
            // 页面级权限码并入（如 sys:user:list）
            if (menu.getPermCode() != null && !menu.getPermCode().isBlank()) {
                codes.add(menu.getPermCode());
            }
            result.put(menu.getPath(), codes);
        }
        return result;
    }

    private Set<String> menuIdsOfRoles(Set<String> roleIds) {
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .in(SysRoleMenu::getRoleId, roleIds))
                .stream().map(SysRoleMenu::getMenuId).collect(Collectors.toSet());
    }

    /** 精确绑定的按钮（超管为全部）。 */
    private List<SysMenuButton> boundButtons(EffectiveRoleResolver.EffectiveRoles effective) {
        if (effective.superAdmin()) {
            return menuButtonMapper.selectList(new LambdaQueryWrapper<SysMenuButton>()
                    .eq(SysMenuButton::getStatusFlag, 1)
                    .eq(SysMenuButton::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE));
        }
        if (effective.isEmpty()) {
            return List.of();
        }
        List<String> buttonIds = roleButtonMapper.selectList(
                        new LambdaQueryWrapper<SysRoleButton>()
                                .in(SysRoleButton::getRoleId, effective.roleIds()))
                .stream().map(SysRoleButton::getButtonId).toList();
        if (buttonIds.isEmpty()) {
            return List.of();
        }
        return menuButtonMapper.selectBatchIds(buttonIds).stream()
                .filter(button -> button.getStatusFlag() != null && button.getStatusFlag() == 1
                        && button.getDeleteFlag() != null
                        && button.getDeleteFlag() == RobotConstants.DELETE_FLAG_ACTIVE)
                .toList();
    }

    /** 按钮清单（menuId 空 = 全量；管理端与装配复用）。 */
    public List<MenuButtonVO> buttonsOfMenu(String menuId) {
        return menuButtonMapper.selectList(new LambdaQueryWrapper<SysMenuButton>()
                        .eq(menuId != null && !menuId.isBlank(), SysMenuButton::getMenuId, menuId)
                        .eq(SysMenuButton::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                        .orderByAsc(SysMenuButton::getSortNo))
                .stream()
                .map(button -> new MenuButtonVO(button.getId(), button.getMenuId(),
                        button.getButtonName(), button.getPermCode(),
                        button.getSortNo(), button.getStatusFlag()))
                .toList();
    }

    /** 权限树（/sys/permissions/tree：菜单 + 按钮；按钮一次批量查询）。 */
    public List<PermissionNode> permissionTree() {
        Map<String, List<MenuButtonVO>> buttonsByMenu = buttonsOfMenu(null).stream()
                .collect(Collectors.groupingBy(MenuButtonVO::menuId));
        return appendButtons(menuTree(), buttonsByMenu);
    }

    /** 权限码清单（/sys/permissions：页面码 + 按钮码）。 */
    public List<PermissionItem> permissionList() {
        List<PermissionItem> result = new ArrayList<>();
        for (SysMenu menu : activeMenus()) {
            if (menu.getPermCode() != null && !menu.getPermCode().isBlank()) {
                result.add(new PermissionItem(menu.getPermCode(), menu.getId(),
                        menu.getMenuName(), menu.getPermCode(), "menu"));
            }
        }
        for (SysMenuButton button : menuButtonMapper.selectList(
                new LambdaQueryWrapper<SysMenuButton>()
                        .eq(SysMenuButton::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))) {
            result.add(new PermissionItem(button.getPermCode(), button.getMenuId(),
                    button.getButtonName(), button.getPermCode(), "button"));
        }
        return result;
    }

    /* ==================== 私有装配 ==================== */

    private List<SysMenu> activeMenus() {
        return menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .eq(SysMenu::getStatusFlag, 1)
                .orderByAsc(SysMenu::getSortNo));
    }

    /**
     * 用户可用菜单 ID 集：null 表示超管（不过滤）。
     * 角色事实源 = {@link EffectiveRoleResolver}（RC02：按用户有效角色过滤，
     * 不再使用全公司角色并集）。
     */
    private Set<String> grantedMenuIds(String userId, String companyId) {
        EffectiveRoles effective = effectiveRoleResolver.resolve(userId, companyId);
        if (effective.superAdmin()) {
            return null;
        }
        if (effective.isEmpty()) {
            return Set.of();
        }
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .in(SysRoleMenu::getRoleId, effective.roleIds()))
                .stream().map(SysRoleMenu::getMenuId).collect(Collectors.toSet());
    }

    private List<MenuTreeNode> buildTree(List<SysMenu> menus, String parentId) {
        return menus.stream()
                .filter(menu -> isChildOf(menu, parentId))
                .map(menu -> {
                    List<MenuTreeNode> children = buildTree(menus, menu.getId());
                    return new MenuTreeNode(menu.getId(), menu.getParentId(),
                            menu.getMenuType(), menu.getMenuName(), menu.getPath(),
                            menu.getComponent(), menu.getRedirect(), menu.getPermCode(),
                            menu.getIcon(), menu.getSortNo(),
                            menu.getVisibleFlag() != null && menu.getVisibleFlag() == 1,
                            menu.getKeepAliveFlag() != null && menu.getKeepAliveFlag() == 1,
                            children.isEmpty() ? null : children);
                })
                .toList();
    }

    private List<RemoteMenuRouteDTO> toRoutes(List<SysMenu> menus, String parentId) {
        return menus.stream()
                .filter(menu -> isChildOf(menu, parentId))
                .filter(menu -> !"button".equals(menu.getMenuType()))
                .map(menu -> {
                    List<RemoteMenuRouteDTO> children = toRoutes(menus, menu.getId());
                    return RemoteMenuRouteDTO.builder()
                            .path(menu.getPath())
                            .name(routeName(menu))
                            .component(menu.getComponent())
                            .redirect(menu.getRedirect())
                            .meta(RemoteMenuRouteDTO.RouteMetaDTO.builder()
                                    .title(menu.getMenuName())
                                    .requiresAuth(true)
                                    .icon(menu.getIcon())
                                    .sortOrder(menu.getSortNo())
                                    .keepAlive(menu.getKeepAliveFlag() != null
                                            && menu.getKeepAliveFlag() == 1)
                                    .hideMenu(menu.getVisibleFlag() != null
                                            && menu.getVisibleFlag() == 0)
                                    .build())
                            .children(children.isEmpty() ? null : children)
                            .build();
                })
                .toList();
    }

    private static boolean isChildOf(SysMenu menu, String parentId) {
        return parentId == null
                ? menu.getParentId() == null || menu.getParentId().isBlank()
                : parentId.equals(menu.getParentId());
    }

    /** 路由 name：path 去斜杠驼峰化。 */
    private String routeName(SysMenu menu) {
        if (menu.getPath() == null || menu.getPath().isBlank()) {
            return menu.getId();
        }
        StringBuilder name = new StringBuilder();
        for (String segment : menu.getPath().replaceAll("^/+", "").split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            name.append(Character.toUpperCase(segment.charAt(0)));
            if (segment.length() > 1) {
                name.append(segment, 1, segment.length());
            }
        }
        return name.length() == 0 ? menu.getId() : name.toString();
    }

    /** 递归把按钮节点挂到菜单树叶子上（权限树）。 */
    private List<PermissionNode> appendButtons(List<MenuTreeNode> tree,
                                               Map<String, List<MenuButtonVO>> buttonsByMenu) {
        List<PermissionNode> result = new ArrayList<>(tree.size());
        for (MenuTreeNode node : tree) {
            List<PermissionNode> children = node.children() == null
                    ? new ArrayList<>() : appendButtons(node.children(), buttonsByMenu);
            for (MenuButtonVO button : buttonsByMenu.getOrDefault(node.id(), List.of())) {
                children.add(new PermissionNode(button.id(), node.id(), "button",
                        button.name(), button.permCode(), button.menuId(), null));
            }
            result.add(new PermissionNode(node.id(), node.parentId(), node.type(),
                    node.name(), node.permCode(), null,
                    children.isEmpty() ? null : children));
        }
        return result;
    }
}
