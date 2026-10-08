package com.robot.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.EffectiveRoleResolver.EffectiveRoles;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.support.SystemKeys;
import cn.dev33.satoken.stp.StpInterface;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sa-Token 权限数据源：@SaCheckPermission 的实现（权限码 = 页面码 + 按钮码）。
 *
 * <p>缓存与角色事实源：
 * <ul>
 *   <li>有效角色一律经 {@link EffectiveRoleResolver}（RC01：超管只来自用户自身有效角色，
 *       公司存在超管而用户未绑定不得获得通配）；</li>
 *   <li>缓存载荷 {@link CachedPermissions}（超管标志 + 权限码）命中路径零 DB 查询；
 *       键携带版本<b>实际值</b>（RC05：撤权随版本自增即时失效）；</li>
 *   <li>临时授权的到期边界收敛属于第 3 批（RC10），当前 TTL 上限 30 分钟。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RobotStpInterface implements StpInterface {

    private static final Duration PERM_TTL = Duration.ofMinutes(30);

    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleButtonMapper roleButtonMapper;
    private final SysMenuMapper menuMapper;
    private final SysMenuButtonMapper menuButtonMapper;
    private final EffectiveRoleResolver effectiveRoleResolver;
    private final RedisHelper redisHelper;

    /** 缓存载荷（Jackson 默认类型序列化，字段公开保证往返兼容）。 */
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CachedPermissions {

        public boolean superAdmin;

        public List<String> permissions = new ArrayList<>();

        public CachedPermissions() {
        }

        public CachedPermissions(boolean superAdmin, List<String> permissions) {
            this.superAdmin = superAdmin;
            this.permissions = permissions;
        }
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        String companyId = currentCompanyId();
        if (companyId == null) {
            return List.of();
        }
        String userId = String.valueOf(loginId);
        String cacheKey = SystemKeys.userPermissions(companyId, userId,
                redisHelper.readVersion(SystemKeys.permissionVersion()));

        CachedPermissions cached = redisHelper.getObject(cacheKey,
                CachedPermissions.class).orElse(null);
        if (cached == null) {
            cached = load(companyId, userId);
            redisHelper.setObject(cacheKey, cached, PERM_TTL);
        }
        return cached.superAdmin ? List.of("*") : cached.permissions;
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        List<String> roleCodes = AuthUtil.getLoginUser() == null
                ? null : AuthUtil.getLoginUser().getRoleCodes();
        return roleCodes == null ? List.of() : roleCodes;
    }

    /** 回源构建：有效角色 → 超管标志 + 授权菜单/按钮权限码。 */
    private CachedPermissions load(String companyId, String userId) {
        EffectiveRoles effective = effectiveRoleResolver.resolve(userId, companyId);
        if (effective.superAdmin()) {
            return new CachedPermissions(true, List.of());
        }
        if (effective.isEmpty()) {
            return new CachedPermissions(false, List.of());
        }

        Set<String> permissions = new HashSet<>();
        for (SysMenu menu : grantedMenus(effective.roleIds())) {
            if (menu.getPermCode() != null && !menu.getPermCode().isBlank()) {
                permissions.add(menu.getPermCode());
            }
        }
        for (SysMenuButton button : grantedButtons(effective.roleIds())) {
            permissions.add(button.getPermCode());
        }
        return new CachedPermissions(false, new ArrayList<>(permissions));
    }

    private List<SysMenu> grantedMenus(Set<String> roleIds) {
        List<String> menuIds = roleMenuMapper.selectList(
                        new LambdaQueryWrapper<SysRoleMenu>()
                                .in(SysRoleMenu::getRoleId, roleIds))
                .stream().map(SysRoleMenu::getMenuId).toList();
        return menuIds.isEmpty() ? List.of()
                : menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .in(SysMenu::getId, menuIds)
                .eq(SysMenu::getDeleteFlag, 1)
                .eq(SysMenu::getStatusFlag, 1));
    }

    /** RC11：按钮权限只来自 sys_role_button 精确绑定（菜单授权不再隐含按钮）。 */
    private List<SysMenuButton> grantedButtons(Set<String> roleIds) {
        List<String> buttonIds = roleButtonMapper.selectList(
                        new LambdaQueryWrapper<SysRoleButton>()
                                .in(SysRoleButton::getRoleId, roleIds))
                .stream().map(SysRoleButton::getButtonId).toList();
        if (buttonIds.isEmpty()) {
            return List.of();
        }
        return menuButtonMapper.selectBatchIds(buttonIds).stream()
                .filter(button -> button.getStatusFlag() != null && button.getStatusFlag() == 1
                        && button.getDeleteFlag() != null && button.getDeleteFlag() == 1)
                .toList();
    }

    /** 租户来源（登录态公司；package 缝：单测可覆写注入固定值）。 */
    String currentCompanyId() {
        return AuthUtil.getLoginCompanyId();
    }
}
