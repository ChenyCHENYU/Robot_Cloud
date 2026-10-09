package com.robot.system.menu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.dto.menu.MenuButtonSaveRequest;
import com.robot.system.api.dto.menu.MenuMoveRequest;
import com.robot.system.api.dto.menu.MenuSaveRequest;
import com.robot.system.api.entity.*;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 菜单/按钮写侧：先持有全局树事务锁，再检查拓扑、版本与授权资源关联。 */
@Service
@RequiredArgsConstructor
public class MenuService extends ServiceImpl<SysMenuMapper, SysMenu> {
    private static final int MAX_DEPTH = 64;
    private final SysMenuButtonMapper menuButtonMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleButtonMapper roleButtonMapper;
    private final PermissionChangeRecorder changeRecorder;
    private final PermissionStateStore stateStore;

    /** 锁定全局菜单树后检查父级、路径与深度，新增及授权审计同事务提交。 */
    @Transactional(rollbackFor = Exception.class)
    public String createMenu(MenuSaveRequest request) {
        stateStore.lockMenuTree();
        Map<String, SysMenu> tree = tree();
        validateMenu(request);
        String parent = parentId(request.getParentId());
        assertPlacement(null, parent, tree);
        assertUniquePath(null, parent, request.getPath(), tree);
        SysMenu menu = new SysMenu();
        apply(menu, request);
        menu.setParentId(parent);
        menu.setDeleteFlag(RobotConstants.DELETE_FLAG_ACTIVE);
        menu.setRevision(0);
        ServiceAssert.isTrue(save(menu), ResultCode.FAILED);
        changeRecorder.record("save_menu", menu.getId(), Map.of("name", menu.getMenuName()));
        return menu.getId();
    }

    /** 校验客户端版本与有效拓扑，禁止编辑时改变资源类型。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateMenu(String id, MenuSaveRequest request) {
        stateStore.lockMenuTree();
        Map<String, SysMenu> tree = tree();
        SysMenu menu = node(id, tree);
        assertRevision(request.getRevision(), menu.getRevision());
        validateMenu(request);
        ServiceAssert.isTrue(Objects.equals(menu.getMenuType(), request.getType()),
                ResultCode.PARAM_INVALID, "编辑时不能改变菜单类型");
        String parent = parentId(request.getParentId());
        assertPlacement(id, parent, tree);
        assertUniquePath(id, parent, request.getPath(), tree);
        apply(menu, request);
        menu.setParentId(parent);
        menu.setRevision(request.getRevision());
        ServiceAssert.isTrue(updateById(menu), ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record("update_menu", id, null);
    }

    /** 仍有子菜单或活跃按钮时拒绝删除，软删与授权关联清理同事务提交。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMenu(String id) {
        stateStore.lockMenuTree();
        Map<String, SysMenu> tree = tree();
        node(id, tree);
        ServiceAssert.isTrue(tree.values().stream().noneMatch(menu -> id.equals(menu.getParentId())),
                ResultCode.MENU_HAS_CHILDREN);
        ServiceAssert.isTrue(menuButtonMapper.selectCount(new LambdaQueryWrapper<SysMenuButton>()
                        .eq(SysMenuButton::getMenuId, id)
                        .eq(SysMenuButton::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)) == 0,
                ResultCode.DATA_REFERENCED, "请先删除页面下的按钮权限");
        ServiceAssert.isTrue(removeById(id), ResultCode.DATA_CONCURRENT_CONFLICT);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getMenuId, id));
        changeRecorder.record("delete_menu", id, null);
    }

    /** 在同一树锁内校验环和路径冲突并重排两侧兄弟，避免并发形成环。 */
    @Transactional(rollbackFor = Exception.class)
    public void moveMenu(String id, MenuMoveRequest request) {
        stateStore.lockMenuTree();
        ServiceAssert.isTrue(Set.of("inside", "inner", "before", "after").contains(
                Objects.toString(request.getPosition(), "")), ResultCode.PARAM_INVALID, "拖拽位置不合法");
        Map<String, SysMenu> tree = tree();
        SysMenu source = node(id, tree), target = node(request.getTargetId(), tree);
        ServiceAssert.isTrue(!id.equals(target.getId()), ResultCode.PARAM_INVALID, "不能移动到自身");
        if (request.getRevision() != null) {
            assertRevision(request.getRevision(), source.getRevision());
        }
        boolean inside = "inside".equals(request.getPosition()) || "inner".equals(request.getPosition());
        String oldParent = parentId(source.getParentId());
        String newParent = inside ? target.getId() : parentId(target.getParentId());
        assertPlacement(id, newParent, tree);
        ServiceAssert.isTrue(!descendants(id, tree).contains(target.getId()),
                ResultCode.PARAM_INVALID, "禁止移动到自身子级下");
        assertUniquePath(id, newParent, source.getPath(), tree);
        List<SysMenu> destination = siblings(newParent, id, tree);
        int index = inside ? destination.size() : indexOf(destination, target.getId())
                + ("after".equals(request.getPosition()) ? 1 : 0);
        destination.add(index, source);
        source.setParentId(newParent);
        if (!Objects.equals(oldParent, newParent)) {
            reorder(siblings(oldParent, id, tree), null);
        }
        reorder(destination, id);
        changeRecorder.record("move_menu", id,
                Map.of("targetId", target.getId(), "position", request.getPosition()));
    }

    /** 新增独立按钮资源，要求所属页面有效；不隐式授予任何角色。 */
    @Transactional(rollbackFor = Exception.class)
    public String createButton(MenuButtonSaveRequest request) {
        return createButton(request.getMenuId(), request);
    }

    /** 新增独立按钮资源，要求所属页面有效；不隐式授予任何角色。 */
    @Transactional(rollbackFor = Exception.class)
    public String createButton(String menuId, MenuButtonSaveRequest request) {
        stateStore.lockMenuTree();
        ServiceAssert.notBlank(menuId, ResultCode.PARAM_INVALID, "按钮必须属于菜单页面");
        ServiceAssert.isTrue(request.getMenuId() == null || menuId.equals(request.getMenuId()),
                ResultCode.PARAM_INVALID, "按钮所属页面与路径不一致");
        requireButtonPage(menuId, tree());
        validateButton(null, request);
        SysMenuButton button = new SysMenuButton();
        button.setMenuId(menuId);
        apply(button, request);
        button.setDeleteFlag(RobotConstants.DELETE_FLAG_ACTIVE);
        button.setRevision(0);
        ServiceAssert.isTrue(menuButtonMapper.insert(button) == 1, ResultCode.FAILED);
        changeRecorder.record("save_button", button.getId(), Map.of("permCode", button.getPermCode()));
        return button.getId();
    }

    /** 按客户端版本编辑按钮，保留所属页面并校验精确权限码。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateButton(String id, MenuButtonSaveRequest request) {
        stateStore.lockMenuTree();
        SysMenuButton button = activeButton(id);
        assertRevision(request.getRevision(), button.getRevision());
        ServiceAssert.isTrue(request.getMenuId() == null || button.getMenuId().equals(request.getMenuId()),
                ResultCode.PARAM_INVALID, "编辑按钮不能改变所属页面");
        requireButtonPage(button.getMenuId(), tree());
        validateButton(id, request);
        apply(button, request);
        button.setRevision(request.getRevision());
        ServiceAssert.isTrue(menuButtonMapper.updateById(button) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record("update_button", id, null);
    }

    /** 软删按钮及其角色绑定，审计和权限版本同事务推进。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteButton(String id) {
        stateStore.lockMenuTree();
        activeButton(id);
        ServiceAssert.isTrue(menuButtonMapper.deleteById(id) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        roleButtonMapper.delete(new LambdaQueryWrapper<SysRoleButton>().eq(SysRoleButton::getButtonId, id));
        changeRecorder.record("delete_button", id, null);
    }

    /** 仅允许启用的页面菜单作为按钮所属资源。 */
    public void requireButtonPage(String id) {
        requireButtonPage(id, tree());
    }

    private void requireButtonPage(String id, Map<String, SysMenu> tree) {
        SysMenu page = node(id, tree);
        ServiceAssert.isTrue("menu".equals(page.getMenuType()) && Integer.valueOf(1).equals(page.getStatusFlag()),
                ResultCode.PARAM_INVALID, "按钮必须属于有效菜单页面");
    }

    private SysMenuButton activeButton(String id) {
        SysMenuButton button = ServiceAssert.notNull(menuButtonMapper.selectById(id), ResultCode.MENU_NOT_FOUND);
        ServiceAssert.isTrue(Integer.valueOf(RobotConstants.DELETE_FLAG_ACTIVE).equals(button.getDeleteFlag()),
                ResultCode.MENU_NOT_FOUND);
        return button;
    }

    private Map<String, SysMenu> tree() {
        return list(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream().collect(Collectors.toMap(SysMenu::getId, Function.identity()));
    }

    private SysMenu node(String id, Map<String, SysMenu> tree) {
        return ServiceAssert.notNull(tree.get(id), ResultCode.MENU_NOT_FOUND);
    }

    private void assertPlacement(String sourceId, String parent, Map<String, SysMenu> tree) {
        Set<String> visited = new HashSet<>();
        int parentDepth = 0;
        for (String walk = parent; walk != null; ) {
            ServiceAssert.isTrue(!Objects.equals(sourceId, walk) && visited.add(walk),
                    ResultCode.PARAM_INVALID, "菜单不能形成循环");
            SysMenu ancestor = node(walk, tree);
            ServiceAssert.isTrue("directory".equals(ancestor.getMenuType())
                            && Integer.valueOf(1).equals(ancestor.getStatusFlag()),
                    ResultCode.PARAM_INVALID, "上级只能选择有效目录");
            ServiceAssert.isTrue(++parentDepth < MAX_DEPTH, ResultCode.PARAM_INVALID, "菜单层级超过上限");
            walk = parentId(ancestor.getParentId());
        }
        ServiceAssert.isTrue(parentDepth + (sourceId == null ? 1 : subtreeDepth(sourceId, tree)) <= MAX_DEPTH,
                ResultCode.PARAM_INVALID, "菜单层级超过上限");
    }

    private Map<String, List<String>> children(Map<String, SysMenu> tree) {
        Map<String, List<String>> children = new HashMap<>();
        for (SysMenu menu : tree.values()) {
            if (parentId(menu.getParentId()) != null)
                {
                    children.computeIfAbsent(menu.getParentId(), key -> new ArrayList<>()).add(menu.getId());
                }
        }
        return children;
    }

    private int subtreeDepth(String id, Map<String, SysMenu> tree) {
        Map<String, List<String>> children = children(tree);
        Set<String> visited = new HashSet<>();
        List<String> level = List.of(id);
        int depth = 0;
        while (!level.isEmpty()) {
            ServiceAssert.isTrue(++depth <= MAX_DEPTH, ResultCode.PARAM_INVALID, "菜单层级超过上限");
            List<String> next = new ArrayList<>();
            for (String current : level) {
                ServiceAssert.isTrue(visited.add(current), ResultCode.PARAM_INVALID, "菜单树存在循环");
                next.addAll(children.getOrDefault(current, List.of()));
            }
            level = next;
        }
        return depth;
    }

    private Set<String> descendants(String id, Map<String, SysMenu> tree) {
        Map<String, List<String>> children = children(tree);
        Set<String> found = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(); queue.add(id);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            ServiceAssert.isTrue(found.add(current), ResultCode.PARAM_INVALID, "菜单树存在循环");
            queue.addAll(children.getOrDefault(current, List.of()));
        }
        return found;
    }

    private static List<SysMenu> siblings(String parent, String excluded, Map<String, SysMenu> tree) {
        return tree.values().stream().filter(menu -> !menu.getId().equals(excluded)
                        && Objects.equals(parent, parentId(menu.getParentId())))
                .sorted(Comparator.comparing(SysMenu::getSortNo, Comparator.nullsFirst(Integer::compareTo))
                        .thenComparing(SysMenu::getId)).collect(Collectors.toCollection(ArrayList::new));
    }

    private static int indexOf(List<SysMenu> menus, String targetId) {
        for (int i = 0; i < menus.size(); i++) {
            if (targetId.equals(menus.get(i).getId())) {
    return i;
}
        }
        throw new ServiceException(ResultCode.PARAM_INVALID, "目标菜单不在预期目录下");
    }

    private void reorder(List<SysMenu> siblings, String sourceId) {
        for (int index = 0; index < siblings.size(); index++) {
            SysMenu menu = siblings.get(index);
            int sort = index + 1;
            if (menu.getId().equals(sourceId) || !Objects.equals(menu.getSortNo(), sort)) {
                menu.setSortNo(sort);
                ServiceAssert.isTrue(updateById(menu), ResultCode.DATA_CONCURRENT_CONFLICT);
            }
        }
    }

    private void assertUniquePath(String id, String parent, String path, Map<String, SysMenu> tree) {
        ServiceAssert.isTrue(tree.values().stream().noneMatch(menu -> !Objects.equals(id, menu.getId())
                        && Objects.equals(parent, parentId(menu.getParentId())) && Objects.equals(path, menu.getPath())),
                ResultCode.DATA_DUPLICATED, "同一目录下路由路径不能重复");
    }

    private static void assertRevision(Integer expected, Integer actual) {
        ServiceAssert.notNull(expected, ResultCode.PARAM_INVALID, "更新必须提交 revision");
        ServiceAssert.isTrue(expected >= 0 && Objects.equals(expected, actual), ResultCode.DATA_CONCURRENT_CONFLICT);
    }

    private static void validateMenu(MenuSaveRequest request) {
        ServiceAssert.notBlank(request.getName(), ResultCode.PARAM_INVALID);
        ServiceAssert.isTrue(Set.of("directory", "menu").contains(Objects.toString(request.getType(), ""))
                && request.isFlagsConsistent(), ResultCode.PARAM_INVALID);
        String path = request.getPath();
        ServiceAssert.isTrue(path != null && !path.isBlank() && !path.matches(".*[\\s?#].*")
                        && !path.toLowerCase(Locale.ROOT).matches("^(javascript|data):.*"),
                ResultCode.PARAM_INVALID, "菜单路由路径不合法");
        if ("menu".equals(request.getType())) {
            ServiceAssert.isTrue(request.getComponent() != null
                    && request.getComponent().matches("/?[\\w./-]+") && !request.getComponent().contains(".."),
                    ResultCode.PARAM_INVALID, "页面组件路径不合法");
        }
        if (request.getPermission() != null && !request.getPermission().isBlank()) {
            exactPermission(request.getPermission());
        }
    }

    private void validateButton(String id, MenuButtonSaveRequest request) {
        ServiceAssert.notBlank(request.getName(), ResultCode.PARAM_INVALID);
        exactPermission(request.getPermission());
        ServiceAssert.isTrue(menuButtonMapper.selectCount(new LambdaQueryWrapper<SysMenuButton>()
                        .eq(SysMenuButton::getPermCode, request.getPermission())
                        .eq(SysMenuButton::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                        .ne(id != null, SysMenuButton::getId, id)) == 0,
                ResultCode.DATA_DUPLICATED, "按钮权限标识已存在");
    }

    private static void exactPermission(String code) {
        ServiceAssert.isTrue(code != null && code.matches("[\\w.-]+(?::[\\w.-]+)+"),
                ResultCode.PARAM_INVALID, "权限标识必须是精确资源动作");
    }

    private static String parentId(String value) { return value == null || value.isBlank() ? null : value; }

    private static void apply(SysMenu menu, MenuSaveRequest request) {
        menu.setMenuName(request.getName()); menu.setMenuType(request.getType()); menu.setPath(request.getPath());
        menu.setComponent(request.getComponent()); menu.setRedirect(request.getRedirect());
        menu.setPermCode(request.getPermission()); menu.setIcon(request.getIcon());
        menu.setSortNo(request.getSort() == null ? 0 : request.getSort());
        menu.setVisibleFlag(request.effectiveVisibleFlag()); menu.setKeepAliveFlag(request.effectiveKeepAliveFlag());
        menu.setStatusFlag(request.getStatus() == null ? 1 : request.getStatus()); menu.setRemark(request.getRemark());
    }

    private static void apply(SysMenuButton button, MenuButtonSaveRequest request) {
        button.setButtonName(request.getName()); button.setPermCode(request.getPermission());
        button.setSortNo(request.getSort() == null ? 0 : request.getSort());
        button.setStatusFlag(request.getStatus() == null ? 1 : request.getStatus()); button.setRemark(request.getRemark());
    }
}
