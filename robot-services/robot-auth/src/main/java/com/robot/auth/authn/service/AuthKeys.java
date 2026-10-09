package com.robot.auth.authn.service;

import com.robot.common.redis.key.RedisKeys;

import java.time.Duration;

/**
 * 认证域 Redis Key 契约（业务键归属服务自身定义，不进 common）。
 */
public final class AuthKeys {

    private AuthKeys() {
    }

    /** 登录票据（一次性，5 分钟）。 */
    public static final Duration TTL_LOGIN_TICKET = Duration.ofMinutes(5);

    /** 刷新令牌（7 天，轮换式）。 */
    public static final Duration TTL_REFRESH_TOKEN = Duration.ofDays(7);

    public static String loginTicket(String ticket) {
        return RedisKeys.base("auth") + ":login-ticket:" + AuthTokenSecrets.digest(ticket);
    }

    public static String refreshToken(String token) {
        return refreshDigest(AuthTokenSecrets.digest(token));
    }

    /** 刷新摘要索引，旧索引在轮换后清理，TTL 兜底过期。 */
    static String refreshDigest(String digest) {
        return RedisKeys.base("auth") + ":refresh-token:" + RedisKeys.requireSafe(digest);
    }

    /** 刷新族唯一权威状态；CAS 和 GETDEL 均只操作此单 key。 */
    public static String family(String familyId) {
        return RedisKeys.base("auth") + ":family:" + RedisKeys.requireSafe(familyId);
    }

    /** 即使 access 已自然过期，持有其原文仍可定位并注销所属刷新族。 */
    static String accessFamily(String accessToken) {
        return accessDigest(AuthTokenSecrets.digest(accessToken));
    }

    static String accessDigest(String digest) {
        return RedisKeys.base("auth") + ":access-family:" + RedisKeys.requireSafe(digest);
    }
}
