package com.robot.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.permission.service.PermissionGrantPolicy.Operator;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 成员授权聚合的永久委托上界与精确绑定；由外层公司协调锁和事务保护。 */
@Component
@RequiredArgsConstructor
public class MemberRoleCommandSupport {
    private static final int MAX_ROLE_ID_LENGTH = 64;

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;
    private final PermissionGrantPolicy grantPolicy;
    private final Clock clock;

    /**
     * 管理成员需覆盖其永久和未结束临时角色；普通操作者不得使用管理命令修改本人。
     *
     * @param operator 永久授权事实校验后的操作者
     * @param targetId 目标成员账号ID
     */
    public void assertCanManageMember(Operator operator, String targetId) {
        ServiceAssert.isTrue(
                operator.roles().superAdmin() || !operator.userId().equals(targetId),
                ResultCode.FORBIDDEN,
                "本人资料和密码请使用需要原密码校验的本人入口");
        if (operator.roles().superAdmin()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Set<String> roleIds = memberBoundRoleIds(targetId, operator.companyId());
        addUnexpiredMemberRoleIds(roleIds, targetId, operator.companyId(), now);
        if (roleIds.isEmpty()) {
            return;
        }
        List<SysRole> roles = managedMemberRoles(roleIds, operator.companyId());
        grantPolicy.assertManagedRoles(operator, roles);
    }

    private Set<String> memberBoundRoleIds(String targetId, String companyId) {
        return userRoleMapper
                .selectList(
                        new LambdaQueryWrapper<SysUserRole>()
                                .eq(SysUserRole::getUserId, targetId)
                                .eq(SysUserRole::getCompanyId, companyId)
                                .eq(SysUserRole::getDeleteFlag, 1))
                .stream()
                .filter(
                        binding ->
                                targetId.equals(binding.getUserId())
                                        && companyId.equals(binding.getCompanyId())
                                        && Integer.valueOf(1).equals(binding.getDeleteFlag()))
                .map(SysUserRole::getRoleId)
                .collect(Collectors.toSet());
    }

    private void addUnexpiredMemberRoleIds(
            Set<String> roleIds, String targetId, String companyId, LocalDateTime now) {
        tempAuthorizationMapper
                .selectList(
                        new LambdaQueryWrapper<SysTempAuthorization>()
                                .eq(SysTempAuthorization::getUserId, targetId)
                                .eq(SysTempAuthorization::getCompanyId, companyId)
                                .eq(SysTempAuthorization::getStatusFlag, 1)
                                .eq(SysTempAuthorization::getDeleteFlag, 1)
                                .gt(SysTempAuthorization::getEffectTo, now))
                .stream()
                .filter(
                        temp ->
                                targetId.equals(temp.getUserId())
                                        && companyId.equals(temp.getCompanyId())
                                        && Integer.valueOf(1).equals(temp.getStatusFlag())
                                        && Integer.valueOf(1).equals(temp.getDeleteFlag())
                                        && temp.getEffectTo() != null
                                        && temp.getEffectTo().isAfter(now))
                .forEach(temp -> roleIds.add(temp.getRoleId()));
    }

    private List<SysRole> managedMemberRoles(Set<String> roleIds, String companyId) {
        List<SysRole> roles =
                roleMapper
                        .selectList(
                                new LambdaQueryWrapper<SysRole>()
                                        .in(SysRole::getId, roleIds)
                                        .eq(SysRole::getCompanyId, companyId)
                                        .eq(SysRole::getDeleteFlag, 1))
                        .stream()
                        .filter(
                                role ->
                                        roleIds.contains(role.getId())
                                                && companyId.equals(role.getCompanyId())
                                                && Integer.valueOf(1).equals(role.getDeleteFlag()))
                        .toList();
        ServiceAssert.isTrue(
                roles.stream().map(SysRole::getId).distinct().count() == roleIds.size(),
                ResultCode.FORBIDDEN,
                "目标成员存在无效或跨公司角色绑定");
        return roles;
    }

    /**
     * 校验真实有效公司角色及永久委托上界，失败前不产生关联写入。
     *
     * @param operator 永久授权事实校验后的操作者
     * @param ids 待校验的精确资源ID集合
     * @return 当前事实派生的结果
     */
    public List<SysRole> validatedRoles(Operator operator, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        ServiceAssert.isTrue(
                ids.size() <= RobotConstants.BATCH_WRITE_LIMIT
                        && ids.stream()
                                .allMatch(
                                        id ->
                                                id != null
                                                        && !id.isBlank()
                                                        && id.length() <= MAX_ROLE_ID_LENGTH)
                        && new HashSet<>(ids).size() == ids.size(),
                ResultCode.PARAM_INVALID,
                "角色ID必须有效、不重复且在单批上限内");
        List<SysRole> roles =
                roleMapper
                        .selectList(
                                new LambdaQueryWrapper<SysRole>()
                                        .in(SysRole::getId, ids)
                                        .eq(SysRole::getCompanyId, operator.companyId())
                                        .eq(SysRole::getDeleteFlag, 1)
                                        .eq(SysRole::getStatusFlag, 1))
                        .stream()
                        .filter(
                                role ->
                                        ids.contains(role.getId())
                                                && operator.companyId().equals(role.getCompanyId())
                                                && Integer.valueOf(1).equals(role.getDeleteFlag())
                                                && Integer.valueOf(1).equals(role.getStatusFlag()))
                        .toList();
        ServiceAssert.isTrue(
                roles.stream().map(SysRole::getId).distinct().count() == ids.size(),
                ResultCode.FORBIDDEN,
                "包含无效、停用或跨公司角色");
        grantPolicy.assertRoles(operator, roles);
        return roles;
    }

    /**
     * 逐项持久化已验证角色，任一影响行数异常由外层事务整体回滚。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     * @param roles 当前有效角色集合
     */
    // 每条公司成员角色绑定需要独立ID与审计填充；不得复用MP已经插入的实体。
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    public void insertRoles(String userId, String companyId, List<SysRole> roles) {
        for (SysRole role : roles) {
            SysUserRole binding = new SysUserRole();
            binding.setUserId(userId);
            binding.setCompanyId(companyId);
            binding.setRoleId(role.getId());
            ServiceAssert.isTrue(
                    userRoleMapper.insert(binding) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        }
    }

    /**
     * 只撤销当前公司成员的角色绑定，不触及其他公司。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     */
    public void deleteRoles(String userId, String companyId) {
        userRoleMapper.delete(
                new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId)
                        .eq(SysUserRole::getCompanyId, companyId));
    }

    /**
     * 成员移出时撤回本公司全部有效临时角色，不触及其他公司。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     */
    public void revokeTemporaryRoles(String userId, String companyId) {
        SysTempAuthorization revoked = new SysTempAuthorization();
        revoked.setStatusFlag(0);
        tempAuthorizationMapper.update(
                revoked,
                new LambdaQueryWrapper<SysTempAuthorization>()
                        .eq(SysTempAuthorization::getUserId, userId)
                        .eq(SysTempAuthorization::getCompanyId, companyId)
                        .eq(SysTempAuthorization::getDeleteFlag, 1)
                        .eq(SysTempAuthorization::getStatusFlag, 1));
    }
}
