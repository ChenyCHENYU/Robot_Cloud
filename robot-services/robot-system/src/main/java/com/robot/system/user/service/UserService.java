package com.robot.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.user.UserPageQuery;
import com.robot.system.api.dto.user.UserSaveRequest;
import com.robot.system.api.dto.user.UserStatusRequest;
import com.robot.system.api.dto.user.UserPasswordResetRequest;
import com.robot.system.api.entity.SysDepartment;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.api.vo.user.UserVO;
import com.robot.system.dept.mapper.SysDepartmentMapper;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.permission.service.PermissionGrantPolicy.Operator;
import com.robot.system.permission.service.DataScopeService;
import com.robot.system.permission.service.DataScopeService.UserReadScope;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 公司管理员管理公司成员。移出/启停不删除或停用共享平台账户；授权与关联写同事务。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Service
@RequiredArgsConstructor
public class UserService extends ServiceImpl<SysUserMapper, SysUser> {
    public static final String BUILTIN_ADMIN_USERNAME = UserWriteValidation.BUILTIN_ADMIN_USERNAME;

    private final SysUserCompanyMapper userCompanyMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysDepartmentMapper departmentMapper;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;
    private final PermissionGrantPolicy grantPolicy;
    private final BCryptPasswordEncoder passwordEncoder;
    private final PermissionChangeRecorder changeRecorder;
    private final Clock clock;
    private final DataScopeService dataScopeService;

    /** 公司成员过滤与用户数据范围共同参与分页SQL，返回值再次校验当前成员关系。 */
    public PageResult<UserVO> queryPage(UserPageQuery query) {
        String companyId = grantPolicy.requireCompany();
        UserWriteValidation.validateStatus(query.getStatus());
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .exists("SELECT 1 FROM sys_user_company uc WHERE uc.user_id = sys_user.id"
                        + " AND uc.company_id = {0} AND uc.delete_flag = 1", companyId)
                .exists(query.getStatus() != null,
                        "SELECT 1 FROM sys_user_company ms WHERE ms.user_id = sys_user.id"
                        + " AND ms.company_id = {0} AND ms.delete_flag = 1 AND ms.status_flag = {1}",
                        companyId, query.getStatus())
                .eq(query.getDeptId() != null && !query.getDeptId().isBlank(),
                        SysUser::getDepartmentId, query.getDeptId())
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(), w -> w
                        .like(SysUser::getUsername, query.getKeyword())
                        .or().like(SysUser::getNickname, query.getKeyword())
                        .or().like(SysUser::getEmail, query.getKeyword()))
                .orderByDesc(SysUser::getCreateDateTime, SysUser::getId);
        UserReadScope scope = dataScopeService.userReadScope(AuthUtil.getLoginUserId(), companyId);
        applyReadScope(wrapper, scope);
        Page<SysUser> result = page(new Page<>(query.pageValue(), query.pageSizeValue()), wrapper);
        return PageResult.of(toVos(result.getRecords().stream().filter(scope::permits).toList(), companyId), result.getTotal(),
                query.pageValue(), query.pageSizeValue());
    }

    /** 详情沿用列表读取范围；查询期间成员移出时返回业务拒绝，避免无成员资料泄露。 */
    public UserVO getDetail(String id) {
        String companyId = grantPolicy.requireCompany();
        requireMembership(id, companyId);
        UserReadScope scope = dataScopeService.userReadScope(AuthUtil.getLoginUserId(), companyId);
        LambdaQueryWrapper<SysUser> query = new LambdaQueryWrapper<SysUser>().eq(SysUser::getId, id)
                .eq(SysUser::getDeleteFlag, 1);
        applyReadScope(query, scope);
        SysUser user = ServiceAssert.notNull(getOne(query), ResultCode.FORBIDDEN, "用户不在当前数据范围内");
        ServiceAssert.isTrue(scope.permits(user) && id.equals(user.getId()), ResultCode.FORBIDDEN);
        List<UserVO> views = toVos(List.of(user), companyId);
        ServiceAssert.isTrue(!views.isEmpty(), ResultCode.FORBIDDEN, "用户已不属于当前公司成员");
        return views.get(0);
    }

    private static void applyReadScope(LambdaQueryWrapper<SysUser> query, UserReadScope scope) {
        if (!scope.all()) {
            query.and(w -> {
                w.eq(SysUser::getId, scope.userId());
                if (!scope.departmentIds().isEmpty()) {
                    w.or().in(SysUser::getDepartmentId, scope.departmentIds());
                }
            });
        }
    }

    /** 新建平台账号及当前公司成员，角色上界在任何写入前校验，审计与关联写同事务。 */
    @Transactional(rollbackFor = Exception.class)
    public String create(UserSaveRequest request) {
        Operator operator = grantPolicy.requireOperator();
        UserWriteValidation.validateUsername(request.getUsername());
        ServiceAssert.notBlank(request.getNickname(), ResultCode.PARAM_INVALID);
        UserWriteValidation.validatePassword(request.getPassword());
        UserWriteValidation.validateProfile(request.getNickname(), request.getEmail(), request.getPhone());
        validateDepartment(request.getDepartmentId(), operator.companyId());
        UserWriteValidation.validateStatus(request.getStatus());
        List<SysRole> roles = validatedRoles(operator, request.getRoleIds());
        ServiceAssert.isTrue(count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername()).eq(SysUser::getDeleteFlag, 1)) == 0,
                ResultCode.USER_USERNAME_DUPLICATED);
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        applyProfile(user, request);
        user.setStatusFlag(1);
        ServiceAssert.isTrue(save(user), ResultCode.DATA_CONCURRENT_CONFLICT);
        SysUserCompany membership = new SysUserCompany();
        membership.setUserId(user.getId());
        membership.setCompanyId(operator.companyId());
        membership.setPrimaryFlag(1);
        membership.setStatusFlag(request.getStatus() == null ? 1 : request.getStatus());
        ServiceAssert.isTrue(userCompanyMapper.insert(membership) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        insertRoles(user.getId(), operator.companyId(), roles);
        changeRecorder.record("create_member", user.getId(), Map.of("roleCount", roles.size()));
        return user.getId();
    }

    /** 更新当前公司成员聚合；账号独占约束和两类版本校验先于角色重绑及成功审计。 */
    @Transactional(rollbackFor = Exception.class)
    public void update(String id, UserSaveRequest request) {
        Operator operator = grantPolicy.requireOperator();
        SysUserCompany membership = requireMembership(id, operator.companyId());
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        validateMemberUpdate(operator, id, user, membership, request);
        List<SysRole> roles = rolesForMemberUpdate(operator, id, request);
        boolean passwordChange = updateAccountIfChanged(id, user, request, operator.companyId());
        applyMemberStatus(id, membership, request.getStatus());
        ServiceAssert.isTrue(userCompanyMapper.updateById(membership) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        if (roles != null) {
            deleteRoles(id, operator.companyId());
            insertRoles(id, operator.companyId(), roles);
        }
        changeRecorder.record("update_member", id, Map.of("rolesChanged", roles != null));
        if (passwordChange) {
            changeRecorder.record("reset_password", id, null);
        }
    }

    private void validateMemberUpdate(Operator operator, String id, SysUser user,
            SysUserCompany membership, UserSaveRequest request) {
        UserWriteValidation.assertMutableAccount(user.getUsername(), "编辑");
        assertCanManageMember(operator, id);
        UserWriteValidation.assertUnchangedUsername(user.getUsername(), request.getUsername());
        UserWriteValidation.assertRevision(membership.getRevision(), request.getMembershipRevision(), "membershipRevision");
        UserWriteValidation.validateStatus(request.getStatus());
        UserWriteValidation.validateProfile(request.getNickname(), request.getEmail(), request.getPhone());
    }

    private List<SysRole> rolesForMemberUpdate(Operator operator, String id, UserSaveRequest request) {
        List<SysRole> roles = request.getRoleIds() == null ? null : validatedRoles(operator, request.getRoleIds());
        if (roles != null) {
            ServiceAssert.isTrue(operator.roles().superAdmin() || !operator.userId().equals(id),
                    ResultCode.FORBIDDEN, "禁止为自己重绑角色");
        }
        return roles;
    }

    private boolean updateAccountIfChanged(String id, SysUser user, UserSaveRequest request, String companyId) {
        boolean profileChange = UserWriteValidation.hasProfileChanges(user, request);
        boolean passwordChange = request.getPassword() != null && !request.getPassword().isBlank();
        if (profileChange || passwordChange) {
            assertExclusiveAccount(id);
            UserWriteValidation.assertRevision(user.getRevision(), request.getRevision(), "revision");
            if (profileChange) {
                validateDepartment(request.getDepartmentId(), companyId);
            }
            applyProfile(user, request);
            if (passwordChange) {
                UserWriteValidation.validatePassword(request.getPassword());
                user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
                user.setSecurityVersion(nextSecurityVersion(user.getSecurityVersion()));
            }
            ServiceAssert.isTrue(updateById(user), ResultCode.DATA_CONCURRENT_CONFLICT);
        }
        return passwordChange;
    }

    private void applyMemberStatus(String id, SysUserCompany membership, Integer status) {
        if (status != null) {
            assertCanDisableSelf(id, status);
            membership.setStatusFlag(status);
            if (status == 0) {
                membership.setSecurityVersion(nextSecurityVersion(membership.getSecurityVersion()));
            }
        }
    }

    /** 按客户端成员版本移出本公司；保留平台账户与其他公司关系，撤权和审计同事务。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id, Integer expectedRevision) {
        Operator operator = grantPolicy.requireOperator();
        String companyId = operator.companyId();
        SysUserCompany membership = requireMembership(id, companyId);
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        UserWriteValidation.assertMutableAccount(user.getUsername(), "移出");
        assertCanManageMember(operator, id);
        ServiceAssert.isTrue(!id.equals(AuthUtil.getLoginUserId()), ResultCode.PARAM_INVALID, "禁止移出自身");
        UserWriteValidation.assertRevision(membership.getRevision(), expectedRevision, "expectedRevision");
        membership.setSecurityVersion(nextSecurityVersion(membership.getSecurityVersion()));
        ServiceAssert.isTrue(userCompanyMapper.update(membership, new LambdaUpdateWrapper<SysUserCompany>()
                        .eq(SysUserCompany::getId, membership.getId()).eq(SysUserCompany::getUserId, id)
                        .eq(SysUserCompany::getCompanyId, companyId)
                        .eq(SysUserCompany::getRevision, expectedRevision)
                        .set(SysUserCompany::getDeleteFlag, 0)) == 1,
                ResultCode.DATA_CONCURRENT_CONFLICT);
        deleteRoles(id, companyId);
        SysTempAuthorization revoked = new SysTempAuthorization();
        revoked.setStatusFlag(0);
        tempAuthorizationMapper.update(revoked, new LambdaQueryWrapper<SysTempAuthorization>()
                .eq(SysTempAuthorization::getCompanyId, companyId).eq(SysTempAuthorization::getUserId, id)
                .eq(SysTempAuthorization::getStatusFlag, 1));
        changeRecorder.record("remove_member", id, null);
    }

    /** 两类客户端版本CAS后重置独占账号密码；成员栅栏、账号、审计和事件同事务。 */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(String id, UserPasswordResetRequest request) {
        Operator operator = grantPolicy.requireOperator();
        ServiceAssert.notNull(request, ResultCode.PARAM_INVALID);
        String companyId = operator.companyId();
        SysUserCompany membership = requireMembership(id, companyId);
        assertCanManageMember(operator, id);
        assertExclusiveAccount(id);
        UserWriteValidation.validatePassword(request.password());
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        UserWriteValidation.assertRevision(user.getRevision(), request.expectedRevision(), "expectedRevision");
        UserWriteValidation.assertRevision(membership.getRevision(), request.expectedMembershipRevision(), "expectedMembershipRevision");
        ServiceAssert.isTrue(userCompanyMapper.updateById(membership) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setSecurityVersion(nextSecurityVersion(user.getSecurityVersion()));
        ServiceAssert.isTrue(updateById(user), ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record("reset_password", id, null);
    }

    /** 按客户端成员版本启停当前公司成员；重新启用仍保留已推进的安全版本。 */
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(String id, UserStatusRequest request) {
        Operator operator = grantPolicy.requireOperator();
        ServiceAssert.notNull(request, ResultCode.PARAM_INVALID);
        String companyId = operator.companyId();
        Integer status = request.status();
        UserWriteValidation.validateStatus(status);
        ServiceAssert.notNull(status, ResultCode.PARAM_INVALID);
        SysUserCompany membership = requireMembership(id, companyId);
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        assertCanManageMember(operator, id);
        if (status == 0) {
            UserWriteValidation.assertMutableAccount(user.getUsername(), "停用成员");
        }
        UserWriteValidation.assertRevision(membership.getRevision(), request.expectedRevision(), "expectedRevision");
        applyMemberStatus(id, membership, status);
        ServiceAssert.isTrue(userCompanyMapper.updateById(membership) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record("member_status", id, Map.of("status", status));
    }

    private SysUserCompany requireMembership(String userId, String companyId) {
        ServiceAssert.notBlank(userId, ResultCode.PARAM_INVALID);
        List<SysUserCompany> memberships = userCompanyMapper.selectList(new LambdaQueryWrapper<SysUserCompany>()
                        .eq(SysUserCompany::getUserId, userId).eq(SysUserCompany::getCompanyId, companyId)
                        .eq(SysUserCompany::getDeleteFlag, 1))
                .stream().filter(m -> userId.equals(m.getUserId()) && companyId.equals(m.getCompanyId())
                        && Integer.valueOf(1).equals(m.getDeleteFlag())).toList();
        ServiceAssert.isTrue(memberships.size() == 1, ResultCode.FORBIDDEN, "目标用户不是当前公司的唯一有效成员");
        return memberships.get(0);
    }

    private void assertExclusiveAccount(String userId) {
        long companies = userCompanyMapper.selectList(new LambdaQueryWrapper<SysUserCompany>()
                        .eq(SysUserCompany::getUserId, userId).eq(SysUserCompany::getDeleteFlag, 1))
                .stream().filter(m -> userId.equals(m.getUserId()) && Integer.valueOf(1).equals(m.getDeleteFlag()))
                .map(SysUserCompany::getCompanyId).distinct().count();
        ServiceAssert.isTrue(companies == 1, ResultCode.FORBIDDEN,
                "共享平台账户资料和密码只能由本人或独立平台管理员修改");
    }

    /** 管理成员需覆盖其永久和未结束临时角色；普通操作者不得使用管理命令修改本人。 */
    private void assertCanManageMember(Operator operator, String targetId) {
        ServiceAssert.isTrue(operator.roles().superAdmin() || !operator.userId().equals(targetId),
                ResultCode.FORBIDDEN, "本人资料和密码请使用需要原密码校验的本人入口");
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
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, targetId).eq(SysUserRole::getCompanyId, companyId)
                        .eq(SysUserRole::getDeleteFlag, 1)).stream()
                .filter(binding -> targetId.equals(binding.getUserId())
                        && companyId.equals(binding.getCompanyId())
                        && Integer.valueOf(1).equals(binding.getDeleteFlag()))
                .map(SysUserRole::getRoleId).collect(Collectors.toSet());
    }

    private void addUnexpiredMemberRoleIds(Set<String> roleIds, String targetId, String companyId, LocalDateTime now) {
        tempAuthorizationMapper.selectList(new LambdaQueryWrapper<SysTempAuthorization>()
                        .eq(SysTempAuthorization::getUserId, targetId)
                        .eq(SysTempAuthorization::getCompanyId, companyId)
                        .eq(SysTempAuthorization::getStatusFlag, 1).eq(SysTempAuthorization::getDeleteFlag, 1)
                        .gt(SysTempAuthorization::getEffectTo, now)).stream()
                .filter(temp -> targetId.equals(temp.getUserId()) && companyId.equals(temp.getCompanyId())
                        && Integer.valueOf(1).equals(temp.getStatusFlag()) && Integer.valueOf(1).equals(temp.getDeleteFlag())
                        && temp.getEffectTo() != null && temp.getEffectTo().isAfter(now))
                .forEach(temp -> roleIds.add(temp.getRoleId()));
    }

    private List<SysRole> managedMemberRoles(Set<String> roleIds, String companyId) {
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .in(SysRole::getId, roleIds).eq(SysRole::getCompanyId, companyId)
                        .eq(SysRole::getDeleteFlag, 1)).stream()
                .filter(role -> roleIds.contains(role.getId()) && companyId.equals(role.getCompanyId())
                        && Integer.valueOf(1).equals(role.getDeleteFlag())).toList();
        ServiceAssert.isTrue(roles.stream().map(SysRole::getId).distinct().count() == roleIds.size(),
                ResultCode.FORBIDDEN, "目标成员存在无效或跨公司角色绑定");
        return roles;
    }

    private void assertCanDisableSelf(String id, Integer status) {
        ServiceAssert.isTrue(status == null || status != 0 || !id.equals(AuthUtil.getLoginUserId()),
                ResultCode.PARAM_INVALID, "禁止停用自身公司成员");
    }

    private void validateDepartment(String id, String companyId) {
        if (id == null || id.isBlank()) {
            return;
        }
        SysDepartment department = departmentMapper.selectById(id);
        ServiceAssert.isTrue(department != null && companyId.equals(department.getCompanyId())
                        && Integer.valueOf(1).equals(department.getDeleteFlag())
                        && Integer.valueOf(1).equals(department.getStatusFlag()),
                ResultCode.FORBIDDEN, "部门不属于当前公司或已停用");
    }

    private static void applyProfile(SysUser user, UserSaveRequest request) {
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setDepartmentId(request.getDepartmentId());
    }

    private List<SysRole> validatedRoles(Operator operator, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        ServiceAssert.isTrue(ids.size() <= RobotConstants.BATCH_WRITE_LIMIT
                        && ids.stream().allMatch(id -> id != null && !id.isBlank() && id.length() <= 64)
                        && new HashSet<>(ids).size() == ids.size(), ResultCode.PARAM_INVALID,
                "角色ID必须有效、不重复且在单批上限内");
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .in(SysRole::getId, ids).eq(SysRole::getCompanyId, operator.companyId())
                        .eq(SysRole::getDeleteFlag, 1).eq(SysRole::getStatusFlag, 1))
                .stream().filter(role -> ids.contains(role.getId())
                        && operator.companyId().equals(role.getCompanyId())
                        && Integer.valueOf(1).equals(role.getDeleteFlag())
                        && Integer.valueOf(1).equals(role.getStatusFlag())).toList();
        ServiceAssert.isTrue(roles.stream().map(SysRole::getId).distinct().count() == ids.size(),
                ResultCode.FORBIDDEN, "包含无效、停用或跨公司角色");
        grantPolicy.assertRoles(operator, roles);
        return roles;
    }

    private void insertRoles(String userId, String companyId, List<SysRole> roles) {
        for (SysRole role : roles) {
            SysUserRole binding = new SysUserRole(); binding.setUserId(userId);
            binding.setCompanyId(companyId); binding.setRoleId(role.getId());
            ServiceAssert.isTrue(userRoleMapper.insert(binding) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        }
    }

    private void deleteRoles(String userId, String companyId) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId).eq(SysUserRole::getCompanyId, companyId));
    }

    private List<UserVO> toVos(List<SysUser> users, String companyId) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<String> ids = users.stream().map(SysUser::getId).toList();
        Map<String, SysUserCompany> memberships = userCompanyMapper.selectList(new LambdaQueryWrapper<SysUserCompany>()
                        .in(SysUserCompany::getUserId, ids).eq(SysUserCompany::getCompanyId, companyId)
                        .eq(SysUserCompany::getDeleteFlag, 1)).stream()
                .filter(m -> ids.contains(m.getUserId()) && companyId.equals(m.getCompanyId())
                        && Integer.valueOf(1).equals(m.getDeleteFlag()))
                .collect(Collectors.toMap(SysUserCompany::getUserId, Function.identity()));
        List<SysUserRole> bindings = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .in(SysUserRole::getUserId, ids).eq(SysUserRole::getCompanyId, companyId)
                        .eq(SysUserRole::getDeleteFlag, 1)).stream()
                .filter(binding -> ids.contains(binding.getUserId()) && companyId.equals(binding.getCompanyId())
                        && Integer.valueOf(1).equals(binding.getDeleteFlag())).toList();
        Map<String, SysRole> roles = bindings.isEmpty() ? Map.of() : roleMapper.selectBatchIds(
                        bindings.stream().map(SysUserRole::getRoleId).distinct().toList()).stream()
                .filter(role -> companyId.equals(role.getCompanyId()) && Integer.valueOf(1).equals(role.getDeleteFlag()))
                .collect(Collectors.toMap(SysRole::getId, Function.identity()));
        return users.stream().filter(user -> memberships.containsKey(user.getId())).map(user -> {
            List<String> roleIds = bindings.stream().filter(binding -> user.getId().equals(binding.getUserId()))
                    .map(SysUserRole::getRoleId).filter(roles::containsKey).distinct().toList();
            UserVO vo = UserVO.from(user, roleIds.stream().map(id -> roles.get(id).getRoleName()).toList());
            SysUserCompany membership = memberships.get(user.getId());
            vo.setRoleIds(roleIds);
            vo.setMembershipRevision(membership.getRevision());
            vo.setStatus(membership.getStatusFlag());
            vo.setStatusFlag(membership.getStatusFlag());
            return vo;
        }).toList();
    }

    /** 本人改密必须匹配旧密码；推进会话安全版本并与密码更新、审计同事务提交。 */
    @Transactional(rollbackFor = Exception.class)
    public void changeOwnPassword(String userId, String oldPassword, String newPassword) {
        requireOwnUser(userId);
        UserWriteValidation.validatePassword(newPassword);
        SysUser user = ServiceAssert.notNull(getById(userId), ResultCode.USER_NOT_FOUND);
        ServiceAssert.isTrue(passwordEncoder.matches(oldPassword == null ? "" : oldPassword, user.getPasswordHash()),
                ResultCode.PARAM_INVALID, "原密码不正确");
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setSecurityVersion(nextSecurityVersion(user.getSecurityVersion()));
        ServiceAssert.isTrue(updateById(user), ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record("change_password", userId, null);
    }

    /** 本人资料入口只允许当前登录账号，避免公司管理命令绕过本人身份校验。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateOwnProfile(String userId, String nickname, String email, String phone) {
        requireOwnUser(userId);
        UserWriteValidation.validateProfile(nickname, email, phone);
        SysUser user = ServiceAssert.notNull(getById(userId), ResultCode.USER_NOT_FOUND);
        if (nickname != null) {
            user.setNickname(nickname);
        }
        user.setEmail(email);
        user.setPhone(phone);
        ServiceAssert.isTrue(updateById(user), ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record("profile_update", userId, null);
    }

    private void requireOwnUser(String id) {
        ServiceAssert.notBlank(AuthUtil.getLoginUserId(), ResultCode.UNAUTHORIZED);
        ServiceAssert.isTrue(AuthUtil.getLoginUserId().equals(id), ResultCode.FORBIDDEN);
        grantPolicy.requireCompany();
    }

    private static long nextSecurityVersion(Long value) {
        return Math.addExact(value == null ? 0L : value, 1L);
    }
}
