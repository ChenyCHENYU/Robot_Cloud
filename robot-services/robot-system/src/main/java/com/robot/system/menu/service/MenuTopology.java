package com.robot.system.menu.service;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.entity.SysMenu;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 活跃目录快照的放置、深度、后代和绝对路径规则，不承载数据库写入。 */
final class MenuTopology {
    private static final int MAX_DEPTH = 64;

    private MenuTopology() {}

    static SysMenu node(String id, Map<String, SysMenu> tree) {
        return ServiceAssert.notNull(tree.get(id), ResultCode.MENU_NOT_FOUND);
    }

    static void assertPlacement(String sourceId, String parent, Map<String, SysMenu> tree) {
        Set<String> visited = new HashSet<>();
        int parentDepth = 0;
        for (String walk = parent; walk != null; ) {
            ServiceAssert.isTrue(
                    !Objects.equals(sourceId, walk) && visited.add(walk),
                    ResultCode.PARAM_INVALID,
                    "菜单不能形成循环");
            SysMenu ancestor = node(walk, tree);
            ServiceAssert.isTrue(
                    "directory".equals(ancestor.getMenuType())
                            && Integer.valueOf(1).equals(ancestor.getStatusFlag()),
                    ResultCode.PARAM_INVALID,
                    "上级只能选择有效目录");
            ServiceAssert.isTrue(++parentDepth < MAX_DEPTH, ResultCode.PARAM_INVALID, "菜单层级超过上限");
            walk = parentId(ancestor.getParentId());
        }
        ServiceAssert.isTrue(
                parentDepth + (sourceId == null ? 1 : subtreeDepth(sourceId, tree)) <= MAX_DEPTH,
                ResultCode.PARAM_INVALID,
                "菜单层级超过上限");
    }

    static Map<String, List<String>> children(Map<String, SysMenu> tree) {
        return tree.values().stream()
                .filter(menu -> parentId(menu.getParentId()) != null)
                .collect(
                        Collectors.groupingBy(
                                SysMenu::getParentId,
                                Collectors.mapping(SysMenu::getId, Collectors.toList())));
    }

    static int subtreeDepth(String id, Map<String, SysMenu> tree) {
        Map<String, List<String>> children = children(tree);
        Set<String> visited = new HashSet<>();
        List<String> level = new ArrayList<>(List.of(id));
        List<String> next = new ArrayList<>();
        int depth = 0;
        while (!level.isEmpty()) {
            ServiceAssert.isTrue(++depth <= MAX_DEPTH, ResultCode.PARAM_INVALID, "菜单层级超过上限");
            next.clear();
            for (String current : level) {
                ServiceAssert.isTrue(visited.add(current), ResultCode.PARAM_INVALID, "菜单树存在循环");
                next.addAll(children.getOrDefault(current, List.of()));
            }
            List<String> previous = level;
            level = next;
            next = previous;
        }
        return depth;
    }

    static Set<String> descendants(String id, Map<String, SysMenu> tree) {
        Map<String, List<String>> children = children(tree);
        Set<String> found = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(id);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            ServiceAssert.isTrue(found.add(current), ResultCode.PARAM_INVALID, "菜单树存在循环");
            queue.addAll(children.getOrDefault(current, List.of()));
        }
        return found;
    }

    static List<SysMenu> siblings(String parent, String excluded, Map<String, SysMenu> tree) {
        return tree.values().stream()
                .filter(
                        menu ->
                                !menu.getId().equals(excluded)
                                        && Objects.equals(parent, parentId(menu.getParentId())))
                .sorted(
                        Comparator.comparing(
                                        SysMenu::getSortNo,
                                        Comparator.nullsFirst(Integer::compareTo))
                                .thenComparing(SysMenu::getId))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    static int indexOf(List<SysMenu> menus, String targetId) {
        for (int i = 0; i < menus.size(); i++) {
            if (targetId.equals(menus.get(i).getId())) {
                return i;
            }
        }
        throw new ServiceException(ResultCode.PARAM_INVALID, "目标菜单不在预期目录下");
    }

    static void assertUniquePath(String id, String parent, String path, Map<String, SysMenu> tree) {
        Map<String, SysMenu> proposed = new HashMap<>(tree);
        SysMenu replacement = new SysMenu();
        replacement.setId(id == null ? "__new-menu__" : id);
        replacement.setParentId(parent);
        replacement.setPath(path);
        proposed.put(replacement.getId(), replacement);
        Set<String> paths = new HashSet<>();
        for (SysMenu menu : proposed.values()) {
            ServiceAssert.isTrue(
                    paths.add(
                            effectivePath(menu, proposed, new HashSet<>())
                                    .toLowerCase(Locale.ROOT)),
                    ResultCode.DATA_DUPLICATED,
                    "有效绝对路由路径不能重复");
        }
    }

    static String effectivePath(SysMenu menu, Map<String, SysMenu> tree, Set<String> visiting) {
        ServiceAssert.isTrue(
                visiting.add(menu.getId()) && visiting.size() <= MAX_DEPTH,
                ResultCode.PARAM_INVALID,
                "菜单路径父链异常");
        String value = Objects.toString(menu.getPath(), "");
        String parent = parentId(menu.getParentId());
        if (!value.startsWith("/") && parent != null) {
            SysMenu owner = ServiceAssert.notNull(tree.get(parent), ResultCode.MENU_NOT_FOUND);
            value = effectivePath(owner, tree, visiting) + "/" + value;
        }
        visiting.remove(menu.getId());
        value = ("/" + value).replaceAll("/{2,}", "/");
        return value.length() > 1 ? value.replaceAll("/+$", "") : value;
    }

    static String parentId(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
