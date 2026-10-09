package com.robot.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.security.InnerSystemAccess;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.service.context.UserContextAssembler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 认证服务专用的内部账号供给；凭据摘要仅进入 RemoteUserDTO 内部契约。
 * 每个入口独立验证服务身份及可选委托，普通用户管理接口不使用此供给。
 */
@Service
@RequiredArgsConstructor
public class UserSupplyService {

    private final SysUserMapper userMapper;
    private final SysUserCompanyMapper userCompanyMapper;
    private final UserContextAssembler contextAssembler;
    private final InnerSystemAccess innerAccess;

    /** 按登录名供给认证账号；未找到返回null，凭据摘要仅供可信服务使用。 */
    public RemoteUserDTO findAuthenticationUserByUsername(String username) {
        AuthUtil.requireTrustedService();
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .last("LIMIT 1"));
        if (user == null) {
            return null;
        }
        innerAccess.requireUser(user.getId());
        return toRemote(user);
    }

    /** 按账号ID供给认证资料；存在委托时必须与目标账号一致，软删账号返回null。 */
    public RemoteUserDTO findAuthenticationUserById(String userId) {
        innerAccess.requireUser(userId);
        SysUser user = userMapper.selectById(userId);
        if (user == null || !Integer.valueOf(RobotConstants.DELETE_FLAG_ACTIVE).equals(user.getDeleteFlag())) {
            return null;
        }
        return toRemote(user);
    }

    /** 供给当前可登录公司上下文；过滤无效账号、成员、公司和租户并携带撤销版本。 */
    public List<RemoteUserContextDTO> listLoginContexts(String userId) {
        innerAccess.requireUser(userId);
        SysUser user = userMapper.selectById(userId);
        if (user == null || !InnerSystemAccess.active(user.getDeleteFlag(), user.getStatusFlag())) {
            return List.of();
        }
        List<SysUserCompany> memberships = userCompanyMapper.selectList(
                new LambdaQueryWrapper<SysUserCompany>()
                        .eq(SysUserCompany::getUserId, userId)
                        .eq(SysUserCompany::getStatusFlag, 1)
                        .eq(SysUserCompany::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE));
        // 装配器复验当前成员并在可信服务作用域内执行带显式公司谓词的批量查询。
        return contextAssembler.assembleAll(userId, memberships);
    }

    private static RemoteUserDTO toRemote(SysUser user) {
        return RemoteUserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .passwordHash(user.getPasswordHash())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .phone(user.getPhone())
                .departmentId(user.getDepartmentId())
                .statusFlag(user.getStatusFlag())
                .securityVersion(user.getSecurityVersion())
                .build();
    }
}
