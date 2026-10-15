package com.robot.common.redis.core;

import com.robot.common.redis.key.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis 操作门面：正数 TTL、精确认证期限、可靠持久事件与单调通知版本。 业务状态和事件使用单次 Lua 原子提交；队列满或存储故障必须明确失败。 */
public class RedisHelper {
    private static final int MAX_EVENT_BATCH_SIZE = 100;
    private static final long EVENT_CAPACITY_REJECTED = -2;
    private static final long MISSING_KEY_TTL = -2;
    /** 空值占位用于已确认不存在的数据库结果，防止缓存穿透。 */
    public static final String NULL_PLACEHOLDER = "@robot:null@";

    private final StringRedisTemplate stringTemplate;
    private final RedisTemplate<String, Object> objectTemplate;
    private volatile com.robot.common.redis.lock.LockTemplate lockTemplate;

    /**
     * 共享 Spring 容器模板的连接、序列化配置与生命周期，不能克隆基础设施 Bean。
     *
     * @param stringTemplate 容器管理的字符串模板
     * @param objectTemplate 容器管理的安全对象模板
     */
    public RedisHelper(
            StringRedisTemplate stringTemplate, RedisTemplate<String, Object> objectTemplate) {
        this.stringTemplate = stringTemplate;
        this.objectTemplate = objectTemplate;
    }
    /**
     * 注入可选互斥重建锁，避免热键并发回源。
     *
     * @param lockTemplate 容器管理的重建锁门面
     */
    public void setLockTemplate(com.robot.common.redis.lock.LockTemplate lockTemplate) {
        this.lockTemplate = lockTemplate;
    }
    /**
     * 写入带正数期限和随机抖动的字符串。
     *
     * @param key 业务缓存键
     * @param value 缓存值
     * @param ttl 正数有效期限
     */
    public void setString(String key, String value, Duration ttl) {
        requireTtl(ttl);
        stringTemplate.opsForValue().set(key, value, withJitter(ttl));
    }
    /**
     * 读取字符串，键不存在时返回空值。
     *
     * @param key 业务缓存键
     * @return 缓存值或空值
     */
    public Optional<String> getString(String key) {
        return Optional.ofNullable(stringTemplate.opsForValue().get(key));
    }
    /**
     * 安全凭据采用精确期限，不增加缓存抖动。
     *
     * @param key 凭据存储键
     * @param value 完整状态
     * @param ttl 正数精确期限
     */
    public void setStringExact(String key, String value, Duration ttl) {
        requireTtl(ttl);
        stringTemplate.opsForValue().set(key, value, ttl);
    }
    /**
     * 仅在键不存在时原子创建带精确期限的状态。
     *
     * @param key 状态键
     * @param value 初始状态
     * @param ttl 正数精确期限
     * @return 本次成功创建时 true
     */
    public boolean setStringIfAbsentExact(String key, String value, Duration ttl) {
        requireTtl(ttl);
        return Boolean.TRUE.equals(stringTemplate.opsForValue().setIfAbsent(key, value, ttl));
    }
    /**
     * 原子取走一次性状态，避免并发重复消费。
     *
     * @param key 一次性状态键
     * @return 被取走的状态或空值
     */
    public Optional<String> consumeString(String key) {
        return Optional.ofNullable(
                stringTemplate.execute(RedisAtomicScripts.CONSUME, List.of(key)));
    }
    /**
     * 比较、迁移与期限更新在单个 Redis 原子操作内完成。
     *
     * @param key 状态键
     * @param expected 需要精确匹配的旧状态
     * @param replacement 完整新状态
     * @param ttl 新状态期限
     * @return 条件匹配且迁移完成时 true
     */
    public boolean compareAndSetStringExact(
            String key, String expected, String replacement, Duration ttl) {
        requireTtl(ttl);
        return Long.valueOf(1)
                .equals(
                        stringTemplate.execute(
                                RedisAtomicScripts.COMPARE_AND_SET,
                                List.of(key),
                                expected,
                                replacement,
                                String.valueOf(Math.max(1, ttl.toMillis()))));
    }
    /**
     * 原子提交业务状态与可靠事件，容量满时必须保留原状态。
     *
     * @param state 单个业务状态的条件迁移
     * @param command 有界持久队列与待确认事件
     * @return 已确认提交时 true，条件竞争落败时 false
     */
    public boolean stateAndEvent(StateTransition state, EventAppend command) {
        requireTtl(state.ttl());
        EventQueue queue = command.queue();
        EventPayload event = command.event();
        Long result =
                stringTemplate.execute(
                        RedisAtomicScripts.STATE_AND_EVENT,
                        List.of(state.key(), queue.hashKey(), queue.dueKey()),
                        state.expected() == null ? "create" : "replace",
                        state.expected() == null ? "" : state.expected(),
                        state.replacement() == null ? "__delete__" : state.replacement(),
                        String.valueOf(state.ttl().toMillis()),
                        event.id(),
                        event.payload(),
                        String.valueOf(queue.capacity()),
                        String.valueOf(event.dueAtMillis()));
        checkEventCapacity(result);
        return Long.valueOf(1).equals(result);
    }
    /**
     * 原子追加可靠事件，容量或存储故障必须让调用方重试。
     *
     * @param command 有界持久队列与待确认事件
     */
    public void appendEvent(EventAppend command) {
        EventQueue queue = command.queue();
        EventPayload event = command.event();
        checkEventCapacity(
                stringTemplate.execute(
                        RedisAtomicScripts.APPEND_EVENT,
                        List.of(queue.hashKey(), queue.dueKey()),
                        event.id(),
                        event.payload(),
                        String.valueOf(queue.capacity()),
                        String.valueOf(event.dueAtMillis())));
    }
    /**
     * 读取有限批次的到期事件，不扫描全部事件或全部键。
     *
     * @param dueKey 事件时间有序集合
     * @param now 当前毫秒时间戳
     * @param batchSize 本批最多读取数
     * @return 独立的到期事件标识清单
     * @throws IllegalArgumentException 批次不在允许的 1..100 范围内
     */
    public List<String> dueEventIds(String dueKey, long now, int batchSize) {
        if (batchSize < 1 || batchSize > MAX_EVENT_BATCH_SIZE) {
            throw new IllegalArgumentException("事件批次必须1..100");
        }
        var ids = stringTemplate.opsForZSet().rangeByScore(dueKey, 0, now, 0, batchSize);
        return ids == null ? List.of() : List.copyOf(ids);
    }
    /**
     * 读取持久审计载荷，事件确认前不能删除。
     *
     * @param hash 持久事件哈希键
     * @param id 幂等事件标识
     * @return 事件载荷或空值
     */
    public Optional<String> eventPayload(String hash, String id) {
        return Optional.ofNullable((String) stringTemplate.opsForHash().get(hash, id));
    }
    /**
     * 仅当完整载荷仍匹配时原子删除已确认事件。
     *
     * @param hash 持久事件哈希键
     * @param dueKey 投递时间集合键
     * @param id 幂等事件标识
     * @param payload 已被持久化接收的完整载荷
     */
    public void acknowledgeEvent(String hash, String dueKey, String id, String payload) {
        stringTemplate.execute(RedisAtomicScripts.ACK_EVENT, List.of(hash, dueKey), id, payload);
    }
    /**
     * 仅对仍存在的事件安排下次投递。
     *
     * @param hash 持久事件哈希键
     * @param dueKey 投递时间集合键
     * @param id 幂等事件标识
     * @param dueAt 下次投递的毫秒时间戳
     */
    public void retryEvent(String hash, String dueKey, String id, long dueAt) {
        stringTemplate.execute(
                RedisAtomicScripts.RETRY_EVENT, List.of(hash, dueKey), id, String.valueOf(dueAt));
    }

    private static void checkEventCapacity(Long result) {
        if (result == null || result == EVENT_CAPACITY_REJECTED) {
            throw new IllegalStateException("可靠审计队列不可用或已满");
        }
    }
    /**
     * 异步删除单个键，避免大键同步释放阻塞 Redis。
     *
     * @param key 目标缓存键
     * @return 键存在且被删除时 true
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(stringTemplate.unlink(key));
    }
    /**
     * 固定窗口原子计数，只在第一次计数设期限，不延长攻击窗口。
     *
     * @param key 计数窗口键
     * @param limit 窗口内允许的最大次数
     * @param ttl 固定窗口期限
     * @return 本次被窗口接受时 true
     * @throws IllegalArgumentException 阈值不是正数
     * @throws IllegalStateException Redis 未确认窗口操作
     */
    public boolean incrementWithinLimit(String key, int limit, Duration ttl) {
        requireTtl(ttl);
        if (limit < 1) {
            throw new IllegalArgumentException("限流阈值必须为正");
        }
        Long accepted =
                stringTemplate.execute(
                        RedisAtomicScripts.LIMITED_INCREMENT,
                        List.of(key),
                        String.valueOf(limit),
                        String.valueOf(ttl.toMillis()));
        if (accepted == null) {
            throw new IllegalStateException("认证窗口未确认");
        }
        return accepted == 1;
    }
    /**
     * 查询剩余期限，键不存在为 -2，无过期为 -1。
     *
     * @param key 目标缓存键
     * @return 剩余期限秒数或 Redis 状态码
     */
    public long ttlSeconds(String key) {
        Long ttl = stringTemplate.getExpire(key);
        return ttl == null ? MISSING_KEY_TTL : ttl;
    }
    /**
     * 使用安全对象模板写入值并附加非负过期抖动。
     *
     * @param key 缓存键
     * @param value 待序列化对象
     * @param ttl 正数有效期限
     */
    public void setObject(String key, Object value, Duration ttl) {
        requireTtl(ttl);
        objectTemplate.opsForValue().set(key, value, withJitter(ttl));
    }
    /**
     * 授权有截止时间时必须使用精确期限。
     *
     * @param key 授权缓存键
     * @param value 待序列化对象
     * @param ttl 正数精确期限
     */
    public void setObjectExact(String key, Object value, Duration ttl) {
        requireTtl(ttl);
        objectTemplate.opsForValue().set(key, value, ttl);
    }
    /**
     * 仅返回与预期类型相符的缓存值。
     *
     * @param <T> 预期值类型
     * @param key 缓存键
     * @param type 预期值类型
     * @return 类型匹配的值或空值
     */
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
    /**
     * 缓存 miss 时执行回源；空值占位防穿透，可选互斥锁下重复检查防击穿。
     *
     * @param <T> 业务值类型
     * @param key 缓存键
     * @param type 业务值类型
     * @param ttl 非空结果的正数缓存期限
     * @param loader 未命中时的业务加载器
     * @return 有效缓存或加载结果，空值占位返回 null
     */
    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        requireTtl(ttl);
        return RedisCacheLoader.load(
                objectTemplate,
                lockTemplate,
                new RedisCacheLoader.Request<>(key, type, ttl, loader));
    }
    /**
     * 删除字符串与对象模板的同名缓存，延迟双删由业务编排。
     *
     * @param key 缓存键
     */
    public void evict(String key) {
        stringTemplate.unlink(key);
        objectTemplate.unlink(key);
    }
    /**
     * 原子递增缓存通知版本并续期，不能替代数据库持久权威版本。
     *
     * @param versionKey 缓存通知版本键
     * @return Redis 确认的递增版本
     * @throws IllegalStateException Redis 未确认版本递增
     */
    public long bumpVersion(String versionKey) {
        Long next =
                stringTemplate.execute(
                        RedisAtomicScripts.INCREMENT_VERSION,
                        List.of(versionKey),
                        String.valueOf(RedisAtomicScripts.VERSION_TTL.toMillis()));
        if (next == null) {
            throw new IllegalStateException("Redis 未返回版本递增结果");
        }
        return next;
    }
    /**
     * 原子读取或初始化通知版本，不返回竞争落败的初始值。
     *
     * @param versionKey 缓存通知版本键
     * @return Redis 确认的当前版本
     * @throws IllegalStateException Redis 未确认版本读取
     */
    public long readVersion(String versionKey) {
        Long value =
                stringTemplate.execute(
                        RedisAtomicScripts.READ_VERSION,
                        List.of(versionKey),
                        String.valueOf(RedisAtomicScripts.VERSION_TTL.toMillis()));
        if (value == null) {
            throw new IllegalStateException("Redis 未返回版本读取结果");
        }
        return value;
    }
    /**
     * 幂等发布数据库版本，迟到事件不能写小；字符串比较避免 Lua 整数精度丢失。
     *
     * @param versionKey 缓存通知版本键
     * @param version 数据库权威版本
     * @param ttl 正数通知期限
     * @throws IllegalArgumentException 版本不是正数
     * @throws IllegalStateException Redis 未确认版本发布
     */
    public void advanceVersionTo(String versionKey, long version, Duration ttl) {
        requireTtl(ttl);
        if (version < 1) {
            throw new IllegalArgumentException("缓存版本必须为正数");
        }
        Long changed =
                stringTemplate.execute(
                        RedisAtomicScripts.ADVANCE_VERSION,
                        List.of(versionKey),
                        String.valueOf(version),
                        String.valueOf(Math.max(1, ttl.toMillis())));
        if (changed == null) {
            throw new IllegalStateException("Redis 未确认版本发布结果");
        }
    }

    private static void requireTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Redis 写入必须携带正 TTL；持久队列须单独登记并使用原子事件命令");
        }
    }
    /**
     * 增加非负随机期限抖动，缓解同批键同时过期。
     *
     * @param ttl 基础期限
     * @return 附加非负抖动后的期限
     */
    public static Duration withJitter(Duration ttl) {
        long jitter =
                ThreadLocalRandom.current()
                        .nextLong(Math.max(1, RedisKeys.TTL_JITTER_BOUND.getSeconds()));
        return ttl.plusSeconds(jitter);
    }
    /**
     * 原子状态迁移；诊断输出隐藏认证材料。
     *
     * @param key 状态存储键
     * @param expected 期望值，null 表示创建
     * @param replacement 替换值，null 表示删除
     * @param ttl 新状态的精确期限
     */
    public record StateTransition(String key, String expected, String replacement, Duration ttl) {
        @Override
        public String toString() {
            return "StateTransition[状态内容已隐藏]";
        }
    }

    /**
     * 已登记的无 TTL 有界事件队列。
     *
     * @param hashKey 持久事件哈希键
     * @param dueKey 投递时间有序集合键
     * @param capacity 未确认事件的最大数量
     */
    public record EventQueue(String hashKey, String dueKey, int capacity) {
        @Override
        public String toString() {
            return "EventQueue[存储键已隐藏, capacity=" + capacity + "]";
        }
    }

    /**
     * 事件身份、审计载荷与投递时间；诊断输出隐藏身份与载荷。
     *
     * @param id 幂等事件标识
     * @param payload 完整审计载荷
     * @param dueAtMillis 毫秒投递时间戳
     */
    public record EventPayload(String id, String payload, long dueAtMillis) {
        @Override
        public String toString() {
            return "EventPayload[身份与载荷已隐藏]";
        }
    }

    /**
     * 可靠事件提交命令，将队列策略与业务载荷分开表达。
     *
     * @param queue 目标有界持久队列
     * @param event 待确认事件
     */
    public record EventAppend(EventQueue queue, EventPayload event) {
        @Override
        public String toString() {
            return "EventAppend[" + queue + ", " + event + "]";
        }
    }
}
