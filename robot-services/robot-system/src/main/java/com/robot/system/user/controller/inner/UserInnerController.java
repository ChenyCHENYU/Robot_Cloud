package com.robot.system.user.controller.inner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.Result;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.service.context.UserContextAssembler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户内部接口（/inner/**：仅供服务间 Feign 调用；网关已屏蔽外部访问）。
 */
@RestController
@RequiredArgsConstructor
public class UserInnerController {

    private final SysUserMapper userMapper;
    private final SysUserCompanyMapper userCompanyMapper;
    private final UserContextAssembler contextAssembler;

    @GetMapping("/inner/user/by-username")
    public Result<RemoteUserDTO> getByUsername(@RequestParam("username") String username) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .last("LIMIT 1"));
        if (user == null) {
            return Result.success(null);
        }
        return Result.success(toRemote(user));
    }

    @GetMapping("/inner/user/{userId}")
    public Result<RemoteUserDTO> getById(@PathVariable("userId") String userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || !user.getDeleteFlag().equals(RobotConstants.DELETE_FLAG_ACTIVE)) {
            return Result.success(null);
        }
        return Result.success(toRemote(user));
    }

    @GetMapping("/inner/user/{userId}/contexts")
    public Result<List<RemoteUserContextDTO>> getContexts(@PathVariable("userId") String userId) {
        List<SysUserCompany> memberships = userCompanyMapper.selectList(
                new LambdaQueryWrapper<SysUserCompany>()
                        .eq(SysUserCompany::getUserId, userId)
                        .eq(SysUserCompany::getStatusFlag, 1)
                        .eq(SysUserCompany::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE));
        // 批量装配（公司/角色各一次查询）并过滤无效/停用公司
        return Result.success(contextAssembler.assembleAll(userId, memberships));
    }

    static RemoteUserDTO toRemote(SysUser user) {
        return RemoteUserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .passwordHash(user.getPasswordHash())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .phone(user.getPhone())
                .departmentId(user.getDepartmentId())
                .statusFlag(user.getStatusFlag())
                .build();
    }
}
