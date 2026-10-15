package com.robot.system.menu.service;

import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 已验证目录授权的导航祖先与按路径权限投影；不查询或变更授权事实。 */
final class MenuAccessProjection {
    private static final int MAX_NAVIGATION_DEPTH = 64;

    private MenuAccessProjection() {}

    record Access(
            boolean superAdmin,
            Set<String> explicitMenus,
            List<SysMenu> menus,
            List<SysMenuButton> buttons,
            Map<String, SysMenu> allMenus) {}

    static Map<String, List<String>> buttonList(Access access) {
        Map<String, List<String>> byMenu = buttonCodesByMenu(access.buttons());
        Map<String, List<String>> result = new HashMap<>();
        for (SysMenu menu : access.menus()) {
            List<String> codes =
                    permissionCodesOfMenu(
                            menu, access, byMenu.getOrDefault(menu.getId(), List.of()));
            putPermissionsForPath(result, menu, access.allMenus(), codes);
        }
        return result;
    }

    /** 这里只接收已经通过有效角色精确绑定和有效父页过滤的按钮。 */
    private static Map<String, List<String>> buttonCodesByMenu(List<SysMenuButton> buttons) {
        return buttons.stream()
                .filter(button -> hasText(button.getPermCode()))
                .collect(
                        Collectors.groupingBy(
                                SysMenuButton::getMenuId,
                                Collectors.mapping(
                                        SysMenuButton::getPermCode, Collectors.toList())));
    }

    /** 导航祖先没有页面码；页面权限仅来自显式菜单授权或既有超管规则。 */
    private static List<String> permissionCodesOfMenu(
            SysMenu menu, Access access, List<String> buttonCodes) {
        List<String> codes = new ArrayList<>(buttonCodes);
        if (hasPagePermission(menu, access)) {
            codes.add(menu.getPermCode());
        }
        return codes.stream().distinct().sorted().toList();
    }

    private static boolean hasPagePermission(SysMenu menu, Access access) {
        return (access.superAdmin() || access.explicitMenus().contains(menu.getId()))
                && hasText(menu.getPermCode());
    }

    private static void putPermissionsForPath(
            Map<String, List<String>> result,
            SysMenu menu,
            Map<String, SysMenu> activeMenus,
            List<String> codes) {
        if (!hasText(menu.getPath()) || codes.isEmpty()) {
            return;
        }
        String path = absolutePath(menu, activeMenus);
        if (path != null) {
            result.put(path, codes);
        }
    }

    static Set<String> navigationMenuIds(
            Set<String> explicitMenus,
            List<SysMenuButton> buttons,
            Map<String, SysMenu> activeMenus) {
        return navigationMenuIds(navigationSeeds(explicitMenus, buttons), activeMenus);
    }

    private static Set<String> navigationSeeds(
            Set<String> explicitMenus, List<SysMenuButton> buttons) {
        Set<String> seeds = new HashSet<>(explicitMenus);
        buttons.forEach(button -> seeds.add(button.getMenuId()));
        return seeds;
    }

    /** 精确授权页和按钮所属页提供导航种子，只有完整有效的祖先链才能进入路由树。 */
    private static Set<String> navigationMenuIds(
            Set<String> seeds, Map<String, SysMenu> activeMenus) {
        Set<String> included = new HashSet<>();
        for (String id : seeds) {
            included.addAll(completeAncestorChain(id, activeMenus));
        }
        return included;
    }

    /** 缺失、循环或超过64层的祖先链全部丢弃，避免残缺导航旁路授权过滤。 */
    private static Set<String> completeAncestorChain(String id, Map<String, SysMenu> activeMenus) {
        Set<String> chain = new HashSet<>();
        String parent = id;
        while (hasText(parent)
                && chain.size() < MAX_NAVIGATION_DEPTH
                && activeMenus.containsKey(parent)
                && chain.add(parent)) {
            parent = activeMenus.get(parent).getParentId();
        }
        return hasText(parent) ? Set.of() : chain;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String absolutePath(SysMenu menu, Map<String, SysMenu> menus) {
        List<String> segments = new ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        SysMenu current = menu;
        while (current != null
                && visited.size() < MAX_NAVIGATION_DEPTH
                && visited.add(current.getId())) {
            String path = current.getPath();
            if (path != null && !path.isBlank()) {
                segments.add(0, path);
                if (path.startsWith("/")) {
                    return String.join("/", segments).replaceAll("/{2,}", "/");
                }
            }
            if (current.getParentId() == null || current.getParentId().isBlank()) {
                return "/" + String.join("/", segments).replaceAll("/{2,}", "/");
            }
            current = menus.get(current.getParentId());
        }
        return null;
    }
}
