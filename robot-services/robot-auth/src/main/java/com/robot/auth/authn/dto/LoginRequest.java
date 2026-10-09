package com.robot.auth.authn.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录请求（前端 LoginRequest 契约：username / password / contextId 可选）。
 */
@Data
@NoArgsConstructor
public class LoginRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    @lombok.ToString.Exclude
    private String password;

    /** 登录前选择的公司上下文 ID；认证服务必须再次校验成员关系。 */
    private String contextId;
}
