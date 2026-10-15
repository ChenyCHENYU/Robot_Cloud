package com.robot.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 所有角色/资源授予入口共享的操作者事实与委托上界；不信任会话超管快照。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Component
@RequiredArgsConstructor
public class PermissionGrantPolicy {
    private static final int ALL_SCOPE_RANK = 3;

    private final EffectiveRoleResolver resolver;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleButtonMapper roleButtonMapper;

    /**
     * 当前公司的操作者与永久角色事实，用于所有管理写入的委托上界校验。
     *
     * @author Robot Cloud
     * @since 1.0.0-SNAPSHOT
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     * @param roles 当前有效角色集合
     */
    public record Operator(
            String userId, String companyId, EffectiveRoleResolver.EffectiveRoles roles) {}

    /**
     * 要求登录账号、公司及成员当前有效，返回可信公司作用域。
     *
     * @return 当前事实派生的结果
     */
    public String requireCompany() {
        String userId = AuthUtil.getLoginUserId();
        String companyId = AuthUtil.getLoginCompanyId();
        ServiceAssert.notBlank(userId, ResultCode.UNAUTHORIZED);
        ServiceAssert.notBlank(companyId, ResultCode.FORBIDDEN, "缺少公司上下文");
        ServiceAssert.isTrue(
                resolver.isActiveSubject(userId, companyId), ResultCode.FORBIDDEN, "当前账户或公司成员已失效");
        return companyId;
    }

    /**
     * 从当前永久有效角色解析委托上界，不能用临时角色扩大授权。
     *
     * @return 当前事实派生的结果
     */
    public Operator requireOperator() {
        String userId = AuthUtil.getLoginUserId();
        String companyId = AuthUtil.getLoginCompanyId();
        ServiceAssert.notBlank(userId, ResultCode.UNAUTHORIZED);
        ServiceAssert.notBlank(companyId, ResultCode.FORBIDDEN, "缺少公司上下文");
        EffectiveRoleResolver.EffectiveRoles effective =
                resolver.resolvePermanent(userId, companyId);
        ServiceAssert.isTrue(
                effective != null && !effective.isEmpty(),
                ResultCode.FORBIDDEN,
                "授权与成员管理必须具有有效的永久角色；临时角色不能继续委托授权");
        return new Operator(userId, companyId, effective);
    }

    /**
     * 阻止普通操作者编辑本人永久或临时持有角色。
     *
     * @param operator 永久授权事实校验后的操作者
     * @param roleId 公司角色ID
     */
    public void assertNotSelfRoleEdit(Operator operator, String roleId) {
        if (operator.roles().superAdmin()) {
            return;
        }
        ServiceAssert.isTrue(
                !operator.roles().roleIds().contains(roleId),
                ResultCode.FORBIDDEN,
                "禁止修改自己持有角色的权限");
        var effective = resolver.resolve(operator.userId(), operator.companyId());
        ServiceAssert.isTrue(
                effective != null && !effective.isEmpty() && !effective.roleIds().contains(roleId),
                ResultCode.FORBIDDEN,
                "禁止修改自己持有角色的权限");
    }

    /**
     * 校验公司超管角色编码变更只能由永久超管执行。
     *
     * @param operator 永久授权事实校验后的操作者
     * @param previous 修改前角色事实；创建时可为空
     * @param requestedCode 请求写入的角色编码
     */
    public void assertRoleCode(Operator operator, SysRole previous, String requestedCode) {
        boolean wasSuper =
                previous != null
                        && RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(previous.getRoleCode());
        boolean becomesSuper = RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(requestedCode);
        ServiceAssert.isTrue(!wasSuper || becomesSuper, ResultCode.FORBIDDEN, "超级管理员角色编码禁止修改");
        ServiceAssert.isTrue(
                !becomesSuper || operator.roles().superAdmin(),
                ResultCode.FORBIDDEN,
                "只有当前有效超级管理员可以创建或修改超级管理员角色");
    }

    /**
     * 校验数据范围模型及永久授权上界，未实现custom配置不得新增。
     *
     * @param operator 永久授权事实校验后的操作者
     * @param requestedScope 请求写入的数据范围
     */
    public void assertScope(Operator operator, String requestedScope) {
        int requested = scopeRank(requestedScope);
        ServiceAssert.isTrue(
                requested >= 0,
                ResultCode.PARAM_INVALID,
                "数据范围只支持self/department/department_below/all；custom尚无配置模型");
        int own =
                operator.roles().roles().stream()
                        .map(SysRole::getDataScope)
                        .mapToInt(PermissionGrantPolicy::scopeRank)
                        .max()
                        .orElse(0);
        ServiceAssert.isTrue(
                operator.roles().superAdmin() || requested <= own,
                ResultCode.FORBIDDEN,
                "禁止授予超出自身数据范围的角色");
    }

    /**
     * 校验待委托的有效公司角色及其资源/数据范围上界。
     *
     * @param operator 永久授权事实校验后的操作者
     * @param requested 请求委托或管理的角色集合
     */
    public void assertPermissions(Operator operator, Set<String> requested) {
        if (operator.roles().superAdmin() || requested.isEmpty()) {
            return;
        }
        ServiceAssert.isTrue(
                resources(operator.roles().roleIds()).containsAll(requested),
                ResultCode.FORBIDDEN,
                "禁止授予超出自身权限范围的资源");
    }

    /**
     * 校验可管理的公司角色，包括停用角色的原有授权上界。
     *
     * @param operator 永久授权事实校验后的操作者
     * @param roles 当前有效角色集合
     */
    public void assertRoles(Operator operator, List<SysRole> roles) {
        roles.forEach(
                role -> {
                    ServiceAssert.isTrue(
                            operator.companyId().equals(role.getCompanyId())
                                    && Integer.valueOf(1).equals(role.getStatusFlag())
                                    && RobotConstants.DELETE_FLAG_ACTIVE.equals(
                                            role.getDeleteFlag()),
                            ResultCode.FORBIDDEN,
                            "角色未启用或不属于当前公司");
                    assertRoleCode(operator, null, role.getRoleCode());
                    assertScope(operator, role.getDataScope());
                });
        if (!operator.roles().superAdmin()) {
            Set<String> targetIds =
                    roles.stream().map(SysRole::getId).collect(java.util.stream.Collectors.toSet());
            assertPermissions(operator, resources(targetIds));
        }
    }

    /**
     * 管理目标包括停用角色和未来临时授权；防止先停用再绕过上界重新启用。
     *
     * @param operator 永久授权事实校验后的操作者
     * @param roles 当前有效角色集合
     */
    public void assertManagedRoles(Operator operator, List<SysRole> roles) {
        if (operator.roles().superAdmin()) {
            return;
        }
        roles.forEach(
                role -> {
                    ServiceAssert.isTrue(
                            operator.companyId().equals(role.getCompanyId())
                                    && RobotConstants.DELETE_FLAG_ACTIVE.equals(
                                            role.getDeleteFlag()),
                            ResultCode.FORBIDDEN,
                            "目标成员绑定角色不属于当前公司");
                    assertRoleCode(operator, null, role.getRoleCode());
                    assertScope(operator, role.getDataScope());
                });
        assertPermissions(
                operator,
                resources(
                        roles.stream()
                                .map(SysRole::getId)
                                .collect(java.util.stream.Collectors.toSet())));
    }

    private Set<String> resources(Set<String> roleIds) {
        Set<String> ids = new HashSet<>();
        if (roleIds.isEmpty()) {
            return ids;
        }
        roleMenuMapper
                .selectList(
                        new LambdaQueryWrapper<SysRoleMenu>()
                                .in(SysRoleMenu::getRoleId, roleIds)
                                .eq(SysRoleMenu::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream()
                .filter(
                        binding ->
                                roleIds.contains(binding.getRoleId())
                                        && RobotConstants.DELETE_FLAG_ACTIVE.equals(
                                                binding.getDeleteFlag()))
                .forEach(binding -> ids.add(binding.getMenuId()));
        roleButtonMapper
                .selectList(
                        new LambdaQueryWrapper<SysRoleButton>()
                                .in(SysRoleButton::getRoleId, roleIds)
                                .eq(
                                        SysRoleButton::getDeleteFlag,
                                        RobotConstants.DELETE_FLAG_ACTIVE))
                .stream()
                .filter(
                        binding ->
                                roleIds.contains(binding.getRoleId())
                                        && RobotConstants.DELETE_FLAG_ACTIVE.equals(
                                                binding.getDeleteFlag()))
                .forEach(binding -> ids.add(binding.getButtonId()));
        return ids;
    }

    private static int scopeRank(String scope) {
        if (scope == null || "self".equals(scope)) {
            return 0;
        }
        return switch (scope) {
            case "department" -> 1;
            case "department_below" -> 2;
            case "all" -> ALL_SCOPE_RANK;
            default -> -1;
        };
    }
}
