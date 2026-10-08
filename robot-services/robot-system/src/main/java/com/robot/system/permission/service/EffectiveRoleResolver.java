package com.robot.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户有效角色解析器：API 权限、菜单、按钮、数据范围的<b>唯一事实源</b>（RC01/RC02 整改）。
 *
 * <p>有效角色 = （用户有效绑定 ∩ 本公司启用角色）∪ 当前生效的临时授权角色（限本公司）。
 * <b>超管只能来自该用户自己的有效集合</b>——公司存在超管角色而用户未绑定时，
 * 用户不得获得任何通配权限（历史缺陷 RC01 的根因即在此处误用全公司角色判定）。
 */
@Component
@RequiredArgsConstructor
public class EffectiveRoleResolver {

    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;

    /** 解析结果：superAdmin 仅由用户自身有效角色决定。 */
    public record EffectiveRoles(boolean superAdmin, List<SysRole> roles) {

        public static final EffectiveRoles EMPTY = new EffectiveRoles(false, List.of());

        public boolean isEmpty() {
            return roles.isEmpty();
        }

        public Set<String> roleIds() {
            return roles.stream().map(SysRole::getId).collect(Collectors.toSet());
        }
    }

    public EffectiveRoles resolve(String userId, String companyId) {
        // 代码级防御过滤（不单依赖 SQL 条件，防绕过 wrapper 的查询路径）
        List<SysRole> companyRoles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getCompanyId, companyId)
                        .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                        .eq(SysRole::getStatusFlag, 1))
                .stream()
                .filter(role -> role.getDeleteFlag() != null
                        && role.getDeleteFlag().equals(RobotConstants.DELETE_FLAG_ACTIVE)
                        && role.getStatusFlag() != null
                        && role.getStatusFlag() == 1)
                .toList();
        if (companyRoles.isEmpty()) {
            return EffectiveRoles.EMPTY;
        }

        // 交集：用户的绑定必须落在目标公司的启用角色上（跨公司角色天然被排除）
        Set<String> boundRoleIds = activeBoundRoleIds(userId);
        List<SysRole> effective = companyRoles.stream()
                .filter(role -> boundRoleIds.contains(role.getId()))
                .collect(Collectors.toCollection(ArrayList::new));

        // 临时授权并入（仅本公司角色、当前时间窗内）
        for (String tempRoleId : activeTempAuthRoleIds(userId)) {
            if (effective.stream().noneMatch(role -> role.getId().equals(tempRoleId))
                    && companyRoles.stream().anyMatch(role -> role.getId().equals(tempRoleId))) {
                companyRoles.stream()
                        .filter(role -> role.getId().equals(tempRoleId))
                        .findFirst()
                        .ifPresent(effective::add);
            }
        }

        boolean superAdmin = effective.stream().anyMatch(role ->
                RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(role.getRoleCode()));
        return new EffectiveRoles(superAdmin, List.copyOf(effective));
    }

    private Set<String> activeBoundRoleIds(String userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId)
                        .eq(SysUserRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream().map(SysUserRole::getRoleId)
                .collect(Collectors.toSet());
    }

    private Set<String> activeTempAuthRoleIds(String userId) {
        LocalDateTime now = LocalDateTime.now();
        return tempAuthorizationMapper.selectList(new LambdaQueryWrapper<SysTempAuthorization>()
                        .eq(SysTempAuthorization::getUserId, userId)
                        .eq(SysTempAuthorization::getStatusFlag, 1)
                        .eq(SysTempAuthorization::getDeleteFlag,
                                RobotConstants.DELETE_FLAG_ACTIVE)
                        .le(SysTempAuthorization::getEffectFrom, now)
                        .ge(SysTempAuthorization::getEffectTo, now))
                .stream().map(SysTempAuthorization::getRoleId)
                .collect(Collectors.toSet());
    }
}
