package com.robot.common.core.util;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import java.nio.charset.StandardCharsets;

/** 新密码的统一写入规则；不改变存量密码的验证解释，不截断或规范化凭据。 */
public final class PasswordPolicy {
    public static final int MAX_UTF8_BYTES = 72;
    public static final int MIN_CHARACTERS = 6;
    public static final int MAX_CHARACTERS = 32;

    private PasswordPolicy() {}

    /**
     * Validates a new credential without truncating or normalizing it.
     *
     * @param value candidate credential; null fails validation
     * @return whether character, Unicode and BCrypt byte boundaries all hold
     */
    public static boolean validNewPassword(String value) {
        return value != null
                && !value.isBlank()
                && value.length() >= MIN_CHARACTERS
                && value.length() <= MAX_CHARACTERS
                && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }

    /**
     * Rejects credentials that cannot be stored safely by BCrypt.
     *
     * @param value candidate credential
     * @throws ServiceException when the credential violates the write policy
     */
    public static void requireNewPassword(String value) {
        if (!validNewPassword(value)) {
            throw new ServiceException(ResultCode.USER_PASSWORD_WEAK, "密码长度6~32且UTF-8编码不得超过72字节");
        }
    }
}
