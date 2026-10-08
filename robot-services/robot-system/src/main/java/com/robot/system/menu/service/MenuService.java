package com.robot.system.menu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 菜单写侧：菜单与按钮的全部变更操作（事务边界）。
 * 每次变更经 {@link PermissionChangeRecorder} 统一完成审计留痕 + 权限缓存失效；
 * 查询装配见 {@link MenuQueryService}。
 */
@Service
@RequiredArgsConstructor
public class MenuService extends ServiceImpl<SysMenuMapper, SysMenu> {

    private final SysMenuButtonMapper menuButtonMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final PermissionChangeRecorder changeRecorder;

    @Transactional(rollbackFor = Exception.class)
    public String createMenu(SysMenu menu) {
        ServiceAssert.notNull(menu.getMenuName(), ResultCode.PARAM_INVALID, "菜单名不能为空");
        if (menu.getParentId() != null && !menu.getParentId().isBlank()) {
            ServiceAssert.notNull(getById(menu.getParentId()), ResultCode.MENU_NOT_FOUND,
                    "上级菜单不存在");
        }
        menu.setStatusFlag(defaultFlag(menu.getStatusFlag(), 1));
        menu.setVisibleFlag(defaultFlag(menu.getVisibleFlag(), 1));
        menu.setKeepAliveFlag(defaultFlag(menu.getKeepAliveFlag(), 0));
        save(menu);
        changeRecorder.record("save_menu", menu.getId(),
                java.util.Map.of("name", menu.getMenuName()));
        return menu.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateMenu(String id, SysMenu incoming) {
        SysMenu menu = ServiceAssert.notNull(getById(id), ResultCode.MENU_NOT_FOUND);
        ServiceAssert.isTrue(!id.equals(incoming.getParentId()),
                ResultCode.PARAM_INVALID, "上级菜单不能是自身");
        menu.setParentId(incoming.getParentId());
        menu.setMenuType(incoming.getMenuType());
        menu.setMenuName(incoming.getMenuName());
        menu.setPath(incoming.getPath());
        menu.setComponent(incoming.getComponent());
        menu.setRedirect(incoming.getRedirect());
        menu.setPermCode(incoming.getPermCode());
        menu.setIcon(incoming.getIcon());
        menu.setSortNo(incoming.getSortNo());
        overrideIfNotNull(incoming.getVisibleFlag(), menu::setVisibleFlag);
        overrideIfNotNull(incoming.getKeepAliveFlag(), menu::setKeepAliveFlag);
        overrideIfNotNull(incoming.getStatusFlag(), menu::setStatusFlag);
        updateById(menu);
        changeRecorder.record("update_menu", id, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteMenu(String id) {
        ServiceAssert.notNull(getById(id), ResultCode.MENU_NOT_FOUND);
        long children = count(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getParentId, id)
                .eq(SysMenu::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE));
        ServiceAssert.isTrue(children == 0, ResultCode.MENU_HAS_CHILDREN);
        // MP 逻辑删除（软删）
        removeById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getMenuId, id));
        changeRecorder.record("delete_menu", id, null);
    }

    /**
     * 拖拽排序（前端 PUT /sys/menus/{id}/move {targetId, position}）。
     * position：inner=成为目标子级（置于末位）；before/after=与目标同级并紧邻。
     */
    @Transactional(rollbackFor = Exception.class)
    public void moveMenu(String id, String targetId, String position) {
        SysMenu menu = ServiceAssert.notNull(getById(id), ResultCode.MENU_NOT_FOUND);
        SysMenu target = ServiceAssert.notNull(getById(targetId), ResultCode.MENU_NOT_FOUND);
        ServiceAssert.isTrue(!id.equals(targetId), ResultCode.PARAM_INVALID, "不能移动到自身");

        int newSort;
        if ("inner".equals(position)) {
            assertNotDescendant(id, target);
            menu.setParentId(target.getId());
            newSort = (int) count(new LambdaQueryWrapper<SysMenu>()
                    .eq(SysMenu::getParentId, target.getId())
                    .eq(SysMenu::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE));
        } else {
            menu.setParentId(target.getParentId());
            int targetSort = target.getSortNo() == null ? 0 : target.getSortNo();
            newSort = "before".equals(position) ? targetSort : targetSort + 1;
        }
        menu.setSortNo(newSort);
        updateById(menu);
        changeRecorder.record("move_menu", id,
                java.util.Map.of("targetId", targetId, "position", position));
    }

    @Transactional(rollbackFor = Exception.class)
    public String createButton(SysMenuButton button) {
        ServiceAssert.notNull(getById(button.getMenuId()), ResultCode.MENU_NOT_FOUND);
        ServiceAssert.notBlank(button.getPermCode(), ResultCode.PARAM_INVALID);
        button.setStatusFlag(defaultFlag(button.getStatusFlag(), 1));
        menuButtonMapper.insert(button);
        changeRecorder.record("save_button", button.getId(),
                java.util.Map.of("permCode", button.getPermCode()));
        return button.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateButton(String id, SysMenuButton incoming) {
        SysMenuButton button = ServiceAssert.notNull(menuButtonMapper.selectById(id),
                ResultCode.MENU_NOT_FOUND);
        if (incoming.getButtonName() != null) {
            button.setButtonName(incoming.getButtonName());
        }
        if (incoming.getPermCode() != null && !incoming.getPermCode().isBlank()) {
            button.setPermCode(incoming.getPermCode());
        }
        overrideIfNotNull(incoming.getSortNo(), button::setSortNo);
        overrideIfNotNull(incoming.getStatusFlag(), button::setStatusFlag);
        menuButtonMapper.updateById(button);
        changeRecorder.record("update_button", id, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteButton(String id) {
        SysMenuButton button = menuButtonMapper.selectById(id);
        if (button == null
                || !button.getDeleteFlag().equals(RobotConstants.DELETE_FLAG_ACTIVE)) {
            throw new ServiceException(ResultCode.MENU_NOT_FOUND, "按钮权限不存在");
        }
        menuButtonMapper.deleteById(id);
        changeRecorder.record("delete_button", id, null);
    }

    /* ==================== 私有 ==================== */

    /** 环检测：target 不得是 id 的后代（沿 parent 链上溯，深度上限防脏数据死循环）。 */
    private void assertNotDescendant(String id, SysMenu target) {
        String walk = target.getId();
        for (int depth = 0; depth < 64 && walk != null; depth++) {
            if (id.equals(walk)) {
                throw new ServiceException(ResultCode.PARAM_INVALID, "禁止移动到自身子级下");
            }
            SysMenu node = getById(walk);
            walk = node == null ? null : node.getParentId();
        }
    }

    private static int defaultFlag(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static void overrideIfNotNull(Integer value,
                                          java.util.function.IntConsumer setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
