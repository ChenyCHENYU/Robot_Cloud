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
        return RedisKeys.base("auth") + ":login-ticket:" + RedisKeys.requireSafe(ticket);
    }

    public static String refreshToken(String token) {
        return RedisKeys.base("auth") + ":refresh-token:" + RedisKeys.requireSafe(token);
    }
}
