package com.robot.system.user.service;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.dto.user.UserSaveRequest;
import com.robot.system.api.entity.SysUser;

import java.util.Objects;

/**
 * 用户写入的纯值校验与资料变更判定；成员身份、授权上界及事务编排由UserService负责。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
final class UserWriteValidation {
    static final String BUILTIN_ADMIN_USERNAME = "admin";

    private UserWriteValidation() {
    }

    static void validateUsername(String value) {
        ServiceAssert.isTrue(value != null && value.matches("^[a-zA-Z0-9_]{3,30}$"),
                ResultCode.PARAM_INVALID, "用户名只允许3~30位字母数字下划线");
    }

    static void assertUnchangedUsername(String actual, String requested) {
        ServiceAssert.isTrue(Objects.equals(actual, requested), ResultCode.PARAM_INVALID, "用户名不可修改");
    }

    static void assertMutableAccount(String username, String action) {
        if (BUILTIN_ADMIN_USERNAME.equals(username)) {
            throw new ServiceException(ResultCode.FORBIDDEN, "内置管理员账号禁止" + action);
        }
    }

    static void validatePassword(String value) {
        ServiceAssert.isTrue(value != null && !value.isBlank() && value.length() >= 6 && value.length() <= 32,
                ResultCode.USER_PASSWORD_WEAK, "密码长度6~32且不可为空");
    }

    static void validateStatus(Integer value) {
        ServiceAssert.isTrue(value == null || value == 0 || value == 1, ResultCode.PARAM_INVALID, "状态只允许0/1");
    }

    static void validateProfile(String nickname, String email, String phone) {
        ServiceAssert.isTrue(nickname == null || !nickname.isBlank() && nickname.length() <= 30,
                ResultCode.PARAM_INVALID, "昵称不可空且最长30");
        ServiceAssert.isTrue((email == null || email.length() <= 100) && (phone == null || phone.length() <= 20),
                ResultCode.PARAM_INVALID, "邮箱或电话超长");
    }

    static void assertRevision(Integer actual, Integer expected, String field) {
        ServiceAssert.notNull(expected, ResultCode.PARAM_INVALID, "修改必须提供" + field);
        ServiceAssert.isTrue(expected >= 0, ResultCode.PARAM_INVALID, field + "不能小于0");
        ServiceAssert.isTrue(expected.equals(actual), ResultCode.DATA_CONCURRENT_CONFLICT);
    }

    static boolean hasProfileChanges(SysUser user, UserSaveRequest request) {
        return !Objects.equals(user.getNickname(), request.getNickname())
                || !Objects.equals(user.getEmail(), request.getEmail())
                || !Objects.equals(user.getPhone(), request.getPhone())
                || !Objects.equals(user.getDepartmentId(), request.getDepartmentId());
    }

}
