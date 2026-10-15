package com.robot.auth.authn.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 以完整凭据验证后发现公司，不创建会话或登录票据。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginCompaniesRequest implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    @NotBlank(message = "用户名不能为空")
    @Size(max = 30)
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 1024)
    @lombok.ToString.Exclude
    private String password;
}
