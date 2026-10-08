package com.robot.common.redis.lock;

import com.robot.common.redis.key.RedisKeys;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁：唯一允许的锁实现（wl-skills-bd B14：强制 Redisson RLock，
 * 禁止 setnx / setIfAbsent 自实现）。
 *
 * <p>用法（模板已内置 watchdog 自动续期 + 安全释放）：
 * <pre>{@code
 * String lockKey = RedisKeys.lock("system", "user", userId);
 * return LockTemplate.executeWithLock(redissonClient, lockKey, 10, () -> {
 *     // 临界区业务
 *     return result;
 * });
 * }</pre>
 */
public final class LockTemplate {

    /** 默认获取锁等待时间（秒）。 */
    private static final long DEFAULT_WAIT_SECONDS = 5;

    private final RedissonClient redissonClient;

    public LockTemplate(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 在分布式锁内执行业务（watchdog 自动续期，业务完成即释放）。
     *
     * @param lockKey  锁 Key（必须经 RedisKeys.lock 生成）
     * @param waitSeconds 获取锁最长等待秒数，超时抛 IllegalStateException
     * @param business 临界区逻辑
     */
    public <T> T execute(String lockKey, long waitSeconds, Supplier<T> business) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(waitSeconds, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("获取分布式锁超时: " + lockKey);
            }
            return business.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取分布式锁被中断: " + lockKey, e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public <T> T execute(String lockKey, Supplier<T> business) {
        return execute(lockKey, DEFAULT_WAIT_SECONDS, business);
    }

    /** 无返回值版本。 */
    public void execute(String lockKey, long waitSeconds, Runnable business) {
        execute(lockKey, waitSeconds, () -> {
            business.run();
            return null;
        });
    }
}
