package com.robot.auth.authn.dto;

import jakarta.validation.constraints.NotBlank;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 刷新令牌请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenRequest implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    @NotBlank(message = "刷新令牌不能为空")
    @jakarta.validation.constraints.Size(min = 64, max = 64)
    @lombok.ToString.Exclude
    private String refreshToken;
}
