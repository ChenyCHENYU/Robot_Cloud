package com.robot.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

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
    private final ActiveCompanySubject subjects;
    private final Clock clock;

    /**
     * 注入当前身份事实与时钟；授权边界不信任会话旧快照。
     *
     * @param roleMapper 公司角色事实Mapper
     * @param userRoleMapper 永久角色绑定Mapper
     * @param tempAuthorizationMapper 临时角色授权Mapper
     * @param subjects 当前身份事实核验协作者
     * @param clock 业务有效期时钟
     */
    public EffectiveRoleResolver(
            SysRoleMapper roleMapper,
            SysUserRoleMapper userRoleMapper,
            SysTempAuthorizationMapper tempAuthorizationMapper,
            ActiveCompanySubject subjects,
            Clock clock) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.tempAuthorizationMapper = tempAuthorizationMapper;
        this.subjects = subjects;
        this.clock = clock;
    }

    /**
     * nextChangeAt 是下一临时角色生效/到期边界；缓存不得跨越该时刻。
     *
     * @author Robot Cloud
     * @since 1.0.0-SNAPSHOT
     * @param superAdmin 永久或有效角色是否含公司超管
     * @param roles 当前有效角色集合
     * @param nextChangeAt 下一临时授权生效/到期边界
     */
    public record EffectiveRoles(
            boolean superAdmin, List<SysRole> roles, LocalDateTime nextChangeAt) {
        public static final EffectiveRoles EMPTY = new EffectiveRoles(false, List.of(), null);

        /** 初始化当前用例的明确协作依赖或兼容值元组。 */
        public EffectiveRoles {
            roles = List.copyOf(roles);
        }

        /**
         * 初始化当前用例的明确协作依赖或兼容值元组。
         *
         * @param superAdmin 永久或有效角色是否含公司超管
         * @param roles 当前有效角色集合
         */
        public EffectiveRoles(boolean superAdmin, List<SysRole> roles) {
            this(superAdmin, roles, null);
        }

        /**
         * 判断有效角色集合是否为空。
         *
         * @return 校验是否通过
         */
        public boolean isEmpty() {
            return roles.isEmpty();
        }

        /**
         * 返回当前有效角色ID的不可变集合。
         *
         * @return 当前事实派生的结果
         */
        public Set<String> roleIds() {
            return roles.stream().map(SysRole::getId).collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * 缓存命中和角色解析必须复验当前账号、成员、公司、租户。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     * @return 校验是否通过
     */
    public boolean isActiveSubject(String userId, String companyId) {
        return subjects.isActive(userId, companyId);
    }

    /**
     * 精确加载用户引用的角色；不扫描全公司角色目录，不信任失配的 Mapper 返回值。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     * @return 当前事实派生的结果
     */
    public EffectiveRoles resolve(String userId, String companyId) {
        return resolve(userId, companyId, true);
    }

    /**
     * 委托授权只依据永久绑定；临时业务权限不可放大为永久或更长的下游授权。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     * @return 当前事实派生的结果
     */
    public EffectiveRoles resolvePermanent(String userId, String companyId) {
        return resolve(userId, companyId, false);
    }

    private EffectiveRoles resolve(String userId, String companyId, boolean includeTemporary) {
        if (!isActiveSubject(userId, companyId)) {
            return EffectiveRoles.EMPTY;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Set<String> bound = boundRoleIds(userId, companyId);
        List<SysTempAuthorization> temporary =
                includeTemporary ? futureOrActive(userId, companyId, now) : List.of();
        Set<String> referenced = new HashSet<>(bound);
        temporary.forEach(auth -> referenced.add(auth.getRoleId()));
        if (referenced.isEmpty()) {
            return EffectiveRoles.EMPTY;
        }
        Map<String, SysRole> available = availableRoles(referenced, companyId);
        RoleWindows windows = applyTemporaryWindows(bound, temporary, available, now);
        List<SysRole> roles =
                available.values().stream()
                        .filter(role -> windows.roleIds().contains(role.getId()))
                        .sorted(java.util.Comparator.comparing(SysRole::getId))
                        .toList();
        boolean superAdmin =
                roles.stream()
                        .anyMatch(
                                role ->
                                        RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(
                                                role.getRoleCode()));
        return new EffectiveRoles(superAdmin, roles, windows.nextChangeAt());
    }

    private Set<String> boundRoleIds(String userId, String companyId) {
        return userRoleMapper
                .selectList(
                        new LambdaQueryWrapper<SysUserRole>()
                                .eq(SysUserRole::getUserId, userId)
                                .eq(SysUserRole::getCompanyId, companyId)
                                .eq(SysUserRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream()
                .filter(
                        binding ->
                                userId.equals(binding.getUserId())
                                        && companyId.equals(binding.getCompanyId())
                                        && RobotConstants.DELETE_FLAG_ACTIVE.equals(
                                                binding.getDeleteFlag())
                                        && !isBlank(binding.getRoleId()))
                .map(SysUserRole::getRoleId)
                .collect(Collectors.toSet());
    }

    private Map<String, SysRole> availableRoles(Set<String> referenced, String companyId) {
        return roleMapper
                .selectList(
                        new LambdaQueryWrapper<SysRole>()
                                .in(SysRole::getId, referenced)
                                .eq(SysRole::getCompanyId, companyId)
                                .eq(SysRole::getStatusFlag, 1)
                                .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream()
                .filter(
                        role ->
                                companyId.equals(role.getCompanyId())
                                        && referenced.contains(role.getId())
                                        && active(role.getDeleteFlag(), role.getStatusFlag()))
                .collect(
                        Collectors.toMap(
                                SysRole::getId, Function.identity(), (first, duplicate) -> first));
    }

    private record RoleWindows(Set<String> roleIds, LocalDateTime nextChangeAt) {}

    private RoleWindows applyTemporaryWindows(
            Set<String> bound,
            List<SysTempAuthorization> temporary,
            Map<String, SysRole> available,
            LocalDateTime now) {
        Set<String> effectiveIds = new HashSet<>(bound);
        LocalDateTime nextChange = null;
        for (SysTempAuthorization auth : temporary) {
            if (!available.containsKey(auth.getRoleId()) || bound.contains(auth.getRoleId())) {
                continue;
            }
            LocalDateTime boundary =
                    auth.getEffectFrom().isAfter(now) ? auth.getEffectFrom() : auth.getEffectTo();
            if (!auth.getEffectFrom().isAfter(now)) {
                effectiveIds.add(auth.getRoleId());
            }
            if (nextChange == null || boundary.isBefore(nextChange)) {
                nextChange = boundary;
            }
        }
        return new RoleWindows(effectiveIds, nextChange);
    }

    private List<SysTempAuthorization> futureOrActive(
            String userId, String companyId, LocalDateTime now) {
        return tempAuthorizationMapper
                .selectList(
                        new LambdaQueryWrapper<SysTempAuthorization>()
                                .eq(SysTempAuthorization::getUserId, userId)
                                .eq(SysTempAuthorization::getCompanyId, companyId)
                                .eq(SysTempAuthorization::getStatusFlag, 1)
                                .eq(
                                        SysTempAuthorization::getDeleteFlag,
                                        RobotConstants.DELETE_FLAG_ACTIVE)
                                .gt(SysTempAuthorization::getEffectTo, now))
                .stream()
                .filter(
                        auth ->
                                userId.equals(auth.getUserId())
                                        && companyId.equals(auth.getCompanyId())
                                        && active(auth.getDeleteFlag(), auth.getStatusFlag())
                                        && !isBlank(auth.getRoleId())
                                        && auth.getEffectFrom() != null
                                        && auth.getEffectTo() != null
                                        && auth.getEffectFrom().isBefore(auth.getEffectTo())
                                        && auth.getEffectTo().isAfter(now))
                .toList();
    }

    private static boolean active(Integer deleted, Integer status) {
        return RobotConstants.DELETE_FLAG_ACTIVE.equals(deleted)
                && Integer.valueOf(1).equals(status);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
