package com.robot.system.api.dto.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 自助资料白名单；身份、公司、权限和治理字段不接收客户端赋值。 */
@Data
public class ProfileUpdateRequest {
    @NotBlank
    @Size(max = 30)
    private String nickname;

    @Email
    @Size(max = 100)
    private String email;

    @Size(max = 20)
    private String phone;
}
