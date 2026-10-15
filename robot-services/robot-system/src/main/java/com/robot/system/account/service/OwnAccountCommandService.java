package com.robot.system.account.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.account.ProfileUpdateRequest;
import com.robot.system.api.entity.SysUser;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.service.UserWriteValidation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 本人全局账号写用例；与公司成员部门、状态、角色编排分开，但审计/epoch仍同事务。 */
@Service
@RequiredArgsConstructor
public class OwnAccountCommandService {
    private final SysUserMapper users;
    private final PermissionGrantPolicy policy;
    private final BCryptPasswordEncoder passwordEncoder;
    private final PermissionChangeRecorder recorder;

    /**
     * 本人提供正确原密码后替换摘要；撤销版本与成功审计同事务。
     *
     * @param userId 平台账号ID
     * @param oldPassword 本人当前密码，仅用于验证
     * @param newPassword 待校验的新密码，禁止日志
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(String userId, String oldPassword, String newPassword) {
        requireSelf(userId);
        UserWriteValidation.validatePassword(newPassword);
        SysUser user = ServiceAssert.notNull(users.selectById(userId), ResultCode.USER_NOT_FOUND);
        ServiceAssert.isTrue(
                passwordEncoder.matches(
                        oldPassword == null ? "" : oldPassword, user.getPasswordHash()),
                ResultCode.PARAM_INVALID,
                "原密码不正确");
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setSecurityVersion(
                Math.addExact(
                        user.getSecurityVersion() == null ? 0L : user.getSecurityVersion(), 1L));
        ServiceAssert.isTrue(users.updateById(user) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        recorder.record("change_password", userId, null);
    }

    /**
     * 只更新显式提交的本人资料；明确null清空邮件/电话，省略保留原值。
     *
     * @param userId 平台账号ID
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(String userId, ProfileUpdateRequest request) {
        requireSelf(userId);
        UserWriteValidation.validateProfile(
                request.getNickname(), request.getEmail(), request.getPhone());
        ServiceAssert.isTrue(
                !request.isNicknamePresent() || request.getNickname() != null,
                ResultCode.PARAM_INVALID,
                "昵称不可清空");
        SysUser user = ServiceAssert.notNull(users.selectById(userId), ResultCode.USER_NOT_FOUND);
        if (request.isNicknamePresent()) {
            user.setNickname(request.getNickname());
        }
        if (request.isEmailPresent()) {
            user.setEmail(request.getEmail());
        }
        if (request.isPhonePresent()) {
            user.setPhone(request.getPhone());
        }
        LambdaUpdateWrapper<SysUser> patch =
                new LambdaUpdateWrapper<SysUser>()
                        .eq(SysUser::getId, userId)
                        .eq(SysUser::getRevision, user.getRevision())
                        .eq(SysUser::getDeleteFlag, 1);
        if (request.isEmailPresent() && request.getEmail() == null) {
            patch.set(SysUser::getEmail, null);
        }
        if (request.isPhonePresent() && request.getPhone() == null) {
            patch.set(SysUser::getPhone, null);
        }
        ServiceAssert.isTrue(users.update(user, patch) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        recorder.record("profile_update", userId, null);
    }

    private void requireSelf(String userId) {
        ServiceAssert.notBlank(AuthUtil.getLoginUserId(), ResultCode.UNAUTHORIZED);
        ServiceAssert.isTrue(AuthUtil.getLoginUserId().equals(userId), ResultCode.FORBIDDEN);
        policy.requireCompany();
    }
}
