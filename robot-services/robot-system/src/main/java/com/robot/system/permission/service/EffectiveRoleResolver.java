package com.robot.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.system.api.entity.SysCompany;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysTenant;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.mapper.SysCompanyMapper;
import com.robot.system.mapper.SysTenantMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户有效角色事实源：有效账户/公司/成员、公司内绑定与半开临时授权时间窗。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Component
public class EffectiveRoleResolver {

    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;
    private final SysUserCompanyMapper membershipMapper;
    private final SysUserMapper userMapper;
    private final SysCompanyMapper companyMapper;
    private final SysTenantMapper tenantMapper;
    private final Clock clock;

    public EffectiveRoleResolver(SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper,
            SysTempAuthorizationMapper tempAuthorizationMapper, SysUserCompanyMapper membershipMapper,
            SysUserMapper userMapper, SysCompanyMapper companyMapper, SysTenantMapper tenantMapper) {
        this(roleMapper, userRoleMapper, tempAuthorizationMapper, membershipMapper,
                userMapper, companyMapper, tenantMapper, Clock.systemDefaultZone());
    }

    /** 测试显式传 Clock；默认保持项目 LocalDateTime 与运行时区的既有时间合同。 */
    @Autowired
    public EffectiveRoleResolver(SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper,
            SysTempAuthorizationMapper tempAuthorizationMapper, SysUserCompanyMapper membershipMapper,
            SysUserMapper userMapper, SysCompanyMapper companyMapper, SysTenantMapper tenantMapper,
            Clock clock) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.tempAuthorizationMapper = tempAuthorizationMapper;
        this.membershipMapper = membershipMapper;
        this.userMapper = userMapper;
        this.companyMapper = companyMapper;
        this.tenantMapper = tenantMapper;
        this.clock = clock;
    }

    /**
     * nextChangeAt 是下一临时角色生效/到期边界；缓存不得跨越该时刻。
     *
     * @author Robot Cloud
     * @since 1.0.0-SNAPSHOT
     */
    public record EffectiveRoles(boolean superAdmin, List<SysRole> roles, LocalDateTime nextChangeAt) {
        public static final EffectiveRoles EMPTY = new EffectiveRoles(false, List.of(), null);

        public EffectiveRoles {
            roles = List.copyOf(roles);
        }

        public EffectiveRoles(boolean superAdmin, List<SysRole> roles) {
            this(superAdmin, roles, null);
        }

        public boolean isEmpty() {
            return roles.isEmpty();
        }

        public Set<String> roleIds() {
            return roles.stream().map(SysRole::getId).collect(Collectors.toUnmodifiableSet());
        }
    }

    /** 缓存命中也必须复验账户、公司、租户和有效成员；没有身份时失败关闭。 */
    public boolean isActiveSubject(String userId, String companyId) {
        if (isBlank(userId) || isBlank(companyId)) {
            return false;
        }
        SysUser user = userMapper.selectById(userId);
        SysCompany company = companyMapper.selectById(companyId);
        if (!matchesActiveUser(user, userId) || !matchesActiveCompany(company, companyId)) {
            return false;
        }
        if (!hasActiveTenant(company.getTenantId())) {
            return false;
        }
        return hasUniqueActiveMembership(userId, companyId);
    }

    private static boolean matchesActiveUser(SysUser user, String userId) {
        return user != null && userId.equals(user.getId()) && active(user.getDeleteFlag(), user.getStatusFlag());
    }

    private static boolean matchesActiveCompany(SysCompany company, String companyId) {
        return company != null && companyId.equals(company.getId())
                && active(company.getDeleteFlag(), company.getStatusFlag()) && !isBlank(company.getTenantId());
    }

    private boolean hasActiveTenant(String tenantId) {
        SysTenant tenant = tenantMapper.selectById(tenantId);
        return tenant != null && tenantId.equals(tenant.getId()) && active(tenant.getDeleteFlag(), tenant.getStatusFlag());
    }

    private boolean hasUniqueActiveMembership(String userId, String companyId) {
        long memberships = membershipMapper.selectList(new LambdaQueryWrapper<SysUserCompany>()
                        .eq(SysUserCompany::getUserId, userId)
                        .eq(SysUserCompany::getCompanyId, companyId)
                        .eq(SysUserCompany::getStatusFlag, 1)
                        .eq(SysUserCompany::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream().filter(m -> userId.equals(m.getUserId())
                        && companyId.equals(m.getCompanyId())
                        && active(m.getDeleteFlag(), m.getStatusFlag())).count();
        return memberships == 1;
    }

    /** 精确加载用户引用的角色；不扫描全公司角色目录，不信任失配的 Mapper 返回值。 */
    public EffectiveRoles resolve(String userId, String companyId) {
        return resolve(userId, companyId, true);
    }

    /** 委托授权只依据永久绑定；临时业务权限不可放大为永久或更长的下游授权。 */
    public EffectiveRoles resolvePermanent(String userId, String companyId) {
        return resolve(userId, companyId, false);
    }

    private EffectiveRoles resolve(String userId, String companyId, boolean includeTemporary) {
        if (!isActiveSubject(userId, companyId)) {
            return EffectiveRoles.EMPTY;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Set<String> bound = boundRoleIds(userId, companyId);
        List<SysTempAuthorization> temporary = includeTemporary ? futureOrActive(userId, companyId, now) : List.of();
        Set<String> referenced = new HashSet<>(bound);
        temporary.forEach(auth -> referenced.add(auth.getRoleId()));
        if (referenced.isEmpty()) {
            return EffectiveRoles.EMPTY;
        }
        Map<String, SysRole> available = availableRoles(referenced, companyId);
        RoleWindows windows = applyTemporaryWindows(bound, temporary, available, now);
        List<SysRole> roles = available.values().stream()
                .filter(role -> windows.roleIds().contains(role.getId()))
                .sorted(java.util.Comparator.comparing(SysRole::getId)).toList();
        boolean superAdmin = roles.stream().anyMatch(role ->
                RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(role.getRoleCode()));
        return new EffectiveRoles(superAdmin, roles, windows.nextChangeAt());
    }

    private Set<String> boundRoleIds(String userId, String companyId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId)
                        .eq(SysUserRole::getCompanyId, companyId)
                        .eq(SysUserRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream().filter(binding -> userId.equals(binding.getUserId())
                        && companyId.equals(binding.getCompanyId())
                        && Integer.valueOf(RobotConstants.DELETE_FLAG_ACTIVE).equals(binding.getDeleteFlag())
                        && !isBlank(binding.getRoleId()))
                .map(SysUserRole::getRoleId).collect(Collectors.toSet());
    }

    private Map<String, SysRole> availableRoles(Set<String> referenced, String companyId) {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .in(SysRole::getId, referenced)
                        .eq(SysRole::getCompanyId, companyId)
                        .eq(SysRole::getStatusFlag, 1)
                        .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream().filter(role -> companyId.equals(role.getCompanyId())
                        && referenced.contains(role.getId())
                        && active(role.getDeleteFlag(), role.getStatusFlag()))
                .collect(Collectors.toMap(SysRole::getId, Function.identity(), (first, duplicate) -> first));
    }

    private record RoleWindows(Set<String> roleIds, LocalDateTime nextChangeAt) { }

    private RoleWindows applyTemporaryWindows(Set<String> bound, List<SysTempAuthorization> temporary,
            Map<String, SysRole> available, LocalDateTime now) {
        Set<String> effectiveIds = new HashSet<>(bound);
        LocalDateTime nextChange = null;
        for (SysTempAuthorization auth : temporary) {
            if (!available.containsKey(auth.getRoleId()) || bound.contains(auth.getRoleId())) {
                continue;
            }
            LocalDateTime boundary = auth.getEffectFrom().isAfter(now)
                    ? auth.getEffectFrom() : auth.getEffectTo();
            if (!auth.getEffectFrom().isAfter(now)) {
                effectiveIds.add(auth.getRoleId());
            }
            if (nextChange == null || boundary.isBefore(nextChange)) {
                nextChange = boundary;
            }
        }
        return new RoleWindows(effectiveIds, nextChange);
    }

    private List<SysTempAuthorization> futureOrActive(String userId, String companyId, LocalDateTime now) {
        return tempAuthorizationMapper.selectList(new LambdaQueryWrapper<SysTempAuthorization>()
                        .eq(SysTempAuthorization::getUserId, userId)
                        .eq(SysTempAuthorization::getCompanyId, companyId)
                        .eq(SysTempAuthorization::getStatusFlag, 1)
                        .eq(SysTempAuthorization::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                        .gt(SysTempAuthorization::getEffectTo, now))
                .stream().filter(auth -> userId.equals(auth.getUserId())
                        && companyId.equals(auth.getCompanyId())
                        && active(auth.getDeleteFlag(), auth.getStatusFlag())
                        && !isBlank(auth.getRoleId()) && auth.getEffectFrom() != null
                        && auth.getEffectTo() != null && auth.getEffectFrom().isBefore(auth.getEffectTo())
                        && auth.getEffectTo().isAfter(now)).toList();
    }

    private static boolean active(Integer deleted, Integer status) {
        return Integer.valueOf(RobotConstants.DELETE_FLAG_ACTIVE).equals(deleted)
                && Integer.valueOf(1).equals(status);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
