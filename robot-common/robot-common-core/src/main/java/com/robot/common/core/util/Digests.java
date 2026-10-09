package com.robot.common.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** UTF-8 SHA-256 标识摘要；密码存储仍使用 BCrypt，不使用本工具替代。 */
public final class Digests {
    private Digests() { }

    public static String sha256Hex(String value) {
        Objects.requireNonNull(value, "摘要输入不能为空");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK 不支持 SHA-256", error);
        }
    }
}
