package com.robot.auth.authn.service;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.core.util.Digests;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.redis.key.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 跨实例固定短窗口：先过总容量，再创建账号/来源计数；不永久锁号，不信任来源代理头。 */
@Component
public final class AuthAttemptGate {
    private static final int DEFAULT_ACCOUNT_LIMIT = 10;
    private static final int DEFAULT_SOURCE_LIMIT = 200;
    private static final int DEFAULT_TOTAL_LIMIT = 1000;
    private static final int MAX_CONFIGURED_LIMIT = 10_000;
    private static final int MAX_USER_ID_CHARACTERS = 64;
    private static final int MAX_USER_ID_UTF16_UNITS = MAX_USER_ID_CHARACTERS * 2;
    private final RedisHelper redis;
    private final Clock clock;
    private final int accountLimit;
    private final int sourceLimit;
    private final int totalLimit;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Bind bounded admission limits and use shared Redis as the cross-instance authority.
     *
     * @param redis shared counter storage
     * @param account attempts per account and short window
     * @param source attempts per actual network source and window
     * @param total total admissions before allocating identity counters
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AuthAttemptGate(
            RedisHelper redis,
            @org.springframework.beans.factory.annotation.Value(
                            "${robot.auth.attempt.account-limit:10}")
                    int account,
            @org.springframework.beans.factory.annotation.Value(
                            "${robot.auth.attempt.source-limit:200}")
                    int source,
            @org.springframework.beans.factory.annotation.Value(
                            "${robot.auth.attempt.total-limit:1000}")
                    int total) {
        this(redis, Clock.systemUTC(), account, source, total);
    }

    AuthAttemptGate(RedisHelper redis) {
        this(
                redis,
                Clock.systemUTC(),
                DEFAULT_ACCOUNT_LIMIT,
                DEFAULT_SOURCE_LIMIT,
                DEFAULT_TOTAL_LIMIT);
    }

    AuthAttemptGate(
            RedisHelper redis, Clock clock, int accountLimit, int sourceLimit, int totalLimit) {
        if (accountLimit < 1
                || accountLimit > MAX_CONFIGURED_LIMIT
                || sourceLimit < 1
                || sourceLimit > MAX_CONFIGURED_LIMIT
                || totalLimit < 1
                || totalLimit > MAX_CONFIGURED_LIMIT) {
            throw new IllegalStateException("认证短窗口配置无效");
        }
        this.redis = redis;
        this.clock = clock;
        this.accountLimit = accountLimit;
        this.sourceLimit = sourceLimit;
        this.totalLimit = totalLimit;
    }

    /**
     * Check an account attempt using the actual servlet network source, never proxy headers.
     *
     * @param username validated login name
     */
    public void check(String username) {
        String source = "unknown";
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            source = attributes.getRequest().getRemoteAddr();
        }
        check(username, source);
    }

    /**
     * Apply the account budget to the stored identity, independently of username collation aliases.
     *
     * @param userId canonical ID returned by trusted account supply, never the submitted username
     * @throws ServiceException if identity supply is invalid, the budget is full or Redis fails
     */
    public void checkResolvedAccount(String userId) {
        if (!validUserId(userId)) {
            throw new ServiceException(ResultCode.FAILED, "系统账号身份供给无效，请稍后重试");
        }
        String prefix =
                RedisKeys.base("auth") + ":attempt:" + clock.millis() / WINDOW.toMillis() + ":";
        try {
            increment(prefix + "resolved-account:" + Digests.sha256Hex(userId), accountLimit);
        } catch (ServiceException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServiceException(ResultCode.FAILED, "认证防护暂不可用，请稍后重试");
        }
    }

    private static boolean validUserId(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= MAX_USER_ID_UTF16_UNITS
                && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.codePointCount(0, value.length()) <= MAX_USER_ID_CHARACTERS
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    /**
     * Check unauthenticated ticket/refresh commands against shared total and source capacity.
     *
     * @throws ServiceException if the short window is full or its authority is unavailable
     */
    public void checkAnonymous() {
        String source = "unknown";
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            source = attributes.getRequest().getRemoteAddr();
        }
        String prefix =
                RedisKeys.base("auth") + ":attempt:" + clock.millis() / WINDOW.toMillis() + ":";
        try {
            increment(prefix + "total", totalLimit);
            increment(prefix + "source:" + Digests.sha256Hex(source), sourceLimit);
        } catch (ServiceException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServiceException(ResultCode.FAILED, "认证防护暂不可用，请稍后重试");
        }
    }

    void check(String username, String source) {
        String prefix =
                RedisKeys.base("auth") + ":attempt:" + clock.millis() / WINDOW.toMillis() + ":";
        try {
            increment(prefix + "total", totalLimit);
            increment(
                    prefix + "account:" + Digests.sha256Hex(username.toLowerCase(Locale.ROOT)),
                    accountLimit);
            increment(prefix + "source:" + Digests.sha256Hex(source), sourceLimit);
        } catch (ServiceException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServiceException(ResultCode.FAILED, "认证防护暂不可用，请稍后重试");
        }
    }

    private void increment(String key, int limit) {
        if (!redis.incrementWithinLimit(key, limit, WINDOW.multipliedBy(2))) {
            throw new ServiceException(ResultCode.TOO_MANY_REQUESTS);
        }
    }
}
