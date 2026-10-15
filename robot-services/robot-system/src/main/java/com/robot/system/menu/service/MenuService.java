package com.robot.system.menu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.menu.MenuButtonSaveRequest;
import com.robot.system.api.dto.menu.MenuMoveRequest;
import com.robot.system.api.dto.menu.MenuSaveRequest;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.GlobalDirectoryPolicy;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 菜单/按钮写侧：先持有全局树事务锁，再检查拓扑、版本与授权资源关联。 */
@Service
@RequiredArgsConstructor
public class MenuService extends ServiceImpl<SysMenuMapper, SysMenu> {
    private final SysMenuButtonMapper menuButtonMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleButtonMapper roleButtonMapper;
    private final PermissionChangeRecorder changeRecorder;
    private final PermissionStateStore stateStore;
    private final GlobalDirectoryPolicy directoryPolicy;

    /**
     * 锁定全局菜单树后检查父级、路径与深度，新增及授权审计同事务提交。
     *
     * @param request 该用例接收的白名单请求
     * @return 当前事实派生的结果
     */
    @Transactional(rollbackFor = Exception.class)
    public String createMenu(MenuSaveRequest request) {
        stateStore.lockMenuTree();
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        directoryPolicy.requirePlatformAdministrator();
        Map<String, SysMenu> tree = tree();
        MenuWriteValues.validateMenu(request);
        String parent = MenuTopology.parentId(request.getParentId());
        MenuTopology.assertPlacement(null, parent, tree);
        MenuTopology.assertUniquePath(null, parent, request.getPath(), tree);
        SysMenu menu = new SysMenu();
        MenuWriteValues.apply(menu, request);
        menu.setParentId(parent);
        menu.setDeleteFlag(RobotConstants.DELETE_FLAG_ACTIVE);
        menu.setRevision(0);
        ServiceAssert.isTrue(save(menu), ResultCode.FAILED);
        changeRecorder.record("save_menu", menu.getId(), Map.of("name", menu.getMenuName()));
        return menu.getId();
    }

    /**
     * 校验客户端版本与有效拓扑，禁止编辑时改变资源类型。
     *
     * @param id 记录ID
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateMenu(String id, MenuSaveRequest request) {
        stateStore.lockMenuTree();
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        directoryPolicy.requirePlatformAdministrator();
        Map<String, SysMenu> tree = tree();
        SysMenu menu = MenuTopology.node(id, tree);
        assertRevision(request.getRevision(), menu.getRevision());
        MenuWriteValues.validateMenu(request);
        ServiceAssert.isTrue(
                Objects.equals(menu.getMenuType(), request.getType()),
                ResultCode.PARAM_INVALID,
                "编辑时不能改变菜单类型");
        String parent = MenuTopology.parentId(request.getParentId());
        MenuTopology.assertPlacement(id, parent, tree);
        MenuTopology.assertUniquePath(id, parent, request.getPath(), tree);
        if (!Objects.equals(menu.getPermCode(), request.getPermission())) {
            ServiceAssert.isTrue(
                    roleMenuMapper.selectCount(
                                    new LambdaQueryWrapper<SysRoleMenu>()
                                            .eq(SysRoleMenu::getMenuId, id)
                                            .eq(SysRoleMenu::getDeleteFlag, 1))
                            == 0,
                    ResultCode.DATA_REFERENCED,
                    "已绑定权限编码须经独立迁移，不允许普通目录更新改写");
        }
        MenuWriteValues.apply(menu, request);
        menu.setParentId(parent);
        menu.setRevision(request.getRevision());
        ServiceAssert.isTrue(updateById(menu), ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record("update_menu", id, null);
    }

    /**
     * 仍有子菜单或活跃按钮时拒绝删除，软删与授权关联清理同事务提交。
     *
     * @param id 记录ID
     * @param expectedRevision 客户端期望的聚合版本
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMenu(String id, Integer expectedRevision) {
        stateStore.lockMenuTree();
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        directoryPolicy.requirePlatformAdministrator();
        Map<String, SysMenu> tree = tree();
        SysMenu menu = MenuTopology.node(id, tree);
        assertRevision(expectedRevision, menu.getRevision());
        ServiceAssert.isTrue(
                tree.values().stream().noneMatch(child -> id.equals(child.getParentId())),
                ResultCode.MENU_HAS_CHILDREN);
        ServiceAssert.isTrue(
                menuButtonMapper.selectCount(
                                new LambdaQueryWrapper<SysMenuButton>()
                                        .eq(SysMenuButton::getMenuId, id)
                                        .eq(
                                                SysMenuButton::getDeleteFlag,
                                                RobotConstants.DELETE_FLAG_ACTIVE))
                        == 0,
                ResultCode.DATA_REFERENCED,
                "请先删除页面下的按钮权限");
        ServiceAssert.isTrue(
                baseMapper.update(
                                menu,
                                new LambdaUpdateWrapper<SysMenu>()
                                        .eq(SysMenu::getId, id)
                                        .eq(SysMenu::getRevision, expectedRevision)
                                        .set(SysMenu::getDeleteFlag, 0))
                        == 1,
                ResultCode.DATA_CONCURRENT_CONFLICT);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getMenuId, id));
        changeRecorder.record("delete_menu", id, null);
    }

    /**
     * 在同一树锁内校验环和路径冲突并重排两侧兄弟，避免并发形成环。
     *
     * @param id 记录ID
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void moveMenu(String id, MenuMoveRequest request) {
        stateStore.lockMenuTree();
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        directoryPolicy.requirePlatformAdministrator();
        ServiceAssert.isTrue(
                Set.of("inside", "inner", "before", "after")
                        .contains(Objects.toString(request.getPosition(), "")),
                ResultCode.PARAM_INVALID,
                "拖拽位置不合法");
        Map<String, SysMenu> tree = tree();
        SysMenu source = MenuTopology.node(id, tree),
                target = MenuTopology.node(request.getTargetId(), tree);
        ServiceAssert.isTrue(!id.equals(target.getId()), ResultCode.PARAM_INVALID, "不能移动到自身");
        assertRevision(request.getRevision(), source.getRevision());
        boolean inside =
                "inside".equals(request.getPosition()) || "inner".equals(request.getPosition());
        String oldParent = MenuTopology.parentId(source.getParentId());
        String newParent = inside ? target.getId() : MenuTopology.parentId(target.getParentId());
        MenuTopology.assertPlacement(id, newParent, tree);
        ServiceAssert.isTrue(
                !MenuTopology.descendants(id, tree).contains(target.getId()),
                ResultCode.PARAM_INVALID,
                "禁止移动到自身子级下");
        MenuTopology.assertUniquePath(id, newParent, source.getPath(), tree);
        List<SysMenu> destination = MenuTopology.siblings(newParent, id, tree);
        int index =
                inside
                        ? destination.size()
                        : MenuTopology.indexOf(destination, target.getId())
                                + ("after".equals(request.getPosition()) ? 1 : 0);
        destination.add(index, source);
        source.setParentId(newParent);
        if (!Objects.equals(oldParent, newParent)) {
            reorder(MenuTopology.siblings(oldParent, id, tree), null);
        }
        reorder(destination, id);
        changeRecorder.record(
                "move_menu",
                id,
                Map.of("targetId", target.getId(), "position", request.getPosition()));
    }

    /**
     * 新增独立按钮资源，要求所属页面有效；不隐式授予任何角色。
     *
     * @param request 该用例接收的白名单请求
     * @return 当前事实派生的结果
     */
    @Transactional(rollbackFor = Exception.class)
    public String createButton(MenuButtonSaveRequest request) {
        return createButton(request.getMenuId(), request);
    }

    /**
     * 新增独立按钮资源，要求所属页面有效；不隐式授予任何角色。
     *
     * @param menuId 页面菜单ID
     * @param request 该用例接收的白名单请求
     * @return 当前事实派生的结果
     */
    @Transactional(rollbackFor = Exception.class)
    public String createButton(String menuId, MenuButtonSaveRequest request) {
        stateStore.lockMenuTree();
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        directoryPolicy.requirePlatformAdministrator();
        ServiceAssert.notBlank(menuId, ResultCode.PARAM_INVALID, "按钮必须属于菜单页面");
        ServiceAssert.isTrue(
                request.getMenuId() == null || menuId.equals(request.getMenuId()),
                ResultCode.PARAM_INVALID,
                "按钮所属页面与路径不一致");
        requireButtonPage(menuId, tree());
        validateButton(null, request);
        SysMenuButton button = new SysMenuButton();
        button.setMenuId(menuId);
        MenuWriteValues.apply(button, request);
        button.setDeleteFlag(RobotConstants.DELETE_FLAG_ACTIVE);
        button.setRevision(0);
        ServiceAssert.isTrue(menuButtonMapper.insert(button) == 1, ResultCode.FAILED);
        changeRecorder.record(
                "save_button", button.getId(), Map.of("permCode", button.getPermCode()));
        return button.getId();
    }

    /**
     * 按客户端版本编辑按钮，保留所属页面并校验精确权限码。
     *
     * @param id 记录ID
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateButton(String id, MenuButtonSaveRequest request) {
        stateStore.lockMenuTree();
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        directoryPolicy.requirePlatformAdministrator();
        SysMenuButton button = activeButton(id);
        assertRevision(request.getRevision(), button.getRevision());
        ServiceAssert.isTrue(
                request.getMenuId() == null || button.getMenuId().equals(request.getMenuId()),
                ResultCode.PARAM_INVALID,
                "编辑按钮不能改变所属页面");
        requireButtonPage(button.getMenuId(), tree());
        validateButton(id, request);
        if (!Objects.equals(button.getPermCode(), request.getPermission())) {
            ServiceAssert.isTrue(
                    roleButtonMapper.selectCount(
                                    new LambdaQueryWrapper<SysRoleButton>()
                                            .eq(SysRoleButton::getButtonId, id)
                                            .eq(SysRoleButton::getDeleteFlag, 1))
                            == 0,
                    ResultCode.DATA_REFERENCED,
                    "已绑定按钮编码须经独立迁移，不允许普通目录更新改写");
        }
        MenuWriteValues.apply(button, request);
        button.setRevision(request.getRevision());
        ServiceAssert.isTrue(
                menuButtonMapper.updateById(button) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record("update_button", id, null);
    }

    /**
     * 软删按钮及其角色绑定，审计和权限版本同事务推进。
     *
     * @param id 记录ID
     * @param expectedRevision 客户端期望的聚合版本
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteButton(String id, Integer expectedRevision) {
        stateStore.lockMenuTree();
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        directoryPolicy.requirePlatformAdministrator();
        SysMenuButton button = activeButton(id);
        assertRevision(expectedRevision, button.getRevision());
        ServiceAssert.isTrue(
                menuButtonMapper.update(
                                button,
                                new LambdaUpdateWrapper<SysMenuButton>()
                                        .eq(SysMenuButton::getId, id)
                                        .eq(SysMenuButton::getRevision, expectedRevision)
                                        .set(SysMenuButton::getDeleteFlag, 0))
                        == 1,
                ResultCode.DATA_CONCURRENT_CONFLICT);
        roleButtonMapper.delete(
                new LambdaQueryWrapper<SysRoleButton>().eq(SysRoleButton::getButtonId, id));
        changeRecorder.record("delete_button", id, null);
    }

    /**
     * 仅允许启用的页面菜单作为按钮所属资源。
     *
     * @param id 记录ID
     */
    public void requireButtonPage(String id) {
        requireButtonPage(id, tree());
    }

    private void requireButtonPage(String id, Map<String, SysMenu> tree) {
        SysMenu page = MenuTopology.node(id, tree);
        ServiceAssert.isTrue(
                "menu".equals(page.getMenuType())
                        && Integer.valueOf(1).equals(page.getStatusFlag()),
                ResultCode.PARAM_INVALID,
                "按钮必须属于有效菜单页面");
    }

    private SysMenuButton activeButton(String id) {
        SysMenuButton button =
                ServiceAssert.notNull(menuButtonMapper.selectById(id), ResultCode.MENU_NOT_FOUND);
        ServiceAssert.isTrue(
                RobotConstants.DELETE_FLAG_ACTIVE.equals(button.getDeleteFlag()),
                ResultCode.MENU_NOT_FOUND);
        return button;
    }

    private Map<String, SysMenu> tree() {
        return list(
                        new LambdaQueryWrapper<SysMenu>()
                                .eq(SysMenu::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream()
                .collect(Collectors.toMap(SysMenu::getId, Function.identity()));
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

    private static void assertRevision(Integer expected, Integer actual) {
        ServiceAssert.notNull(expected, ResultCode.PARAM_INVALID, "更新必须提交 revision");
        ServiceAssert.isTrue(
                expected >= 0 && Objects.equals(expected, actual),
                ResultCode.DATA_CONCURRENT_CONFLICT);
    }

    private void validateButton(String id, MenuButtonSaveRequest request) {
        MenuWriteValues.validateButtonFields(request);
        ServiceAssert.isTrue(
                menuButtonMapper.selectCount(
                                new LambdaQueryWrapper<SysMenuButton>()
                                        .eq(SysMenuButton::getPermCode, request.getPermission())
                                        .eq(
                                                SysMenuButton::getDeleteFlag,
                                                RobotConstants.DELETE_FLAG_ACTIVE)
                                        .ne(id != null, SysMenuButton::getId, id))
                        == 0,
                ResultCode.DATA_DUPLICATED,
                "按钮权限标识已存在");
    }
}
