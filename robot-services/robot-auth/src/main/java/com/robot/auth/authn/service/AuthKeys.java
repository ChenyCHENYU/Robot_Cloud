package com.robot.auth.authn.service;

import com.robot.common.redis.key.RedisKeys;
import java.time.Duration;

/** 认证域 Redis Key 契约（业务键归属服务自身定义，不进 common）。 */
public final class AuthKeys {

    private AuthKeys() {}

    /** 登录票据（一次性，5 分钟）。 */
    public static final Duration TTL_LOGIN_TICKET = Duration.ofMinutes(5);

    /** 刷新令牌（7 天，轮换式）。 */
    public static final Duration TTL_REFRESH_TOKEN = Duration.ofDays(7);

    /** schema v2：原子族/审计事件使用同一slot；旧schema需重新登录。 */
    static String sessionBase() {
        return RedisKeys.base("auth") + ":{sessions}:v2";
    }

    static String auditHash() {
        return sessionBase() + ":audit:payloads";
    }

    static String auditDue() {
        return sessionBase() + ":audit:due";
    }

    /**
     * Hash a one-time login ticket into the isolated session schema.
     *
     * @param ticket exact ticket secret
     * @return isolated ticket key
     */
    public static String loginTicket(String ticket) {
        return sessionBase() + ":login-ticket:" + AuthTokenSecrets.digest(ticket);
    }

    /**
     * Locate a refresh index without embedding the credential in a Redis key.
     *
     * @param token exact refresh secret
     * @return isolated digest index key
     */
    public static String refreshToken(String token) {
        return refreshDigest(AuthTokenSecrets.digest(token));
    }

    /** 刷新摘要索引，旧索引在轮换后清理，TTL 兜底过期。 */
    static String refreshDigest(String digest) {
        return sessionBase() + ":refresh-token:" + RedisKeys.requireSafe(digest);
    }

    /**
     * 刷新族唯一权威状态；CAS及其审计事件位于同slot，注销也是原子状态/事件命令。
     *
     * @param familyId 服务端生成的族标识
     * @return 当前隔离环境的族权威键
     */
    public static String family(String familyId) {
        return sessionBase() + ":family:" + RedisKeys.requireSafe(familyId);
    }

    /** 即使 access 已自然过期，持有其原文仍可定位并注销所属刷新族。 */
    static String accessFamily(String accessToken) {
        return accessDigest(AuthTokenSecrets.digest(accessToken));
    }

    static String accessDigest(String digest) {
        return sessionBase() + ":access-family:" + RedisKeys.requireSafe(digest);
    }
}
