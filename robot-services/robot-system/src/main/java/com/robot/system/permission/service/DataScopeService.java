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

    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;

    public List<DataScopeVO> scopesOfUser(String userId, String companyId) {
        Set<String> roleIds = new HashSet<>();
        Set<String> scopes = new HashSet<>();
        for (SysRole role : rolesOfUser(userId, companyId)) {
            roleIds.add(role.getId());
            scopes.add(role.getDataScope() == null
                    ? SystemConstants.DATA_SCOPE_SELF : role.getDataScope());
        }
        // 临时授权并入（只提升范围，不降级；角色一次批量查询）
        List<SysTempAuthorization> tempAuths = tempAuthorizationMapper.selectList(
                new LambdaQueryWrapper<SysTempAuthorization>()
                        .eq(SysTempAuthorization::getUserId, userId)
                        .eq(SysTempAuthorization::getStatusFlag, 1)
                        .eq(SysTempAuthorization::getDeleteFlag,
                                RobotConstants.DELETE_FLAG_ACTIVE)
                        .le(SysTempAuthorization::getEffectFrom, LocalDateTime.now())
                        .ge(SysTempAuthorization::getEffectTo, LocalDateTime.now()));
        if (!tempAuths.isEmpty()) {
            Set<String> grantedIds = tempAuths.stream()
                    .map(SysTempAuthorization::getRoleId).collect(Collectors.toSet());
            for (SysRole granted : roleMapper.selectBatchIds(grantedIds)) {
                if (granted.getCompanyId().equals(companyId)
                        && granted.getDeleteFlag().equals(RobotConstants.DELETE_FLAG_ACTIVE)) {
                    roleIds.add(granted.getId());
                    scopes.add(granted.getDataScope());
                }
            }
        }

        // 范围取最大（all > custom > department_below > department > self）
        return List.of(new DataScopeVO("*", strongest(scopes)));
    }

    private List<SysRole> rolesOfUser(String userId, String companyId) {
        List<SysRole> companyRoles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCompanyId, companyId)
                .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .eq(SysRole::getStatusFlag, 1));
        if (companyRoles.isEmpty()) {
            return List.of();
        }
        Set<String> boundRoleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>()
                                .eq(SysUserRole::getUserId, userId)
                                .eq(SysUserRole::getDeleteFlag,
                                        RobotConstants.DELETE_FLAG_ACTIVE))
                .stream().map(SysUserRole::getRoleId).collect(java.util.stream.Collectors.toSet());
        return companyRoles.stream()
                .filter(role -> boundRoleIds.contains(role.getId()))
                .toList();
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
