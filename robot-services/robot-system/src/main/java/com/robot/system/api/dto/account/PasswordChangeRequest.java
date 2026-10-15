package com.robot.system.api.dto.account;

import com.robot.common.web.validation.ValidNewPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/** 原密码校验与新密码白名单；账号身份仅取登录态。 */
@Data
public class PasswordChangeRequest {
    @NotBlank
    @Size(max = 32)
    @ToString.Exclude
    private String oldPassword;

    @NotBlank
    @Size(min = 6, max = 32)
    @ValidNewPassword
    @ToString.Exclude
    private String newPassword;
}
