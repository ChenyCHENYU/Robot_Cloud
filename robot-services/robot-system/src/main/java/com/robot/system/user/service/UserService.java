package com.robot.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.user.UserPageQuery;
import com.robot.system.api.dto.user.UserPasswordResetRequest;
import com.robot.system.api.dto.user.UserSaveRequest;
import com.robot.system.api.dto.user.UserStatusRequest;
import com.robot.system.api.entity.SysDepartment;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.vo.user.UserVO;
import com.robot.system.dept.mapper.SysDepartmentMapper;
import com.robot.system.permission.service.GlobalDirectoryPolicy;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.permission.service.PermissionGrantPolicy.Operator;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final SysDepartmentMapper departmentMapper;
    private final PermissionGrantPolicy grantPolicy;
    private final BCryptPasswordEncoder passwordEncoder;
    private final PermissionChangeRecorder changeRecorder;
    private final PermissionStateStore stateStore;
    private final UserQueryService queries;
    private final MemberRoleCommandSupport memberRoles;
    private final GlobalDirectoryPolicy directoryPolicy;

    /**
     * 委托成员读侧执行相同公司事实与数据权限过滤。
     *
     * @param query 分页与筛选条件
     * @return 当前事实派生的结果
     */
    public PageResult<UserVO> queryPage(UserPageQuery query) {
        return queries.queryPage(query);
    }

    /**
     * 详情复验当前成员关系，写聚合不承担资料投影装配。
     *
     * @param id 记录ID
     * @return 审计详情
     */
    public UserVO getDetail(String id) {
        return queries.getDetail(id);
    }

    /**
     * 本人主部门取当前有效成员，不回读退役全局字段。
     *
     * @param userId 平台账号ID
     * @return 当前事实派生的结果
     */
    public String currentMemberDepartment(String userId) {
        return queries.currentMemberDepartment(userId);
    }

    /**
     * 新建平台账号及当前公司成员，角色上界在任何写入前校验，审计与关联写同事务。
     *
     * @param request 该用例接收的白名单请求
     * @return 当前事实派生的结果
     */
    @Transactional(rollbackFor = Exception.class)
    public String create(UserSaveRequest request) {
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        Operator operator = grantPolicy.requireOperator();
        UserWriteValidation.validateUsername(request.getUsername());
        ServiceAssert.notBlank(request.getNickname(), ResultCode.PARAM_INVALID);
        UserWriteValidation.validatePassword(request.getPassword());
        UserWriteValidation.validateProfile(
                request.getNickname(), request.getEmail(), request.getPhone());
        validateDepartment(request.getDepartmentId(), operator.companyId());
        UserWriteValidation.validateStatus(request.getStatus());
        List<SysRole> roles = memberRoles.validatedRoles(operator, request.getRoleIds());
        ServiceAssert.isTrue(
                count(
                                new LambdaQueryWrapper<SysUser>()
                                        .eq(SysUser::getUsername, request.getUsername())
                                        .eq(SysUser::getDeleteFlag, 1))
                        == 0,
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
        membership.setDepartmentId(request.getDepartmentId());
        membership.setStatusFlag(
                request.getStatus() == null ? Integer.valueOf(1) : request.getStatus());
        ServiceAssert.isTrue(
                userCompanyMapper.insert(membership) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        memberRoles.insertRoles(user.getId(), operator.companyId(), roles);
        changeRecorder.record("create_member", user.getId(), Map.of("roleCount", roles.size()));
        return user.getId();
    }

    /**
     * 更新当前公司成员聚合；账号独占约束和两类版本校验先于角色重绑及成功审计。
     *
     * @param id 记录ID
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(String id, UserSaveRequest request) {
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        Operator operator = grantPolicy.requireOperator();
        SysUserCompany membership = requireMembership(id, operator.companyId());
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        directoryPolicy.assertManagedAccount(user);
        validateMemberUpdate(operator, id, user, membership, request);
        List<SysRole> roles = rolesForMemberUpdate(operator, id, request);
        boolean passwordChange = updateAccountIfChanged(id, user, request, operator.companyId());
        if (request.isDepartmentIdPresent()) {
            validateDepartment(request.getDepartmentId(), operator.companyId());
            membership.setDepartmentId(request.getDepartmentId());
        }
        applyMemberStatus(id, membership, request.getStatus());
        ServiceAssert.isTrue(
                userCompanyMapper.updateById(membership) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        if (roles != null) {
            memberRoles.deleteRoles(id, operator.companyId());
            memberRoles.insertRoles(id, operator.companyId(), roles);
        }
        changeRecorder.record("update_member", id, Map.of("rolesChanged", roles != null));
        if (passwordChange) {
            changeRecorder.record("reset_password", id, null);
        }
    }

    private void validateMemberUpdate(
            Operator operator,
            String id,
            SysUser user,
            SysUserCompany membership,
            UserSaveRequest request) {
        UserWriteValidation.assertMutableAccount(user.getUsername(), "编辑");
        memberRoles.assertCanManageMember(operator, id);
        UserWriteValidation.assertUnchangedUsername(user.getUsername(), request.getUsername());
        UserWriteValidation.assertRevision(
                membership.getRevision(), request.getMembershipRevision(), "membershipRevision");
        UserWriteValidation.validateStatus(request.getStatus());
        UserWriteValidation.validateProfile(
                request.getNickname(), request.getEmail(), request.getPhone());
        ServiceAssert.isTrue(
                !request.isNicknamePresent() || request.getNickname() != null,
                ResultCode.PARAM_INVALID,
                "昵称不可清空");
    }

    private List<SysRole> rolesForMemberUpdate(
            Operator operator, String id, UserSaveRequest request) {
        List<SysRole> roles =
                request.getRoleIds() == null
                        ? null
                        : memberRoles.validatedRoles(operator, request.getRoleIds());
        if (roles != null) {
            ServiceAssert.isTrue(
                    operator.roles().superAdmin() || !operator.userId().equals(id),
                    ResultCode.FORBIDDEN,
                    "禁止为自己重绑角色");
        }
        return roles;
    }

    private boolean updateAccountIfChanged(
            String id, SysUser user, UserSaveRequest request, String companyId) {
        boolean profileChange = UserWriteValidation.hasProfileChanges(user, request);
        boolean passwordChange = request.getPassword() != null && !request.getPassword().isBlank();
        if (profileChange || passwordChange) {
            assertExclusiveAccount(id);
            UserWriteValidation.assertRevision(
                    user.getRevision(), request.getRevision(), "revision");
            applyProfile(user, request);
            if (passwordChange) {
                UserWriteValidation.validatePassword(request.getPassword());
                user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
                user.setSecurityVersion(nextSecurityVersion(user.getSecurityVersion()));
            }
            LambdaUpdateWrapper<SysUser> patch =
                    new LambdaUpdateWrapper<SysUser>()
                            .eq(SysUser::getId, id)
                            .eq(SysUser::getRevision, request.getRevision())
                            .eq(SysUser::getDeleteFlag, 1);
            if (request.isEmailPresent() && request.getEmail() == null) {
                patch.set(SysUser::getEmail, null);
            }
            if (request.isPhonePresent() && request.getPhone() == null) {
                patch.set(SysUser::getPhone, null);
            }
            ServiceAssert.isTrue(
                    baseMapper.update(user, patch) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
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

    /**
     * 按客户端成员版本移出本公司；保留平台账户与其他公司关系，撤权和审计同事务。
     *
     * @param id 记录ID
     * @param expectedRevision 客户端期望的聚合版本
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id, Integer expectedRevision) {
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        Operator operator = grantPolicy.requireOperator();
        String companyId = operator.companyId();
        SysUserCompany membership = requireMembership(id, companyId);
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        directoryPolicy.assertManagedAccount(user);
        UserWriteValidation.assertMutableAccount(user.getUsername(), "移出");
        memberRoles.assertCanManageMember(operator, id);
        ServiceAssert.isTrue(
                !id.equals(AuthUtil.getLoginUserId()), ResultCode.PARAM_INVALID, "禁止移出自身");
        UserWriteValidation.assertRevision(
                membership.getRevision(), expectedRevision, "expectedRevision");
        membership.setSecurityVersion(nextSecurityVersion(membership.getSecurityVersion()));
        ServiceAssert.isTrue(
                userCompanyMapper.update(
                                membership,
                                new LambdaUpdateWrapper<SysUserCompany>()
                                        .eq(SysUserCompany::getId, membership.getId())
                                        .eq(SysUserCompany::getUserId, id)
                                        .eq(SysUserCompany::getCompanyId, companyId)
                                        .eq(SysUserCompany::getRevision, expectedRevision)
                                        .set(SysUserCompany::getDeleteFlag, 0))
                        == 1,
                ResultCode.DATA_CONCURRENT_CONFLICT);
        memberRoles.deleteRoles(id, companyId);
        memberRoles.revokeTemporaryRoles(id, companyId);
        changeRecorder.record("remove_member", id, null);
    }

    /**
     * 两类客户端版本CAS后重置独占账号密码；成员栅栏、账号、审计和事件同事务。
     *
     * @param id 记录ID
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(String id, UserPasswordResetRequest request) {
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        Operator operator = grantPolicy.requireOperator();
        ServiceAssert.notNull(request, ResultCode.PARAM_INVALID);
        String companyId = operator.companyId();
        SysUserCompany membership = requireMembership(id, companyId);
        memberRoles.assertCanManageMember(operator, id);
        assertExclusiveAccount(id);
        UserWriteValidation.validatePassword(request.password());
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        directoryPolicy.assertManagedAccount(user);
        UserWriteValidation.assertRevision(
                user.getRevision(), request.expectedRevision(), "expectedRevision");
        UserWriteValidation.assertRevision(
                membership.getRevision(),
                request.expectedMembershipRevision(),
                "expectedMembershipRevision");
        ServiceAssert.isTrue(
                userCompanyMapper.updateById(membership) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setSecurityVersion(nextSecurityVersion(user.getSecurityVersion()));
        ServiceAssert.isTrue(updateById(user), ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record("reset_password", id, null);
    }

    /**
     * 按客户端成员版本启停当前公司成员；重新启用仍保留已推进的安全版本。
     *
     * @param id 记录ID
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(String id, UserStatusRequest request) {
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        Operator operator = grantPolicy.requireOperator();
        ServiceAssert.notNull(request, ResultCode.PARAM_INVALID);
        String companyId = operator.companyId();
        Integer status = request.status();
        UserWriteValidation.validateStatus(status);
        ServiceAssert.notNull(status, ResultCode.PARAM_INVALID);
        SysUserCompany membership = requireMembership(id, companyId);
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        directoryPolicy.assertManagedAccount(user);
        memberRoles.assertCanManageMember(operator, id);
        if (status == 0) {
            UserWriteValidation.assertMutableAccount(user.getUsername(), "停用成员");
        }
        UserWriteValidation.assertRevision(
                membership.getRevision(), request.expectedRevision(), "expectedRevision");
        applyMemberStatus(id, membership, status);
        ServiceAssert.isTrue(
                userCompanyMapper.updateById(membership) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record("member_status", id, Map.of("status", status));
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

    private void assertExclusiveAccount(String userId) {
        long companies =
                userCompanyMapper
                        .selectList(
                                new LambdaQueryWrapper<SysUserCompany>()
                                        .eq(SysUserCompany::getUserId, userId)
                                        .eq(SysUserCompany::getDeleteFlag, 1))
                        .stream()
                        .filter(
                                m ->
                                        userId.equals(m.getUserId())
                                                && Integer.valueOf(1).equals(m.getDeleteFlag()))
                        .map(SysUserCompany::getCompanyId)
                        .distinct()
                        .count();
        ServiceAssert.isTrue(companies == 1, ResultCode.FORBIDDEN, "共享平台账户资料和密码请由本人在受控账户入口修改");
    }

    private void assertCanDisableSelf(String id, Integer status) {
        ServiceAssert.isTrue(
                status == null || status != 0 || !id.equals(AuthUtil.getLoginUserId()),
                ResultCode.PARAM_INVALID,
                "禁止停用自身公司成员");
    }

    private void validateDepartment(String id, String companyId) {
        if (id == null || id.isBlank()) {
            return;
        }
        SysDepartment department = departmentMapper.selectById(id);
        ServiceAssert.isTrue(
                department != null
                        && companyId.equals(department.getCompanyId())
                        && Integer.valueOf(1).equals(department.getDeleteFlag())
                        && Integer.valueOf(1).equals(department.getStatusFlag()),
                ResultCode.FORBIDDEN,
                "部门不属于当前公司或已停用");
    }

    private static void applyProfile(SysUser user, UserSaveRequest request) {
        if (request.isNicknamePresent()) {
            user.setNickname(request.getNickname());
        }
        if (request.isEmailPresent()) {
            user.setEmail(request.getEmail());
        }
        if (request.isPhonePresent()) {
            user.setPhone(request.getPhone());
        }
    }

    private static long nextSecurityVersion(Long value) {
        return Math.addExact(value == null ? 0L : value, 1L);
    }
}
