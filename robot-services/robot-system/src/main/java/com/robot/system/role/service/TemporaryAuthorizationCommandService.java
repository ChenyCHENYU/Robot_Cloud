package com.robot.system.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.system.api.dto.role.TempAuthorizationCreateRequest;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.permission.service.PermissionGrantPolicy.Operator;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 临时角色授权写用例；沿用永久授权上界，授权记录与审计事件同事务。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Service
@RequiredArgsConstructor
public class TemporaryAuthorizationCommandService {
    private final SysRoleMapper roleMapper;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;
    private final SysUserMapper userMapper;
    private final SysUserCompanyMapper membershipMapper;
    private final PermissionGrantPolicy grantPolicy;
    private final PermissionChangeRecorder changeRecorder;
    private final Clock clock;

    /** 为有效公司成员创建有界临时角色；操作者的委托能力只来自永久角色。 */
    @Transactional(rollbackFor = Exception.class)
    public String createTempAuthorization(TempAuthorizationCreateRequest request) {
        Operator operator = grantPolicy.requireOperator();
        validateWindow(request);
        validateReason(request.getReason());
        ServiceAssert.isTrue(operator.roles().superAdmin() || !operator.userId().equals(request.getUserId()),
                ResultCode.FORBIDDEN, "禁止为自己创建临时授权");
        SysRole role = requireRole(request.getRoleId(), operator.companyId());
        grantPolicy.assertRoles(operator, List.of(role));
        SysUser target = ServiceAssert.notNull(userMapper.selectById(request.getUserId()), ResultCode.USER_NOT_FOUND);
        assertActiveMember(target, request.getUserId(), operator.companyId());
        SysTempAuthorization authorization = new SysTempAuthorization();
        authorization.setCompanyId(operator.companyId());
        authorization.setUserId(request.getUserId());
        authorization.setRoleId(role.getId());
        authorization.setEffectFrom(request.getEffectFrom());
        authorization.setEffectTo(request.getEffectTo());
        authorization.setReason(request.getReason());
        authorization.setStatusFlag(1);
        ServiceAssert.isTrue(tempAuthorizationMapper.insert(authorization) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        changeRecorder.record("temp_authorize", target.getId(), Map.of("roleId", role.getId(),
                "effectTo", request.getEffectTo().toString()));
        return authorization.getId();
    }

    private void validateWindow(TempAuthorizationCreateRequest request) {
        ServiceAssert.isTrue(request.getEffectFrom() != null && request.getEffectTo() != null
                        && request.getEffectFrom().isBefore(request.getEffectTo())
                        && !request.getEffectTo().isAfter(request.getEffectFrom().plusDays(30))
                        && request.getEffectTo().isAfter(LocalDateTime.now(clock)),
                ResultCode.PARAM_INVALID, "临时授权时间窗必须有效且最长30天");
    }

    private static void validateReason(String reason) {
        ServiceAssert.notBlank(reason, ResultCode.PARAM_INVALID, "授权理由不能为空");
        ServiceAssert.isTrue(reason.length() <= 255, ResultCode.PARAM_INVALID, "授权理由最长255");
    }

    private void assertActiveMember(SysUser target, String userId, String companyId) {
        ServiceAssert.isTrue(Integer.valueOf(1).equals(target.getStatusFlag())
                        && Integer.valueOf(1).equals(target.getDeleteFlag())
                        && membershipMapper.selectCount(new LambdaQueryWrapper<SysUserCompany>()
                        .eq(SysUserCompany::getCompanyId, companyId)
                        .eq(SysUserCompany::getUserId, userId)
                        .eq(SysUserCompany::getStatusFlag, 1).eq(SysUserCompany::getDeleteFlag, 1)) == 1,
                ResultCode.FORBIDDEN, "被授权用户不是当前公司的有效成员");
    }

    private SysRole requireRole(String id, String companyId) {
        ServiceAssert.notBlank(id, ResultCode.PARAM_INVALID, "角色ID不能为空");
        SysRole role = ServiceAssert.notNull(roleMapper.selectById(id), ResultCode.ROLE_NOT_FOUND);
        ServiceAssert.isTrue(companyId.equals(role.getCompanyId()) && Integer.valueOf(1).equals(role.getDeleteFlag()),
                ResultCode.FORBIDDEN, "角色不属于当前公司");
        return role;
    }

}
