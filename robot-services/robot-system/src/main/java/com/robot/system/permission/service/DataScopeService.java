package com.robot.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.system.api.constant.SystemConstants;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.user.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import com.robot.system.api.vo.permission.DataScopeVO;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据权限：按「当前公司角色 DATA_SCOPE + 生效中的临时授权」聚合，
 * 输出前端 GetDataPermissionResponse 契约的 module/scope 结构。
 */
@Service
@RequiredArgsConstructor
public class DataScopeService {

    private final EffectiveRoleResolver effectiveRoleResolver;

    public List<DataScopeVO> scopesOfUser(String userId, String companyId) {
        EffectiveRoleResolver.EffectiveRoles effective =
                effectiveRoleResolver.resolve(userId, companyId);
        Set<String> scopes = effective.roles().stream()
                .map(role -> role.getDataScope() == null
                        ? SystemConstants.DATA_SCOPE_SELF : role.getDataScope())
                .collect(Collectors.toSet());
        // 范围取最大（all > custom > department_below > department > self）
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
