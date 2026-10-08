package com.robot.auth.authn.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/** 刷新令牌响应（前端 RefreshTokenResponse 契约）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String token;

    private String refreshToken;

    private long expiresIn;
}
