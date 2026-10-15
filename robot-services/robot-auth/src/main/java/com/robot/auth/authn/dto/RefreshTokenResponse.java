package com.robot.auth.authn.dto;

import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 刷新令牌响应（前端 RefreshTokenResponse 契约）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenResponse implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    @lombok.ToString.Exclude private String token;

    @lombok.ToString.Exclude private String refreshToken;

    private long expiresIn;
}
