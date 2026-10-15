package com.robot.auth.authn.dto;

import jakarta.validation.constraints.NotBlank;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 激活企业上下文请求（前端 ActivateAuthContextRequest 契约：loginTicket + contextId）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivateContextRequest implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    @NotBlank(message = "登录票据不能为空")
    @jakarta.validation.constraints.Size(min = 64, max = 64)
    @lombok.ToString.Exclude
    private String loginTicket;

    @NotBlank(message = "上下文 ID 不能为空")
    @jakarta.validation.constraints.Size(max = 64)
    private String contextId;
}
