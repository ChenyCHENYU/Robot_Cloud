package com.robot.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.redis.core.RedisHelper;
import com.robot.system.support.SystemKeys;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import cn.dev33.satoken.stp.StpInterface;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sa-Token 权限数据源：@SaCheckPermission 的实现（权限码 = 页面码 + 按钮码）。
 *
 * <p>缓存设计：
 * <ul>
 *   <li>缓存载荷 {@link CachedPermissions} 同时携带超管标志与权限码集合，
 *       命中路径零 DB 查询（超管判断随版本失效保持新鲜，撤销即时生效）；</li>
 *   <li>TTL 30 分钟 + 抖动；键携带版本段，角色/菜单/授权变更自增版本全量惰性失效；</li>
 *   <li>角色来源 = 绑定角色 + 生效中的临时授权角色并集。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RobotStpInterface implements StpInterface {

    private static final Duration PERM_TTL = Duration.ofMinutes(30);

    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuMapper menuMapper;
    private final SysMenuButtonMapper menuButtonMapper;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;
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
        String cacheKey = SystemKeys.userPermissions(companyId, userId);

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

    /** 租户来源（登录态公司；package 缝：单测可覆写注入固定值）。 */
    String currentCompanyId() {
        return AuthUtil.getLoginCompanyId();
    }

    /** 回源构建：角色并集 → 超管标志 + 授权菜单/按钮权限码。 */
    private CachedPermissions load(String companyId, String userId) {
        List<SysRole> companyRoles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCompanyId, companyId)
                .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .eq(SysRole::getStatusFlag, 1));
        if (companyRoles.isEmpty()) {
            return new CachedPermissions(false, List.of());
        }

        Set<String> roleIds = resolveRoleIds(userId, companyRoles);
        boolean superAdmin = companyRoles.stream().anyMatch(role ->
                RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(role.getRoleCode()));
        if (superAdmin) {
            return new CachedPermissions(true, List.of());
        }
        if (roleIds.isEmpty()) {
            return new CachedPermissions(false, List.of());
        }

        Set<String> permissions = new HashSet<>();
        List<String> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .in(SysRoleMenu::getRoleId, roleIds))
                .stream().map(SysRoleMenu::getMenuId).toList();
        List<SysMenu> grantedMenus = menuIds.isEmpty() ? List.of()
                : menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .in(SysMenu::getId, menuIds)
                .eq(SysMenu::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .eq(SysMenu::getStatusFlag, 1));
        for (SysMenu menu : grantedMenus) {
            if (menu.getPermCode() != null && !menu.getPermCode().isBlank()) {
                permissions.add(menu.getPermCode());
            }
        }
        if (!grantedMenus.isEmpty()) {
            for (SysMenuButton button : menuButtonMapper.selectList(
                    new LambdaQueryWrapper<SysMenuButton>()
                            .in(SysMenuButton::getMenuId,
                                    grantedMenus.stream().map(SysMenu::getId).toList())
                            .eq(SysMenuButton::getStatusFlag, 1)
                            .eq(SysMenuButton::getDeleteFlag,
                                    RobotConstants.DELETE_FLAG_ACTIVE))) {
                permissions.add(button.getPermCode());
            }
        }
        return new CachedPermissions(false, new ArrayList<>(permissions));
    }

    /** 有效角色并集：用户绑定 ∩ 本公司角色 + 生效中的临时授权角色。 */
    private Set<String> resolveRoleIds(String userId, List<SysRole> companyRoles) {
        Set<String> companyRoleIds = companyRoles.stream()
                .map(SysRole::getId).collect(Collectors.toSet());
        Set<String> bound = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId)
                        .eq(SysUserRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
        Set<String> roleIds = companyRoleIds.stream()
                .filter(bound::contains).collect(Collectors.toSet());
        for (SysTempAuthorization tempAuth : tempAuthorizationMapper.selectList(
                new LambdaQueryWrapper<SysTempAuthorization>()
                        .eq(SysTempAuthorization::getUserId, userId)
                        .eq(SysTempAuthorization::getStatusFlag, 1)
                        .eq(SysTempAuthorization::getDeleteFlag,
                                RobotConstants.DELETE_FLAG_ACTIVE)
                        .le(SysTempAuthorization::getEffectFrom, LocalDateTime.now())
                        .ge(SysTempAuthorization::getEffectTo, LocalDateTime.now()))) {
            if (companyRoleIds.contains(tempAuth.getRoleId())) {
                roleIds.add(tempAuth.getRoleId());
            }
        }
        return roleIds;
    }
}
