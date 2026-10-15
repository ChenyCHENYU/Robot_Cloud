package com.robot.auth.authn.service;

import com.robot.common.core.util.Digests;
import java.security.SecureRandom;
import java.util.HexFormat;

/** 认证凭据生成及摘要：固定 256 位密码学随机源，不保存刷新令牌原文。 */
final class AuthTokenSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;

    private AuthTokenSecrets() {}

    static String randomToken() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static boolean validSecret(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    static String digest(String value) {
        return Digests.sha256Hex(value);
    }
}
