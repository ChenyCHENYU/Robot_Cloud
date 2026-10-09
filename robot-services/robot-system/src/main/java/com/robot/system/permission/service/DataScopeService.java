package com.robot.system.permission.service;

import com.robot.system.api.constant.SystemConstants;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysDepartment;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.dept.mapper.SysDepartmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.system.api.vo.permission.DataScopeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.stream.Collectors;

/**
 * 数据权限：按「当前公司角色 DATA_SCOPE + 生效中的临时授权」聚合，
 * 输出前端 GetDataPermissionResponse 契约的 module/scope 结构。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Service
@RequiredArgsConstructor
public class DataScopeService {

    private final EffectiveRoleResolver effectiveRoleResolver;
    private final SysUserMapper userMapper;
    private final SysDepartmentMapper departmentMapper;

    /**
     * 仅供用户读侧使用；不等同于通用业务数据权限引擎。
     *
     * @author Robot Cloud
     * @since 1.0.0-SNAPSHOT
     */
    public record UserReadScope(String userId, String companyId, boolean all, Set<String> departmentIds) {
        public UserReadScope {
            departmentIds = Set.copyOf(departmentIds);
        }

        /** 判断已归属当前公司的目标是否落在用户读取范围，调用方另行验证公司成员。 */
        public boolean permits(SysUser target) {
            return target != null && (all || userId.equals(target.getId())
                    || target.getDepartmentId() != null && departmentIds.contains(target.getDepartmentId()));
        }
    }

    /** 解析用户读取范围：先检查有效角色，再验证公司部门与祖先链，非法部门退回本人。 */
    public UserReadScope userReadScope(String userId, String companyId) {
        ServiceAssert.notBlank(userId, ResultCode.UNAUTHORIZED);
        ServiceAssert.notBlank(companyId, ResultCode.FORBIDDEN);
        var effective = effectiveRoleResolver.resolve(userId, companyId);
        ServiceAssert.isTrue(effective != null && !effective.isEmpty(), ResultCode.FORBIDDEN);
        Set<String> scopes = configuredScopes(effective.roles());
        if (effective.superAdmin() || scopes.contains(SystemConstants.DATA_SCOPE_ALL)) {
            return new UserReadScope(userId, companyId, true, Set.of());
        }
        assertSupportedUserScopes(scopes);
        String scope = strongest(scopes);
        if (SystemConstants.DATA_SCOPE_SELF.equals(scope)) {
            return new UserReadScope(userId, companyId, false, Set.of());
        }
        SysDepartment own = ownActiveDepartment(userId, companyId);
        if (own == null) {
            return new UserReadScope(userId, companyId, false, Set.of());
        }
        List<SysDepartment> departments = activeDepartments(companyId);
        Map<String, SysDepartment> byId = departments.stream().collect(Collectors.toMap(SysDepartment::getId,
                java.util.function.Function.identity(), (first, duplicate) -> first));
        if (!validDepartmentPath(own.getId(), byId)) {
            return new UserReadScope(userId, companyId, false, Set.of());
        }
        if (SystemConstants.DATA_SCOPE_DEPARTMENT.equals(scope)) {
            return new UserReadScope(userId, companyId, false, Set.of(own.getId()));
        }
        return new UserReadScope(userId, companyId, false, descendantDepartmentIds(own.getId(), departments));
    }

    private static Set<String> configuredScopes(List<SysRole> roles) {
        return roles.stream().map(role -> role.getDataScope() == null
                ? SystemConstants.DATA_SCOPE_SELF : role.getDataScope()).collect(Collectors.toSet());
    }

    private static void assertSupportedUserScopes(Set<String> scopes) {
        ServiceAssert.isTrue(scopes.stream().allMatch(scope -> Set.of(SystemConstants.DATA_SCOPE_SELF,
                        SystemConstants.DATA_SCOPE_DEPARTMENT, SystemConstants.DATA_SCOPE_DEPARTMENT_BELOW).contains(scope)),
                ResultCode.FORBIDDEN, "custom或未知数据范围尚未实现，拒绝用户读请求");
    }

    private SysDepartment ownActiveDepartment(String userId, String companyId) {
        SysUser user = userMapper.selectById(userId);
        SysDepartment own = user == null || !userId.equals(user.getId()) || user.getDepartmentId() == null
                ? null : departmentMapper.selectById(user.getDepartmentId());
        return !validDepartment(own, companyId) || !user.getDepartmentId().equals(own.getId()) ? null : own;
    }

    private List<SysDepartment> activeDepartments(String companyId) {
        return departmentMapper.selectList(new LambdaQueryWrapper<SysDepartment>()
                        .eq(SysDepartment::getCompanyId, companyId).eq(SysDepartment::getStatusFlag, 1)
                        .eq(SysDepartment::getDeleteFlag, 1)).stream()
                .filter(dept -> validDepartment(dept, companyId)).toList();
    }

    private static Set<String> descendantDepartmentIds(String departmentId, List<SysDepartment> departments) {
        Map<String, List<SysDepartment>> byParent = departments.stream()
                .filter(dept -> dept.getParentId() != null && !dept.getParentId().isBlank())
                .collect(Collectors.groupingBy(SysDepartment::getParentId));
        Set<String> descendants = new HashSet<>();
        descendants.add(departmentId);
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(departmentId);
        while (!pending.isEmpty()) {
            for (SysDepartment dept : byParent.getOrDefault(pending.removeFirst(), List.of())) {
                if (descendants.add(dept.getId())) {
                    pending.addLast(dept.getId());
                }
            }
        }
        return descendants;
    }

    private static boolean validDepartment(SysDepartment department, String companyId) {
        return department != null && companyId.equals(department.getCompanyId())
                && department.getId() != null && Integer.valueOf(1).equals(department.getDeleteFlag())
                && Integer.valueOf(1).equals(department.getStatusFlag());
    }

    private static boolean validDepartmentPath(String id, Map<String, SysDepartment> departments) {
        Set<String> visited = new HashSet<>();
        String current = id;
        while (current != null && !current.isBlank()) {
            SysDepartment department = departments.get(current);
            if (department == null || !visited.add(current)) {
                return false;
            }
            current = department.getParentId();
        }
        return true;
    }

    /** 返回兼容治理页的范围摘要，不代表其他业务域已接入用户读侧筛选。 */
    public List<DataScopeVO> scopesOfUser(String userId, String companyId) {
        EffectiveRoleResolver.EffectiveRoles effective =
                effectiveRoleResolver.resolve(userId, companyId);
        if (effective.isEmpty()) {
            return List.of();
        }
        Set<String> scopes = effective.roles().stream()
                .map(role -> role.getDataScope() == null
                        ? SystemConstants.DATA_SCOPE_SELF : role.getDataScope())
                .collect(Collectors.toSet());
        // 仅为兼容现有范围展示合同；custom集合模型与行级查询执行仍需独立实现。
        return List.of(new DataScopeVO("*", strongest(scopes)));
    }
    private String strongest(Set<String> scopes) {
        if (scopes.contains(SystemConstants.DATA_SCOPE_ALL)) {
            return SystemConstants.DATA_SCOPE_ALL;
        }
        if (scopes.contains(SystemConstants.DATA_SCOPE_CUSTOM)) {
            return SystemConstants.DATA_SCOPE_CUSTOM;
        }
        if (scopes.contains(SystemConstants.DATA_SCOPE_DEPARTMENT_BELOW)) {
            return SystemConstants.DATA_SCOPE_DEPARTMENT_BELOW;
        }
        if (scopes.contains(SystemConstants.DATA_SCOPE_DEPARTMENT)) {
            return SystemConstants.DATA_SCOPE_DEPARTMENT;
        }
        return SystemConstants.DATA_SCOPE_SELF;
    }
}
