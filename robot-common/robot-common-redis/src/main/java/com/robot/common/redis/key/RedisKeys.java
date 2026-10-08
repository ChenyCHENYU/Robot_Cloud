package com.robot.common.redis.key;

import com.robot.common.core.constant.RobotConstants;

/**
 * Redis Key 构建器：唯一合法的 Key 生成约定（wl-skills-bd standards/20）。
 *
 * <p>规范：{@code robot:{env}:{service}:{domain}:{business}:{id}}
 * <ul>
 *   <li>统一小写、冒号分层，禁止裸 Key（多服务共池防互踩）；</li>
 *   <li>禁止把用户输入直接拼入 Key（防 Key 注入与大 Key），动态段必须先收敛；</li>
 *   <li>本类只提供通用构建能力；业务键（认证/系统域）由各服务自行定义，
 *       避免 common 模块耦合业务语义。</li>
 * </ul>
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** TTL 抖动上限（防雪崩，60 秒内随机）。 */
    public static final java.time.Duration TTL_JITTER_BOUND = java.time.Duration.ofSeconds(60);

    /** 空值占位缓存 TTL（防穿透，2 分钟）。 */
    public static final java.time.Duration TTL_NULL_PLACEHOLDER = java.time.Duration.ofMinutes(2);

    /** 业务缓存通用段：robot:{env}:{service}:{domain}:{id}。 */
    public static String businessCache(String service, String domain, String id) {
        return base(service) + ":" + requireSafe(domain) + ":" + requireSafe(id);
    }

    /** 分布式锁 Key（RLock 专用）。 */
    public static String lock(String service, String business, String id) {
        return base(service) + ":lock:" + requireSafe(business) + ":" + requireSafe(id);
    }

    /** 缓存重建互斥锁（防击穿：热 Key 过期瞬间只放一个请求回源）。 */
    public static String rebuildLock(String cacheKey) {
        return base("common") + ":lock:rebuild:"
                + Integer.toHexString(cacheKey.hashCode());
    }

    /** 服务命名空间根：robot:{env}:{service}。 */
    public static String base(String service) {
        return RobotConstants.REDIS_KEY_PREFIX + ":" + envSegment() + ":" + requireSafe(service);
    }

    /** 优先级：JVM 系统属性 robot.env > 环境变量 ROBOT_ENV > local。 */
    static String envSegment() {
        String env = System.getProperty("robot.env");
        if (env == null || env.isBlank()) {
            env = System.getenv("ROBOT_ENV");
        }
        if (env == null || env.isBlank()) {
            env = "local";
        }
        return env.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    /** 收敛动态段：只允许字母数字、下划线、中划线、点，长度 ≤ 64。 */
    public static String requireSafe(String segment) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException("Redis Key 动态段禁止为空");
        }
        String trimmed = segment.trim();
        if (trimmed.length() > 64 || !trimmed.matches("[A-Za-z0-9_.\\-]+")) {
            throw new IllegalArgumentException("Redis Key 动态段含非法字符或超长: " + trimmed);
        }
        return trimmed;
    }
}
