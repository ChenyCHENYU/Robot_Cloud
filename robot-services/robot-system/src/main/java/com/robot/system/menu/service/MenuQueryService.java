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
import java.util.HashSet;
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
        return buildTree(menus(false), null);
    }

    /** 必要祖先仅用于导航结构，不附赠页面或动作权限。 */
    public List<RemoteMenuRouteDTO> routesOfUser(String userId, String companyId) {
        return toRoutes(accessOfUser(userId, companyId).menus(), null);
    }

    /** 与API精确绑定一致：隐式父菜单不提供page权限，按钮只来自有效role_button。 */
    public Map<String, List<String>> buttonListOfUser(String userId, String companyId) {
        MenuAccess access = accessOfUser(userId, companyId);
        Map<String, List<String>> byMenu = buttonCodesByMenu(access.buttons());
        Map<String, List<String>> result = new HashMap<>();
        for (SysMenu menu : access.menus()) {
            List<String> codes = permissionCodesOfMenu(menu, access, byMenu.getOrDefault(menu.getId(), List.of()));
            putPermissionsForPath(result, menu, access.allMenus(), codes);
        }
        return result;
    }

    /** 这里只接收已经通过有效角色精确绑定和有效父页过滤的按钮。 */
    private Map<String, List<String>> buttonCodesByMenu(List<SysMenuButton> buttons) {
        return buttons.stream().filter(button -> hasText(button.getPermCode()))
                .collect(Collectors.groupingBy(SysMenuButton::getMenuId,
                        Collectors.mapping(SysMenuButton::getPermCode, Collectors.toList())));
    }

    /** 导航祖先没有页面码；页面权限仅来自显式菜单授权或既有超管规则。 */
    private List<String> permissionCodesOfMenu(SysMenu menu, MenuAccess access, List<String> buttonCodes) {
        List<String> codes = new ArrayList<>(buttonCodes);
        if (hasPagePermission(menu, access)) {
            codes.add(menu.getPermCode());
        }
        return codes.stream().distinct().sorted().toList();
    }

    private boolean hasPagePermission(SysMenu menu, MenuAccess access) {
        return (access.superAdmin() || access.explicitMenus().contains(menu.getId())) && hasText(menu.getPermCode());
    }

    private void putPermissionsForPath(Map<String, List<String>> result, SysMenu menu,
            Map<String, SysMenu> activeMenus, List<String> codes) {
        if (!hasText(menu.getPath()) || codes.isEmpty()) {
            return;
        }
        String path = absolutePath(menu, activeMenus);
        if (path != null) {
            result.put(path, codes);
        }
    }

    private record MenuAccess(boolean superAdmin, Set<String> explicitMenus,
            List<SysMenu> menus, List<SysMenuButton> buttons, Map<String, SysMenu> allMenus) { }

    private MenuAccess accessOfUser(String userId, String companyId) {
        EffectiveRoles effective = effectiveRoleResolver.resolve(userId, companyId);
        if (effective.isEmpty()) {
            return new MenuAccess(false, Set.of(), List.of(), List.of(), Map.of());
        }
        List<SysMenu> active = menus(true);
        Map<String, SysMenu> byId = active.stream().collect(Collectors.toMap(SysMenu::getId, java.util.function.Function.identity()));
        Set<String> explicit = effective.superAdmin() ? byId.keySet() : menuIdsOfRoles(effective.roleIds());
        List<SysMenuButton> buttons = buttonsOnActiveMenus(effective, byId);
        Set<String> included = navigationMenuIds(navigationSeeds(explicit, buttons), byId);
        return new MenuAccess(effective.superAdmin(), explicit,
                active.stream().filter(menu -> included.contains(menu.getId())).toList(), buttons, byId);
    }

    private List<SysMenuButton> buttonsOnActiveMenus(EffectiveRoles effective, Map<String, SysMenu> activeMenus) {
        return boundButtons(effective).stream().filter(button -> activeMenus.containsKey(button.getMenuId())).toList();
    }

    private Set<String> navigationSeeds(Set<String> explicitMenus, List<SysMenuButton> buttons) {
        Set<String> seeds = new HashSet<>(explicitMenus);
        buttons.forEach(button -> seeds.add(button.getMenuId()));
        return seeds;
    }

    /** 精确授权页和按钮所属页提供导航种子，只有完整有效的祖先链才能进入路由树。 */
    private Set<String> navigationMenuIds(Set<String> seeds, Map<String, SysMenu> activeMenus) {
        Set<String> included = new HashSet<>();
        for (String id : seeds) {
            included.addAll(completeAncestorChain(id, activeMenus));
        }
        return included;
    }

    /** 缺失、循环或超过64层的祖先链全部丢弃，避免残缺导航旁路授权过滤。 */
    private Set<String> completeAncestorChain(String id, Map<String, SysMenu> activeMenus) {
        Set<String> chain = new HashSet<>();
        String parent = id;
        while (hasText(parent) && chain.size() < 64 && activeMenus.containsKey(parent) && chain.add(parent)) {
            parent = activeMenus.get(parent).getParentId();
        }
        return hasText(parent) ? Set.of() : chain;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String absolutePath(SysMenu menu, Map<String, SysMenu> menus) {
        List<String> segments = new ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        SysMenu current = menu;
        while (current != null && visited.size() < 64 && visited.add(current.getId())) {
            String path = current.getPath();
            if (path != null && !path.isBlank()) {
                segments.add(0, path);
                if (path.startsWith("/")) {
                    return String.join("/", segments).replaceAll("/{2,}", "/");
                }
            }
            if (current.getParentId() == null || current.getParentId().isBlank())
                {
                    return "/" + String.join("/", segments).replaceAll("/{2,}", "/");
                }
            current = menus.get(current.getParentId());
        }
        return null;
    }

    private Set<String> menuIdsOfRoles(Set<String> roleIds) {
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .in(SysRoleMenu::getRoleId, roleIds)
                        .eq(SysRoleMenu::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream().filter(binding -> roleIds.contains(binding.getRoleId())
                        && Integer.valueOf(1).equals(binding.getDeleteFlag()))
                .map(SysRoleMenu::getMenuId).collect(Collectors.toSet());
    }

    /** 精确绑定的按钮（超管为全部）。 */
    private List<SysMenuButton> boundButtons(EffectiveRoleResolver.EffectiveRoles effective) {
        if (effective.superAdmin()) {
            return menuButtonMapper.selectList(new LambdaQueryWrapper<SysMenuButton>()
                    .eq(SysMenuButton::getStatusFlag, 1)
                    .eq(SysMenuButton::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)).stream()
                    .filter(button -> Integer.valueOf(1).equals(button.getStatusFlag())
                            && Integer.valueOf(1).equals(button.getDeleteFlag())).toList();
        }
        if (effective.isEmpty()) {
            return List.of();
        }
        List<String> buttonIds = roleButtonMapper.selectList(
                        new LambdaQueryWrapper<SysRoleButton>()
                                .in(SysRoleButton::getRoleId, effective.roleIds())
                                .eq(SysRoleButton::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream().filter(binding -> effective.roleIds().contains(binding.getRoleId())
                        && Integer.valueOf(1).equals(binding.getDeleteFlag()))
                .map(SysRoleButton::getButtonId).toList();
        if (buttonIds.isEmpty()) {
            return List.of();
        }
        return menuButtonMapper.selectBatchIds(buttonIds).stream()
                .filter(button -> buttonIds.contains(button.getId()) && button.getStatusFlag() != null && button.getStatusFlag() == 1
                        && button.getDeleteFlag() != null
                        && button.getDeleteFlag() == RobotConstants.DELETE_FLAG_ACTIVE)
                .toList();
    }

    /** 按钮清单（menuId 空 = 全量；管理端与装配复用）。 */
    public List<MenuButtonVO> buttonsOfMenu(String menuId) {
        return menuButtonMapper.selectList(new LambdaQueryWrapper<SysMenuButton>()
                        .eq(menuId != null && !menuId.isBlank(), SysMenuButton::getMenuId, menuId)
                        .eq(SysMenuButton::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                        .orderByAsc(SysMenuButton::getSortNo, SysMenuButton::getId))
                .stream()
                .map(button -> new MenuButtonVO(button.getId(), button.getMenuId(),
                        button.getButtonName(), button.getPermCode(),
                        button.getSortNo(), button.getStatusFlag(), button.getPermCode(),
                        button.getRemark(), button.getRevision()))
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
        for (SysMenu menu : menus(false)) {
            if (menu.getPermCode() != null && !menu.getPermCode().isBlank()) {
                result.add(new PermissionItem(menu.getId(), menu.getId(),
                        menu.getMenuName(), menu.getPermCode(), "menu"));
            }
        }
        for (SysMenuButton button : menuButtonMapper.selectList(
                new LambdaQueryWrapper<SysMenuButton>()
                        .eq(SysMenuButton::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))) {
            result.add(new PermissionItem(button.getId(), button.getMenuId(),
                    button.getButtonName(), button.getPermCode(), "button"));
        }
        return result;
    }

    /* ==================== 私有装配 ==================== */

    private List<SysMenu> menus(boolean activeOnly) {
        return menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                        .eq(SysMenu::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                        .eq(activeOnly, SysMenu::getStatusFlag, 1)
                        .orderByAsc(SysMenu::getSortNo, SysMenu::getId)).stream()
                .filter(menu -> Integer.valueOf(1).equals(menu.getDeleteFlag())
                        && (!activeOnly || Integer.valueOf(1).equals(menu.getStatusFlag())))
                .toList();
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
                            children.isEmpty() ? null : children, menu.getPermCode(), menu.getStatusFlag(),
                            menu.getVisibleFlag() != null && menu.getVisibleFlag() == 0 ? 1 : 0,
                            menu.getRemark(), menu.getRevision());
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
