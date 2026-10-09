package com.robot.common.redis.core;

import com.robot.common.redis.key.RedisKeys;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Optional;
import java.util.List;
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

    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]); "
                    + "if v then redis.call('DEL', KEYS[1]); end; return v", String.class);
    private static final DefaultRedisScript<Long> COMPARE_AND_SET = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]); return 1; "
                    + "end; return 0", Long.class);
    private static final DefaultRedisScript<Long> INCREMENT_VERSION = new DefaultRedisScript<>(
            "local v = redis.call('INCR', KEYS[1]); "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[1]); return v", Long.class);
    private static final DefaultRedisScript<Long> READ_VERSION = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]); "
                    + "if not v then redis.call('SET', KEYS[1], '1', 'PX', ARGV[1]); return 1; end; "
                    + "local n = tonumber(v); if not n or n < 1 then "
                    + "return redis.error_reply('Invalid version value'); end; return n", Long.class);
    private static final Duration VERSION_TTL = Duration.ofDays(7);
    private static final DefaultRedisScript<Long> ADVANCE_VERSION = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]); local n = ARGV[1]; "
                    + "if v and not string.match(v, '^[1-9][0-9]*$') then "
                    + "return redis.error_reply('Invalid version value'); end; "
                    + "if not v or string.len(n) > string.len(v) or "
                    + "(string.len(n) == string.len(v) and n > v) then "
                    + "redis.call('SET', KEYS[1], n, 'PX', ARGV[2]); return 1; end; "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[2]); return 0", Long.class);

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

    /** 安全凭据的精确期限，不增加缓存抖动。 */
    public void setStringExact(String key, String value, Duration ttl) {
        requireTtl(ttl);
        stringTemplate.opsForValue().set(key, value, ttl);
    }

    /** 原子创建状态；只有不存在时写入，期限不附加抖动。 */
    public boolean setStringIfAbsentExact(String key, String value, Duration ttl) {
        requireTtl(ttl);
        return Boolean.TRUE.equals(stringTemplate.opsForValue().setIfAbsent(key, value, ttl));
    }

    /** 原子取走一次性状态；单 key Lua 兼容集群和不支持 GETDEL 的 Redis。 */
    public Optional<String> consumeString(String key) {
        return Optional.ofNullable(stringTemplate.execute(CONSUME, List.of(key)));
    }

    /** 单 key 状态迁移，比较、更新与期限设置在 Redis 内一次完成。 */
    public boolean compareAndSetStringExact(String key, String expected, String replacement,
                                            Duration ttl) {
        requireTtl(ttl);
        Long changed = stringTemplate.execute(COMPARE_AND_SET, List.of(key), expected,
                replacement, String.valueOf(Math.max(1, ttl.toMillis())));
        return Long.valueOf(1).equals(changed);
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(stringTemplate.unlink(key));
    }

    /** 过期时间（Key 不存在返回 -2；无过期返回 -1）。 */
    public long ttlSeconds(String key) {
        Long ttl = stringTemplate.getExpire(key);
        return ttl == null ? -2 : ttl;
    }

    /* ==================== 对象值（JSON) ==================== */

    public void setObject(String key, Object value, Duration ttl) {
        requireTtl(ttl);
        objectTemplate.opsForValue().set(key, value, withJitter(ttl));
    }

    /** 有授权截止时间的缓存不允许加正抖动。 */
    public void setObjectExact(String key, Object value, Duration ttl) {
        requireTtl(ttl);
        objectTemplate.opsForValue().set(key, value, ttl);
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
            if (NULL_PLACEHOLDER.equals(cached)) {
                return null;
            }
            if (type.isInstance(cached)) {
                return type.cast(cached);
            }
            objectTemplate.unlink(key);
        }
        if (lockTemplate != null) {
            return lockTemplate.execute(com.robot.common.redis.key.RedisKeys.rebuildLock(key),
                    () -> {
                        // 双检：持锁期间可能已被并发请求回填
                        Object filled = objectTemplate.opsForValue().get(key);
                        if (filled != null) {
                            if (NULL_PLACEHOLDER.equals(filled)) {
                                return null;
                            }
                            if (type.isInstance(filled)) {
                                return type.cast(filled);
                            }
                            objectTemplate.unlink(key);
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
     * 原子递增并续期。安全授权的权威版本应持久化在业务数据库；
     * 可过期 Redis 数字只作为缓存通知，不能用于跨重启的唯一 epoch。
     */
    public long bumpVersion(String versionKey) {
        Long next = stringTemplate.execute(INCREMENT_VERSION, List.of(versionKey),
                String.valueOf(VERSION_TTL.toMillis()));
        if (next == null) {
            throw new IllegalStateException("Redis 未返回版本递增结果");
        }
        return next;
    }

    /** 读取/初始化在 Redis 内原子完成；不返回竞争中落败的初始值。 */
    public long readVersion(String versionKey) {
        Long value = stringTemplate.execute(READ_VERSION, List.of(versionKey),
                String.valueOf(VERSION_TTL.toMillis()));
        if (value == null) {
            throw new IllegalStateException("Redis 未返回版本读取结果");
        }
        return value;
    }

    /** 幂等发布数据库版本，迟到事件不能把通知版本写小；字符串比较避免 Lua 大整数精度丢失。 */
    public void advanceVersionTo(String versionKey, long version, Duration ttl) {
        requireTtl(ttl);
        if (version < 1) {
            throw new IllegalArgumentException("缓存版本必须为正数");
        }
        Long changed = stringTemplate.execute(ADVANCE_VERSION, List.of(versionKey),
                String.valueOf(version), String.valueOf(Math.max(1, ttl.toMillis())));
        if (changed == null) {
            throw new IllegalStateException("Redis 未确认版本发布结果");
        }
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
