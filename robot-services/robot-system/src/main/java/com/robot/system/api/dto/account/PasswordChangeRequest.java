package com.robot.system.api.dto.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 原密码校验与新密码白名单；账号身份仅取登录态。 */
@Data
public class PasswordChangeRequest {
    @NotBlank
    @Size(max = 32)
    private String oldPassword;

    @NotBlank
    @Size(min = 6, max = 32)
    private String newPassword;
}
