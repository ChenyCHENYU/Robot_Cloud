package com.robot.system.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.role.TempAuthorizationCreateRequest;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.permission.service.PermissionGrantPolicy.Operator;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 临时角色授权写用例；沿用永久授权上界，授权记录与审计事件同事务。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Service
@RequiredArgsConstructor
public class TemporaryAuthorizationCommandService {
    private static final int MAX_GRANT_DAYS = 30;
    private static final int MAX_REASON_LENGTH = 255;

    private final SysRoleMapper roleMapper;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;
    private final SysUserMapper userMapper;
    private final SysUserCompanyMapper membershipMapper;
    private final PermissionGrantPolicy grantPolicy;
    private final PermissionChangeRecorder changeRecorder;
    private final Clock clock;
    private final PermissionStateStore stateStore;

    /**
     * 为有效公司成员创建有界临时角色；操作者的委托能力只来自永久角色。
     *
     * @param request 该用例接收的白名单请求
     * @return 当前事实派生的结果
     */
    @Transactional(rollbackFor = Exception.class)
    public String createTempAuthorization(TempAuthorizationCreateRequest request) {
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        Operator operator = grantPolicy.requireOperator();
        validateWindow(request);
        validateReason(request.getReason());
        ServiceAssert.isTrue(
                operator.roles().superAdmin() || !operator.userId().equals(request.getUserId()),
                ResultCode.FORBIDDEN,
                "禁止为自己创建临时授权");
        SysRole role = requireRole(request.getRoleId(), operator.companyId());
        grantPolicy.assertRoles(operator, List.of(role));
        SysUser target =
                ServiceAssert.notNull(
                        userMapper.selectById(request.getUserId()), ResultCode.USER_NOT_FOUND);
        assertActiveMember(target, request.getUserId(), operator.companyId());
        SysTempAuthorization authorization = new SysTempAuthorization();
        authorization.setCompanyId(operator.companyId());
        authorization.setUserId(request.getUserId());
        authorization.setRoleId(role.getId());
        authorization.setEffectFrom(request.getEffectFrom());
        authorization.setEffectTo(request.getEffectTo());
        authorization.setReason(request.getReason());
        authorization.setStatusFlag(1);
        ServiceAssert.isTrue(
                tempAuthorizationMapper.insert(authorization) == 1,
                ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record(
                "temp_authorize",
                target.getId(),
                Map.of("roleId", role.getId(), "effectTo", request.getEffectTo().toString()));
        return authorization.getId();
    }

    /**
     * 未来或当前临时授权均可精准撤回；永久委托上界和客户端 CAS 保持同事务。
     *
     * @param id 记录ID
     * @param expectedRevision 客户端期望的聚合版本
     */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(String id, Integer expectedRevision) {
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        Operator operator = grantPolicy.requireOperator();
        SysTempAuthorization authorization =
                ServiceAssert.notNull(
                        tempAuthorizationMapper.selectOne(
                                new LambdaQueryWrapper<SysTempAuthorization>()
                                        .eq(SysTempAuthorization::getId, id)
                                        .eq(
                                                SysTempAuthorization::getCompanyId,
                                                operator.companyId())
                                        .eq(SysTempAuthorization::getDeleteFlag, 1)),
                        ResultCode.NOT_FOUND);
        ServiceAssert.isTrue(
                expectedRevision != null && expectedRevision >= 0, ResultCode.PARAM_INVALID);
        ServiceAssert.isTrue(
                expectedRevision.equals(authorization.getRevision())
                        && Integer.valueOf(1).equals(authorization.getStatusFlag()),
                ResultCode.DATA_CONCURRENT_CONFLICT);
        SysRole role = requireRole(authorization.getRoleId(), operator.companyId());
        grantPolicy.assertManagedRoles(operator, List.of(role));
        authorization.setStatusFlag(0);
        ServiceAssert.isTrue(
                tempAuthorizationMapper.updateById(authorization) == 1,
                ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record(
                "revoke_temp_authorization",
                id,
                Map.of("roleId", role.getId(), "userId", authorization.getUserId()));
    }

    private void validateWindow(TempAuthorizationCreateRequest request) {
        ServiceAssert.isTrue(
                request.getEffectFrom() != null
                        && request.getEffectTo() != null
                        && request.getEffectFrom().isBefore(request.getEffectTo())
                        && !request.getEffectTo()
                                .isAfter(request.getEffectFrom().plusDays(MAX_GRANT_DAYS))
                        && request.getEffectTo().isAfter(LocalDateTime.now(clock)),
                ResultCode.PARAM_INVALID,
                "临时授权时间窗必须有效且最长30天");
    }

    private static void validateReason(String reason) {
        ServiceAssert.notBlank(reason, ResultCode.PARAM_INVALID, "授权理由不能为空");
        ServiceAssert.isTrue(
                reason.length() <= MAX_REASON_LENGTH, ResultCode.PARAM_INVALID, "授权理由最长255");
    }

    private void assertActiveMember(SysUser target, String userId, String companyId) {
        ServiceAssert.isTrue(
                Integer.valueOf(1).equals(target.getStatusFlag())
                        && Integer.valueOf(1).equals(target.getDeleteFlag())
                        && membershipMapper.selectCount(
                                        new LambdaQueryWrapper<SysUserCompany>()
                                                .eq(SysUserCompany::getCompanyId, companyId)
                                                .eq(SysUserCompany::getUserId, userId)
                                                .eq(SysUserCompany::getStatusFlag, 1)
                                                .eq(SysUserCompany::getDeleteFlag, 1))
                                == 1,
                ResultCode.FORBIDDEN,
                "被授权用户不是当前公司的有效成员");
    }

    private SysRole requireRole(String id, String companyId) {
        ServiceAssert.notBlank(id, ResultCode.PARAM_INVALID, "角色ID不能为空");
        SysRole role = ServiceAssert.notNull(roleMapper.selectById(id), ResultCode.ROLE_NOT_FOUND);
        ServiceAssert.isTrue(
                companyId.equals(role.getCompanyId())
                        && Integer.valueOf(1).equals(role.getDeleteFlag()),
                ResultCode.FORBIDDEN,
                "角色不属于当前公司");
        return role;
    }
}
