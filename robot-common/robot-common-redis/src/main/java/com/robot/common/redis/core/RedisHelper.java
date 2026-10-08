package com.robot.common.redis.core;

import com.robot.common.redis.key.RedisKeys;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Redis 操作门面：收敛团队规范，禁止业务代码直接持有 RedisTemplate 裸写。
 *
 * <p>强制约束（wl-skills-bd B13/B14/B15）：
 * <ul>
 *   <li>所有写入必须携带 TTL —— 本类不提供无 TTL 的 set 方法；</li>
 *   <li>TTL 支持随机抖动（防雪崩），空值占位短 TTL（防穿透）；</li>
 *   <li>删除大 Key 走 {@code unlink}（异步，不阻塞主线程）；</li>
 *   <li>禁止 KEYS * —— 扫描一律由运维工具执行 SCAN。</li>
 * </ul>
 */
public class RedisHelper {

    /** 空值占位：命中该值表示「数据库确认不存在」，避免穿透。 */
    public static final String NULL_PLACEHOLDER = "@robot:null@";

    private final StringRedisTemplate stringTemplate;
    private final RedisTemplate<String, Object> objectTemplate;

    /** 可选互斥重建锁（防击穿）；由装配层注入，未注入时退化为直接回源。 */
    private volatile com.robot.common.redis.lock.LockTemplate lockTemplate;

    public RedisHelper(StringRedisTemplate stringTemplate,
                       RedisTemplate<String, Object> objectTemplate) {
        this.stringTemplate = stringTemplate;
        this.objectTemplate = objectTemplate;
    }

    public void setLockTemplate(com.robot.common.redis.lock.LockTemplate lockTemplate) {
        this.lockTemplate = lockTemplate;
    }

    /* ==================== 字符串值 ==================== */

    public void setString(String key, String value, Duration ttl) {
        requireTtl(ttl);
        stringTemplate.opsForValue().set(key, value, withJitter(ttl));
    }

    public Optional<String> getString(String key) {
        return Optional.ofNullable(stringTemplate.opsForValue().get(key));
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(stringTemplate.unlink(key));
    }

    /** 过期时间（Key 不存在返回 -1；无过期返回 -2）。 */
    public long ttlSeconds(String key) {
        Long ttl = stringTemplate.getExpire(key);
        return ttl == null ? -2 : ttl;
    }

    /* ==================== 对象值（JSON) ==================== */

    public void setObject(String key, Object value, Duration ttl) {
        requireTtl(ttl);
        objectTemplate.opsForValue().set(key, value, withJitter(ttl));
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getObject(String key, Class<T> type) {
        Object value = objectTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }

    /* ==================== 缓存三件套：读穿透 / 写回填 ==================== */

    /**
     * 缓存读取（防穿透 + 防击穿版）：miss 时执行加载器回填。
     * 加载器返回 null → 写入短 TTL 空值占位；占位命中 → 返回 null；
     * 注入 LockTemplate 后，miss 回源在互斥锁内执行（热 Key 过期瞬间只放一个请求打 DB）。
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        requireTtl(ttl);
        Object cached = objectTemplate.opsForValue().get(key);
        if (cached != null) {
            return NULL_PLACEHOLDER.equals(cached) ? null : (T) cached;
        }
        if (lockTemplate != null) {
            return lockTemplate.execute(com.robot.common.redis.key.RedisKeys.rebuildLock(key),
                    () -> {
                        // 双检：持锁期间可能已被并发请求回填
                        Object filled = objectTemplate.opsForValue().get(key);
                        if (filled != null) {
                            return NULL_PLACEHOLDER.equals(filled) ? null : (T) filled;
                        }
                        return loadAndFill(key, ttl, loader);
                    });
        }
        return loadAndFill(key, ttl, loader);
    }

    @SuppressWarnings("unchecked")
    private <T> T loadAndFill(String key, Duration ttl, Supplier<T> loader) {
        T loaded = loader.get();
        if (loaded == null) {
            objectTemplate.opsForValue().set(key, NULL_PLACEHOLDER,
                    RedisKeys.TTL_NULL_PLACEHOLDER);
            return null;
        }
        objectTemplate.opsForValue().set(key, loaded, withJitter(ttl));
        return loaded;
    }

    /** 缓存一致性（延迟双删由调用方编排）：本方法负责删缓存。 */
    public void evict(String key) {
        stringTemplate.unlink(key);
        objectTemplate.unlink(key);
    }

    /**
     * 版本自增（惰性失效方案）：key 不存在时初始化为 1（7 天 TTL，避免无过期 Key）。
     * 版本回落仅导致一次缓存重建，不影响正确性。
     */
    public long bumpVersion(String versionKey) {
        String current = stringTemplate.opsForValue().get(versionKey);
        long next = current == null ? 1L : Long.parseLong(current) + 1L;
        stringTemplate.opsForValue().set(versionKey, String.valueOf(next),
                java.time.Duration.ofDays(7));
        return next;
    }

    /** 读取当前版本（缺失初始化为 1）。 */
    public long readVersion(String versionKey) {
        String current = stringTemplate.opsForValue().get(versionKey);
        if (current != null) {
            try {
                return Long.parseLong(current);
            } catch (NumberFormatException ignored) {
                // 脏值回落重建
            }
        }
        stringTemplate.opsForValue().set(versionKey, "1", java.time.Duration.ofDays(7));
        return 1L;
    }

    /* ==================== 工具 ==================== */

    private static void requireTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "Redis 写入必须携带正 TTL（wl-skills-bd B13）；持久 Key 需在 redis-persistent-keys.json 登记并走 StringRedisTemplate 显式操作");
        }
    }

    /** TTL 加随机抖动（0~60s），防大批 Key 同时过期造成雪崩。 */
    public static Duration withJitter(Duration ttl) {
        long jitter = ThreadLocalRandom.current().nextLong(
                Math.max(1, RedisKeys.TTL_JITTER_BOUND.getSeconds()));
        return ttl.plusSeconds(jitter);
    }
}
