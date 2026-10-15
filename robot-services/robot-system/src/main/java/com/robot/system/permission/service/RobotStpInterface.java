package com.robot.system.permission.service;

import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.EffectiveRoleResolver.EffectiveRoles;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.support.SystemKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 权限数据源：@SaCheckPermission 的实现（权限码 = 页面码 + 按钮码）。
 *
 * <p>缓存与角色事实源：
 *
 * <ul>
 *   <li>有效角色一律经 {@link EffectiveRoleResolver}（RC01：超管只来自用户自身有效角色， 公司存在超管而用户未绑定不得获得通配）；
 *   <li>数据库 epoch 与业务变更同事务，缓存命中仍校验主体有效性与已提交版本；
 *   <li>缓存期限不超过临时授权下一时间边界，拒绝过期载荷。
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RobotStpInterface implements StpInterface {

    private static final Duration PERM_TTL = Duration.ofMinutes(30);
    private static final java.util.regex.Pattern EXACT_PERMISSION =
            java.util.regex.Pattern.compile("[\\w.-]+(?::[\\w.-]+)+");

    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleButtonMapper roleButtonMapper;
    private final SysMenuMapper menuMapper;
    private final SysMenuButtonMapper menuButtonMapper;
    private final EffectiveRoleResolver effectiveRoleResolver;
    private final RedisHelper redisHelper;
    private final PermissionStateStore stateStore;
    private final Clock clock;

    /** 缓存载荷（Jackson 默认类型序列化，字段公开保证往返兼容）。 */
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CachedPermissions {

        public boolean superAdmin;

        public List<String> permissions = new ArrayList<>();
        public LocalDateTime validUntil;

        /** 初始化当前用例的明确协作依赖或兼容值元组。 */
        public CachedPermissions() {}

        /**
         * 初始化当前用例的明确协作依赖或兼容值元组。
         *
         * @param superAdmin 永久或有效角色是否含公司超管
         * @param permissions permissions协作输入
         */
        public CachedPermissions(boolean superAdmin, List<String> permissions) {
            this.superAdmin = superAdmin;
            this.permissions = new ArrayList<>(permissions);
        }
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        String companyId = currentCompanyId();
        if (companyId == null || companyId.isBlank() || loginId == null) {
            return List.of();
        }
        String userId = String.valueOf(loginId);
        if (!effectiveRoleResolver.isActiveSubject(userId, companyId)) {
            return List.of();
        }
        String cacheKey =
                SystemKeys.userPermissions(
                        companyId,
                        userId,
                        stateStore.currentVersion(PermissionStateStore.PERMISSIONS));

        CachedPermissions cached =
                redisHelper.getObject(cacheKey, CachedPermissions.class).orElse(null);
        if (!isCurrent(cached)) {
            cached = load(companyId, userId);
            cacheWithinDeadline(cacheKey, cached);
        }
        if (!isCurrent(cached)) {
            return List.of();
        }
        return cached.superAdmin ? List.of("*") : List.copyOf(cached.permissions);
    }

    private boolean isCurrent(CachedPermissions cached) {
        return cached != null
                && cached.validUntil != null
                && LocalDateTime.now(clock).isBefore(cached.validUntil);
    }

    private void cacheWithinDeadline(String key, CachedPermissions cached) {
        Duration ttl = Duration.between(LocalDateTime.now(clock), cached.validUntil);
        if (!ttl.isNegative() && !ttl.isZero()) {
            redisHelper.setObjectExact(key, cached, ttl);
        }
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        String companyId = currentCompanyId();
        if (companyId == null || loginId == null) {
            return List.of();
        }
        return effectiveRoleResolver.resolve(String.valueOf(loginId), companyId).roles().stream()
                .map(role -> role.getRoleCode())
                .distinct()
                .sorted()
                .toList();
    }

    /** 回源构建：有效角色 → 超管标志 + 授权菜单/按钮权限码。 */
    private CachedPermissions load(String companyId, String userId) {
        EffectiveRoles effective = effectiveRoleResolver.resolve(userId, companyId);
        LocalDateTime until = cacheDeadline(effective);
        if (effective.superAdmin()) {
            return payload(true, List.of(), until);
        }
        if (effective.isEmpty()) {
            return payload(false, List.of(), until);
        }

        Set<String> permissions = new HashSet<>();
        addExactCodes(
                permissions,
                grantedMenus(effective.roleIds()).stream().map(SysMenu::getPermCode).toList());
        addExactCodes(
                permissions,
                grantedButtons(effective.roleIds()).stream()
                        .map(SysMenuButton::getPermCode)
                        .toList());
        return payload(false, permissions.stream().sorted().toList(), until);
    }

    private LocalDateTime cacheDeadline(EffectiveRoles effective) {
        LocalDateTime deadline = LocalDateTime.now(clock).plus(PERM_TTL);
        return effective.nextChangeAt() != null && effective.nextChangeAt().isBefore(deadline)
                ? effective.nextChangeAt()
                : deadline;
    }

    /** 普通角色不能借存量目录中的通配码获得超管能力。 */
    private static void addExactCodes(Set<String> target, List<String> codes) {
        codes.stream()
                .filter(code -> code != null && EXACT_PERMISSION.matcher(code).matches())
                .forEach(target::add);
    }

    private static CachedPermissions payload(
            boolean superAdmin, List<String> permissions, LocalDateTime until) {
        CachedPermissions payload = new CachedPermissions(superAdmin, permissions);
        payload.validUntil = until;
        return payload;
    }

    private List<SysMenu> grantedMenus(Set<String> roleIds) {
        List<String> menuIds =
                roleMenuMapper
                        .selectList(
                                new LambdaQueryWrapper<SysRoleMenu>()
                                        .in(SysRoleMenu::getRoleId, roleIds))
                        .stream()
                        .map(SysRoleMenu::getMenuId)
                        .toList();
        return menuIds.isEmpty()
                ? List.of()
                : menuMapper
                        .selectList(
                                new LambdaQueryWrapper<SysMenu>()
                                        .in(SysMenu::getId, menuIds)
                                        .eq(SysMenu::getDeleteFlag, 1)
                                        .eq(SysMenu::getStatusFlag, 1))
                        .stream()
                        .filter(
                                menu ->
                                        menuIds.contains(menu.getId())
                                                && Integer.valueOf(1).equals(menu.getDeleteFlag())
                                                && Integer.valueOf(1).equals(menu.getStatusFlag()))
                        .toList();
    }

    /** RC11：按钮权限只来自 sys_role_button 精确绑定（菜单授权不再隐含按钮）。 */
    private List<SysMenuButton> grantedButtons(Set<String> roleIds) {
        List<String> buttonIds =
                roleButtonMapper
                        .selectList(
                                new LambdaQueryWrapper<SysRoleButton>()
                                        .in(SysRoleButton::getRoleId, roleIds))
                        .stream()
                        .map(SysRoleButton::getButtonId)
                        .toList();
        if (buttonIds.isEmpty()) {
            return List.of();
        }
        List<SysMenuButton> buttons =
                menuButtonMapper.selectBatchIds(buttonIds).stream()
                        .filter(
                                button ->
                                        button.getStatusFlag() != null
                                                && button.getStatusFlag() == 1
                                                && button.getDeleteFlag() != null
                                                && button.getDeleteFlag() == 1
                                                && buttonIds.contains(button.getId()))
                        .toList();
        if (buttons.isEmpty()) {
            return List.of();
        }
        Set<String> parentIds =
                buttons.stream()
                        .map(SysMenuButton::getMenuId)
                        .collect(java.util.stream.Collectors.toSet());
        Set<String> enabledParents =
                menuMapper
                        .selectList(
                                new LambdaQueryWrapper<SysMenu>()
                                        .in(SysMenu::getId, parentIds)
                                        .eq(SysMenu::getStatusFlag, 1)
                                        .eq(SysMenu::getDeleteFlag, 1))
                        .stream()
                        .filter(
                                menu ->
                                        Integer.valueOf(1).equals(menu.getStatusFlag())
                                                && Integer.valueOf(1).equals(menu.getDeleteFlag())
                                                && "menu".equals(menu.getMenuType())
                                                && parentIds.contains(menu.getId()))
                        .map(SysMenu::getId)
                        .collect(java.util.stream.Collectors.toSet());
        return buttons.stream()
                .filter(button -> enabledParents.contains(button.getMenuId()))
                .toList();
    }

    /** 租户来源（登录态公司；package 缝：单测可覆写注入固定值）。 */
    String currentCompanyId() {
        return AuthUtil.getLoginCompanyId();
    }
}
