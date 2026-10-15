package com.robot.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.user.UserPageQuery;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.api.vo.user.UserVO;
import com.robot.system.permission.service.DataScopeService;
import com.robot.system.permission.service.DataScopeService.UserReadScope;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 公司成员读用例；范围过滤、分页、成员当前事实与资料投影集中在同一读侧。 */
@Service
@RequiredArgsConstructor
public class UserQueryService {
    private final SysUserMapper users;
    private final SysUserCompanyMapper userCompanyMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final PermissionGrantPolicy grantPolicy;
    private final DataScopeService dataScopeService;

    /**
     * 公司成员过滤与用户数据范围共同参与分页SQL，返回值再次校验当前成员关系。
     *
     * @param query 分页与筛选条件
     * @return 当前事实派生的结果
     */
    public PageResult<UserVO> queryPage(UserPageQuery query) {
        String companyId = grantPolicy.requireCompany();
        UserWriteValidation.validateStatus(query.getStatus());
        LambdaQueryWrapper<SysUser> wrapper =
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                        .exists(
                                "SELECT 1 FROM sys_user_company uc WHERE uc.user_id = sys_user.id"
                                        + " AND uc.company_id = {0} AND uc.delete_flag = 1",
                                companyId)
                        .exists(
                                query.getStatus() != null,
                                "SELECT 1 FROM sys_user_company ms WHERE ms.user_id = sys_user.id"
                                        + " AND ms.company_id = {0} AND ms.delete_flag = 1 AND"
                                        + " ms.status_flag = {1}",
                                companyId,
                                query.getStatus())
                        .exists(
                                query.getDeptId() != null && !query.getDeptId().isBlank(),
                                "SELECT 1 FROM sys_user_company md WHERE md.user_id = sys_user.id"
                                        + " AND md.company_id = {0} AND md.delete_flag = 1 AND"
                                        + " md.department_id = {1}",
                                companyId,
                                query.getDeptId())
                        .and(
                                query.getKeyword() != null && !query.getKeyword().isBlank(),
                                w ->
                                        w.like(SysUser::getUsername, query.getKeyword())
                                                .or()
                                                .like(SysUser::getNickname, query.getKeyword())
                                                .or()
                                                .like(SysUser::getEmail, query.getKeyword()))
                        .orderByDesc(SysUser::getCreateDateTime, SysUser::getId);
        UserReadScope scope = dataScopeService.userReadScope(AuthUtil.getLoginUserId(), companyId);
        applyReadScope(wrapper, scope);
        Page<SysUser> result =
                users.selectPage(new Page<>(query.pageValue(), query.pageSizeValue()), wrapper);
        return PageResult.of(
                toVos(result.getRecords(), companyId, scope),
                result.getTotal(),
                query.pageValue(),
                query.pageSizeValue());
    }

    /**
     * 详情沿用列表读取范围；查询期间成员移出时返回业务拒绝，避免无成员资料泄露。
     *
     * @param id 记录ID
     * @return 审计详情
     */
    public UserVO getDetail(String id) {
        String companyId = grantPolicy.requireCompany();
        requireMembership(id, companyId);
        UserReadScope scope = dataScopeService.userReadScope(AuthUtil.getLoginUserId(), companyId);
        LambdaQueryWrapper<SysUser> query =
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getId, id)
                        .eq(SysUser::getDeleteFlag, 1);
        applyReadScope(query, scope);
        SysUser user =
                ServiceAssert.notNull(users.selectOne(query), ResultCode.FORBIDDEN, "用户不在当前数据范围内");
        ServiceAssert.isTrue(id.equals(user.getId()), ResultCode.FORBIDDEN);
        List<UserVO> views = toVos(List.of(user), companyId, scope);
        ServiceAssert.isTrue(!views.isEmpty(), ResultCode.FORBIDDEN, "用户已不属于当前公司成员");
        return views.get(0);
    }

    private static void applyReadScope(LambdaQueryWrapper<SysUser> query, UserReadScope scope) {
        if (!scope.all()) {
            query.and(
                    w -> {
                        w.eq(SysUser::getId, scope.userId());
                        if (!scope.departmentIds().isEmpty()) {
                            List<Object> arguments = new java.util.ArrayList<>();
                            arguments.add(scope.companyId());
                            String placeholders =
                                    scope.departmentIds().stream()
                                            .sorted()
                                            .map(
                                                    id -> {
                                                        arguments.add(id);
                                                        return "{" + (arguments.size() - 1) + "}";
                                                    })
                                            .collect(Collectors.joining(","));
                            w.or().exists(
                                            "SELECT 1 FROM sys_user_company ds WHERE ds.user_id ="
                                                    + " sys_user.id AND ds.company_id = {0} AND"
                                                    + " ds.delete_flag = 1 AND ds.department_id IN ("
                                                    + placeholders
                                                    + ")",
                                            arguments.toArray());
                        }
                    });
        }
    }

    private SysUserCompany requireMembership(String userId, String companyId) {
        ServiceAssert.notBlank(userId, ResultCode.PARAM_INVALID);
        List<SysUserCompany> memberships =
                userCompanyMapper
                        .selectList(
                                new LambdaQueryWrapper<SysUserCompany>()
                                        .eq(SysUserCompany::getUserId, userId)
                                        .eq(SysUserCompany::getCompanyId, companyId)
                                        .eq(SysUserCompany::getDeleteFlag, 1))
                        .stream()
                        .filter(
                                m ->
                                        userId.equals(m.getUserId())
                                                && companyId.equals(m.getCompanyId())
                                                && Integer.valueOf(1).equals(m.getDeleteFlag()))
                        .toList();
        ServiceAssert.isTrue(memberships.size() == 1, ResultCode.FORBIDDEN, "目标用户不是当前公司的唯一有效成员");
        return memberships.get(0);
    }

    private List<UserVO> toVos(List<SysUser> users, String companyId, UserReadScope scope) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<String> ids = users.stream().map(SysUser::getId).toList();
        Map<String, SysUserCompany> memberships = membershipsByUser(ids, companyId);
        List<SysUserRole> bindings = currentRoleBindings(ids, companyId);
        Map<String, SysRole> roles = currentRoles(bindings, companyId);
        return users.stream()
                .filter(
                        user ->
                                memberships.containsKey(user.getId())
                                        && scope.permits(
                                                user,
                                                memberships.get(user.getId()).getDepartmentId()))
                .map(user -> toView(user, memberships.get(user.getId()), bindings, roles))
                .toList();
    }

    private Map<String, SysUserCompany> membershipsByUser(List<String> ids, String companyId) {
        return userCompanyMapper
                .selectList(
                        new LambdaQueryWrapper<SysUserCompany>()
                                .in(SysUserCompany::getUserId, ids)
                                .eq(SysUserCompany::getCompanyId, companyId)
                                .eq(SysUserCompany::getDeleteFlag, 1))
                .stream()
                .filter(
                        member ->
                                ids.contains(member.getUserId())
                                        && companyId.equals(member.getCompanyId())
                                        && Integer.valueOf(1).equals(member.getDeleteFlag()))
                .collect(Collectors.toMap(SysUserCompany::getUserId, Function.identity()));
    }

    private List<SysUserRole> currentRoleBindings(List<String> ids, String companyId) {
        return userRoleMapper
                .selectList(
                        new LambdaQueryWrapper<SysUserRole>()
                                .in(SysUserRole::getUserId, ids)
                                .eq(SysUserRole::getCompanyId, companyId)
                                .eq(SysUserRole::getDeleteFlag, 1))
                .stream()
                .filter(
                        binding ->
                                ids.contains(binding.getUserId())
                                        && companyId.equals(binding.getCompanyId())
                                        && Integer.valueOf(1).equals(binding.getDeleteFlag()))
                .toList();
    }

    private Map<String, SysRole> currentRoles(List<SysUserRole> bindings, String companyId) {
        return bindings.isEmpty()
                ? Map.of()
                : roleMapper
                        .selectBatchIds(
                                bindings.stream().map(SysUserRole::getRoleId).distinct().toList())
                        .stream()
                        .filter(
                                role ->
                                        companyId.equals(role.getCompanyId())
                                                && Integer.valueOf(1).equals(role.getDeleteFlag()))
                        .collect(Collectors.toMap(SysRole::getId, Function.identity()));
    }

    private UserVO toView(
            SysUser user,
            SysUserCompany membership,
            List<SysUserRole> bindings,
            Map<String, SysRole> roles) {
        List<String> roleIds =
                bindings.stream()
                        .filter(binding -> user.getId().equals(binding.getUserId()))
                        .map(SysUserRole::getRoleId)
                        .filter(roles::containsKey)
                        .distinct()
                        .toList();
        UserVO view =
                UserVO.from(user, roleIds.stream().map(id -> roles.get(id).getRoleName()).toList());
        view.setRoleIds(roleIds);
        view.setMembershipRevision(membership.getRevision());
        view.setDepartmentId(membership.getDepartmentId());
        view.setStatus(membership.getStatusFlag());
        view.setStatusFlag(membership.getStatusFlag());
        return view;
    }

    private void requireOwnUser(String id) {
        ServiceAssert.notBlank(AuthUtil.getLoginUserId(), ResultCode.UNAUTHORIZED);
        ServiceAssert.isTrue(AuthUtil.getLoginUserId().equals(id), ResultCode.FORBIDDEN);
        grantPolicy.requireCompany();
    }

    /**
     * 自助资料中的部门来自当前成员，不能回读已退役的全局账号字段。
     *
     * @param userId 平台账号ID
     * @return 当前事实派生的结果
     */
    public String currentMemberDepartment(String userId) {
        requireOwnUser(userId);
        return requireMembership(userId, AuthUtil.getLoginCompanyId()).getDepartmentId();
    }
}
