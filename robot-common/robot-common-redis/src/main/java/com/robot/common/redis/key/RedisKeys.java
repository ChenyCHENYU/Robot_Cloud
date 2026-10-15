package com.robot.common.redis.key;

import com.robot.common.core.constant.RobotConstants;
import java.time.Duration;

/** Redis 通用键约定：服务、环境与业务域明确分层，动态段严格校验而非静默规范化。 */
public final class RedisKeys {
    private static volatile String configuredEnvironment;
    private static final int MAX_SEGMENT_LENGTH = 64;

    private RedisKeys() {}
    /** 非负期限抖动的最大秒数。 */
    public static final Duration TTL_JITTER_BOUND = Duration.ofSeconds(60);
    /** 已确认数据库结果为空时的短占位期限。 */
    public static final Duration TTL_NULL_PLACEHOLDER = Duration.ofMinutes(2);
    /**
     * 构建业务缓存键。
     *
     * @param service 服务标识
     * @param domain 业务域
     * @param id 规范化业务标识
     * @return 包含环境与服务边界的缓存键
     */
    public static String businessCache(String service, String domain, String id) {
        return base(service) + ":" + requireSafe(domain) + ":" + requireSafe(id);
    }
    /**
     * 构建 Redisson 分布式锁键。
     *
     * @param service 服务标识
     * @param business 临界区业务标识
     * @param id 规范化资源标识
     * @return 包含环境与服务边界的锁键
     */
    public static String lock(String service, String business, String id) {
        return base(service) + ":lock:" + requireSafe(business) + ":" + requireSafe(id);
    }
    /**
     * 构建不直接包含原缓存键的互斥重建锁。
     *
     * @param cacheKey 待重建的完整缓存键
     * @return 互斥重建锁键
     */
    public static String rebuildLock(String cacheKey) {
        return base("common") + ":lock:rebuild:" + Integer.toHexString(cacheKey.hashCode());
    }
    /**
     * 构建服务命名空间根。
     *
     * @param service 服务标识
     * @return 包含环境边界的服务命名空间
     */
    public static String base(String service) {
        return RobotConstants.REDIS_KEY_PREFIX + ":" + envSegment() + ":" + requireSafe(service);
    }
    /** 优先采用 Spring 绑定值，其次 JVM 属性、环境变量，最后 local。 */
    static String envSegment() {
        if (configuredEnvironment != null) {
            return configuredEnvironment;
        }
        String env = System.getProperty("robot.env");
        if (env == null || env.isBlank()) {
            env = System.getenv("ROBOT_ENV");
        }
        if (env == null || env.isBlank()) {
            env = "local";
        }
        return validatedEnvironment(env);
    }
    /**
     * Spring 绑定与键生成采用同一严格环境格式。
     *
     * @param env 规范小写环境标识
     */
    public static void configureEnvironment(String env) {
        configuredEnvironment = validatedEnvironment(env);
    }

    private static String validatedEnvironment(String env) {
        if (env == null || !env.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalStateException("robot.env必须是规范小写环境标识");
        }
        return env;
    }
    /**
     * 校验动态键段，拒绝空值、非法字符与超长输入。
     *
     * @param segment 原始动态键段
     * @return 去除首尾空白后通过校验的键段
     * @throws IllegalArgumentException 空值、非法字符或长度超过 64
     */
    public static String requireSafe(String segment) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException("Redis Key 动态段禁止为空");
        }
        String trimmed = segment.trim();
        if (trimmed.length() > MAX_SEGMENT_LENGTH || !trimmed.matches("[A-Za-z0-9_.\\-]+")) {
            throw new IllegalArgumentException("Redis Key 动态段含非法字符或超长: " + trimmed);
        }
        return trimmed;
    }
}
