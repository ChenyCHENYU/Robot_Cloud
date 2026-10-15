package com.robot.system.menu.service;

import com.robot.api.system.dto.RemoteMenuRouteDTO;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.vo.menu.MenuButtonVO;
import com.robot.system.api.vo.menu.MenuTreeNode;
import com.robot.system.api.vo.permission.PermissionNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 已授权菜单快照的树/路由/权限投影；不读取数据库或扩大资源权限。 */
final class MenuTreeProjection {
    private static final int MAX_TREE_DEPTH = 64;

    private MenuTreeProjection() {}

    static List<MenuTreeNode> buildTree(List<SysMenu> menus, String parentId) {
        Map<String, List<SysMenu>> byParent =
                menus.stream()
                        .collect(
                                Collectors.groupingBy(
                                        menu ->
                                                menu.getParentId() == null
                                                                || menu.getParentId().isBlank()
                                                        ? ""
                                                        : menu.getParentId()));
        return buildTree(byParent, parentId, 0, new java.util.HashSet<>());
    }

    static List<MenuTreeNode> buildTree(
            Map<String, List<SysMenu>> byParent, String parentId, int depth, Set<String> visiting) {
        com.robot.common.core.exception.ServiceAssert.isTrue(
                depth <= MAX_TREE_DEPTH,
                com.robot.common.core.domain.ResultCode.PARAM_INVALID,
                "菜单树超过允许深度");
        return byParent.getOrDefault(parentId == null ? "" : parentId, List.of()).stream()
                .map(
                        menu -> {
                            com.robot.common.core.exception.ServiceAssert.isTrue(
                                    visiting.add(menu.getId()),
                                    com.robot.common.core.domain.ResultCode.PARAM_INVALID,
                                    "菜单树父链异常");
                            List<MenuTreeNode> children =
                                    buildTree(byParent, menu.getId(), depth + 1, visiting);
                            visiting.remove(menu.getId());
                            return new MenuTreeNode(
                                    menu.getId(),
                                    menu.getParentId(),
                                    menu.getMenuType(),
                                    menu.getMenuName(),
                                    menu.getPath(),
                                    menu.getComponent(),
                                    menu.getRedirect(),
                                    menu.getPermCode(),
                                    menu.getIcon(),
                                    menu.getSortNo(),
                                    menu.getVisibleFlag() != null && menu.getVisibleFlag() == 1,
                                    menu.getKeepAliveFlag() != null && menu.getKeepAliveFlag() == 1,
                                    children.isEmpty() ? null : children,
                                    menu.getPermCode(),
                                    menu.getStatusFlag(),
                                    menu.getVisibleFlag() != null && menu.getVisibleFlag() == 0
                                            ? 1
                                            : 0,
                                    menu.getRemark(),
                                    menu.getRevision());
                        })
                .toList();
    }

    static List<RemoteMenuRouteDTO> toRoutes(List<SysMenu> menus, String parentId) {
        Map<String, List<SysMenu>> byParent =
                menus.stream()
                        .collect(
                                Collectors.groupingBy(
                                        menu ->
                                                menu.getParentId() == null
                                                                || menu.getParentId().isBlank()
                                                        ? ""
                                                        : menu.getParentId()));
        return toRoutes(byParent, parentId, 0, new java.util.HashSet<>());
    }

    static List<RemoteMenuRouteDTO> toRoutes(
            Map<String, List<SysMenu>> byParent, String parentId, int depth, Set<String> visiting) {
        com.robot.common.core.exception.ServiceAssert.isTrue(
                depth <= MAX_TREE_DEPTH,
                com.robot.common.core.domain.ResultCode.PARAM_INVALID,
                "菜单树超过允许深度");
        return byParent.getOrDefault(parentId == null ? "" : parentId, List.of()).stream()
                .filter(menu -> !"button".equals(menu.getMenuType()))
                .map(
                        menu -> {
                            com.robot.common.core.exception.ServiceAssert.isTrue(
                                    visiting.add(menu.getId()),
                                    com.robot.common.core.domain.ResultCode.PARAM_INVALID,
                                    "菜单树父链异常");
                            List<RemoteMenuRouteDTO> children =
                                    toRoutes(byParent, menu.getId(), depth + 1, visiting);
                            visiting.remove(menu.getId());
                            return RemoteMenuRouteDTO.builder()
                                    .path(menu.getPath())
                                    .name(routeName(menu))
                                    .component(menu.getComponent())
                                    .redirect(menu.getRedirect())
                                    .meta(
                                            RemoteMenuRouteDTO.RouteMetaDTO.builder()
                                                    .title(menu.getMenuName())
                                                    .requiresAuth(true)
                                                    .icon(menu.getIcon())
                                                    .sortOrder(menu.getSortNo())
                                                    .keepAlive(
                                                            menu.getKeepAliveFlag() != null
                                                                    && menu.getKeepAliveFlag() == 1)
                                                    .hideMenu(
                                                            menu.getVisibleFlag() != null
                                                                    && menu.getVisibleFlag() == 0)
                                                    .build())
                                    .children(children.isEmpty() ? null : children)
                                    .build();
                        })
                .toList();
    }

    /** 稳定全局身份，与页面路径、展示名及静态路由命名空间分离。 */
    private static String routeName(SysMenu menu) {
        return "robot-menu:" + menu.getId();
    }

    /** 递归把按钮节点挂到菜单树叶子上（权限树）。 */
    // 每个菜单递归拥有独立可变子集合，后续加入按钮不能改写兄弟节点。
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    static List<PermissionNode> appendButtons(
            List<MenuTreeNode> tree, Map<String, List<MenuButtonVO>> buttonsByMenu) {
        List<PermissionNode> result = new ArrayList<>(tree.size());
        for (MenuTreeNode node : tree) {
            List<PermissionNode> children =
                    node.children() == null
                            ? new ArrayList<>()
                            : appendButtons(node.children(), buttonsByMenu);
            for (MenuButtonVO button : buttonsByMenu.getOrDefault(node.id(), List.of())) {
                children.add(
                        new PermissionNode(
                                button.id(),
                                node.id(),
                                "button",
                                button.name(),
                                button.permCode(),
                                button.menuId(),
                                null));
            }
            result.add(
                    new PermissionNode(
                            node.id(),
                            node.parentId(),
                            node.type(),
                            node.name(),
                            node.permCode(),
                            null,
                            children.isEmpty() ? null : children));
        }
        return result;
    }
}
