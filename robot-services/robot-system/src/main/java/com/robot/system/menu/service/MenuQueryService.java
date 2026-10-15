package com.robot.system.menu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.api.system.dto.RemoteMenuRouteDTO;
import com.robot.common.core.constant.RobotConstants;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.api.vo.menu.MenuButtonVO;
import com.robot.system.api.vo.menu.MenuTreeNode;
import com.robot.system.api.vo.permission.PermissionItem;
import com.robot.system.api.vo.permission.PermissionNode;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.EffectiveRoleResolver.EffectiveRoles;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 菜单读侧（CQRS-lite）：树/路由/按钮映射/权限树/权限码清单等全部查询装配。 写侧见 {@link MenuService}；两侧共享菜单表但互不依赖，读侧无事务。 */
@Service
@RequiredArgsConstructor
public class MenuQueryService {
    private final SysMenuMapper menuMapper;
    private final SysMenuButtonMapper menuButtonMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleButtonMapper roleButtonMapper;
    private final EffectiveRoleResolver effectiveRoleResolver;

    /**
     * 全量菜单树（管理端；菜单为全局表，可见性由角色授权控制）。
     *
     * @return 当前事实派生的结果
     */
    public List<MenuTreeNode> menuTree() {
        return MenuTreeProjection.buildTree(menus(false), null);
    }

    /**
     * 必要祖先仅用于导航结构，不附赠页面或动作权限。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     * @return 当前事实派生的结果
     */
    public List<RemoteMenuRouteDTO> routesOfUser(String userId, String companyId) {
        return MenuTreeProjection.toRoutes(accessOfUser(userId, companyId).menus(), null);
    }

    /**
     * 与API精确绑定一致：隐式父菜单不提供page权限，按钮只来自有效role_button。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     * @return 当前事实派生的结果
     */
    public Map<String, List<String>> buttonListOfUser(String userId, String companyId) {
        return MenuAccessProjection.buttonList(accessOfUser(userId, companyId));
    }

    private MenuAccessProjection.Access accessOfUser(String userId, String companyId) {
        EffectiveRoles effective = effectiveRoleResolver.resolve(userId, companyId);
        if (effective.isEmpty()) {
            return new MenuAccessProjection.Access(false, Set.of(), List.of(), List.of(), Map.of());
        }
        List<SysMenu> active = menus(true);
        Map<String, SysMenu> byId =
                active.stream()
                        .collect(
                                Collectors.toMap(
                                        SysMenu::getId, java.util.function.Function.identity()));
        Set<String> explicit =
                effective.superAdmin() ? byId.keySet() : menuIdsOfRoles(effective.roleIds());
        List<SysMenuButton> buttons = buttonsOnActiveMenus(effective, byId);
        Set<String> included = MenuAccessProjection.navigationMenuIds(explicit, buttons, byId);
        return new MenuAccessProjection.Access(
                effective.superAdmin(),
                explicit,
                active.stream().filter(menu -> included.contains(menu.getId())).toList(),
                buttons,
                byId);
    }

    private List<SysMenuButton> buttonsOnActiveMenus(
            EffectiveRoles effective, Map<String, SysMenu> activeMenus) {
        return boundButtons(effective).stream()
                .filter(button -> activeMenus.containsKey(button.getMenuId()))
                .toList();
    }

    private Set<String> menuIdsOfRoles(Set<String> roleIds) {
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return roleMenuMapper
                .selectList(
                        new LambdaQueryWrapper<SysRoleMenu>()
                                .in(SysRoleMenu::getRoleId, roleIds)
                                .eq(SysRoleMenu::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream()
                .filter(
                        binding ->
                                roleIds.contains(binding.getRoleId())
                                        && Integer.valueOf(1).equals(binding.getDeleteFlag()))
                .map(SysRoleMenu::getMenuId)
                .collect(Collectors.toSet());
    }

    /** 精确绑定的按钮（超管为全部）。 */
    private List<SysMenuButton> boundButtons(EffectiveRoleResolver.EffectiveRoles effective) {
        if (effective.superAdmin()) {
            return menuButtonMapper
                    .selectList(
                            new LambdaQueryWrapper<SysMenuButton>()
                                    .eq(SysMenuButton::getStatusFlag, 1)
                                    .eq(
                                            SysMenuButton::getDeleteFlag,
                                            RobotConstants.DELETE_FLAG_ACTIVE))
                    .stream()
                    .filter(
                            button ->
                                    Integer.valueOf(1).equals(button.getStatusFlag())
                                            && Integer.valueOf(1).equals(button.getDeleteFlag()))
                    .toList();
        }
        if (effective.isEmpty()) {
            return List.of();
        }
        List<String> buttonIds =
                roleButtonMapper
                        .selectList(
                                new LambdaQueryWrapper<SysRoleButton>()
                                        .in(SysRoleButton::getRoleId, effective.roleIds())
                                        .eq(
                                                SysRoleButton::getDeleteFlag,
                                                RobotConstants.DELETE_FLAG_ACTIVE))
                        .stream()
                        .filter(
                                binding ->
                                        effective.roleIds().contains(binding.getRoleId())
                                                && Integer.valueOf(1)
                                                        .equals(binding.getDeleteFlag()))
                        .map(SysRoleButton::getButtonId)
                        .toList();
        if (buttonIds.isEmpty()) {
            return List.of();
        }
        return menuButtonMapper.selectBatchIds(buttonIds).stream()
                .filter(
                        button ->
                                buttonIds.contains(button.getId())
                                        && Integer.valueOf(1).equals(button.getStatusFlag())
                                        && RobotConstants.DELETE_FLAG_ACTIVE.equals(
                                                button.getDeleteFlag()))
                .toList();
    }

    /**
     * 按钮清单（menuId 空 = 全量；管理端与装配复用）。
     *
     * @param menuId 页面菜单ID
     * @return 当前事实派生的结果
     */
    public List<MenuButtonVO> buttonsOfMenu(String menuId) {
        return menuButtonMapper
                .selectList(
                        new LambdaQueryWrapper<SysMenuButton>()
                                .eq(
                                        menuId != null && !menuId.isBlank(),
                                        SysMenuButton::getMenuId,
                                        menuId)
                                .eq(SysMenuButton::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                                .orderByAsc(SysMenuButton::getSortNo, SysMenuButton::getId))
                .stream()
                .map(
                        button ->
                                new MenuButtonVO(
                                        button.getId(),
                                        button.getMenuId(),
                                        button.getButtonName(),
                                        button.getPermCode(),
                                        button.getSortNo(),
                                        button.getStatusFlag(),
                                        button.getPermCode(),
                                        button.getRemark(),
                                        button.getRevision()))
                .toList();
    }

    /**
     * 权限树（/sys/permissions/tree：菜单 + 按钮；按钮一次批量查询）。
     *
     * @return 当前事实派生的结果
     */
    public List<PermissionNode> permissionTree() {
        Map<String, List<MenuButtonVO>> buttonsByMenu =
                buttonsOfMenu(null).stream().collect(Collectors.groupingBy(MenuButtonVO::menuId));
        return MenuTreeProjection.appendButtons(menuTree(), buttonsByMenu);
    }

    /**
     * 权限码清单（/sys/permissions：页面码 + 按钮码）。
     *
     * @return 当前事实派生的结果
     */
    public List<PermissionItem> permissionList() {
        List<PermissionItem> result = new ArrayList<>();
        for (SysMenu menu : menus(false)) {
            if (menu.getPermCode() != null && !menu.getPermCode().isBlank()) {
                result.add(
                        new PermissionItem(
                                menu.getId(),
                                menu.getId(),
                                menu.getMenuName(),
                                menu.getPermCode(),
                                "menu"));
            }
        }
        for (SysMenuButton button :
                menuButtonMapper.selectList(
                        new LambdaQueryWrapper<SysMenuButton>()
                                .eq(
                                        SysMenuButton::getDeleteFlag,
                                        RobotConstants.DELETE_FLAG_ACTIVE))) {
            result.add(
                    new PermissionItem(
                            button.getId(),
                            button.getMenuId(),
                            button.getButtonName(),
                            button.getPermCode(),
                            "button"));
        }
        return result;
    }

    /* ==================== 私有装配 ==================== */

    private List<SysMenu> menus(boolean activeOnly) {
        return menuMapper
                .selectList(
                        new LambdaQueryWrapper<SysMenu>()
                                .eq(SysMenu::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                                .eq(activeOnly, SysMenu::getStatusFlag, 1)
                                .orderByAsc(SysMenu::getSortNo, SysMenu::getId))
                .stream()
                .filter(
                        menu ->
                                Integer.valueOf(1).equals(menu.getDeleteFlag())
                                        && (!activeOnly
                                                || Integer.valueOf(1).equals(menu.getStatusFlag())))
                .toList();
    }
}
