package com.robot.system.permission.service;

import com.robot.common.redis.core.RedisHelper;
import com.robot.common.redis.key.RedisKeys;
import com.robot.system.support.SystemKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** 可重试的幂等缓存通知；数据库 epoch 是权威，旧回写受 revision 围栏约束。 */
@Slf4j
@Component
public class CacheEventDispatcher {
    private final PermissionStateStore store;
    private final RedisHelper redis;
    private final CacheOutboxMetrics metrics;
    private final TransactionTemplate transactions;

    /** 独立事务测试入口；生产由 Spring 注入共享 MeterRegistry 对应的指标组件。 */
    public CacheEventDispatcher(PermissionStateStore store, RedisHelper redis, PlatformTransactionManager manager) {
        this(store, redis, manager, new CacheOutboxMetrics(new SimpleMeterRegistry()));
    }

    @Autowired
    public CacheEventDispatcher(PermissionStateStore store, RedisHelper redis,
                                PlatformTransactionManager manager, CacheOutboxMetrics metrics) {
        this.store = store;
        this.redis = redis;
        this.metrics = metrics;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 每轮有界扫描；多实例可重复投递，数据库/Redis 失败不能回退已经提交的授权版本。 */
    public void retryPending() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("缓存投递必须由独立调度运行，不能进入业务事务");
        }
        try {
            for (PermissionStateStore.CacheEvent event : store.pending()) {
                dispatch(event.id());
            }
        } catch (RuntimeException error) {
            databaseFailure();
        } finally {
            refreshSnapshot();
        }
    }

    /** Redis 调用不持有数据库事务，确认与失败记录各自提交。 */
    private void dispatch(String eventId) {
        try {
            List<PermissionStateStore.CacheEvent> events = transactions.execute(status -> store.findPending(eventId));
            if (events != null && !events.isEmpty()) {
                deliver(events.get(0));
            }
        } catch (RuntimeException error) {
            databaseFailure();
        }
    }

    private void deliver(PermissionStateStore.CacheEvent event) {
        long startedAtNanos = System.nanoTime();
        metrics.started();
        try {
            if (!validEvent(event)) {
                recordFailure(event, CacheOutboxFailure.Category.INVALID_EVENT);
                return;
            }
            try {
                send(event);
            } catch (RuntimeException error) {
                recordFailure(event, CacheOutboxFailure.redisCategory(error));
                return;
            }
            boolean completed = Boolean.TRUE.equals(transactions.execute(status -> store.complete(event)));
            metrics.count(completed ? CacheOutboxMetrics.Outcome.CONFIRMED : CacheOutboxMetrics.Outcome.SUPERSEDED);
        } finally {
            metrics.duration(startedAtNanos);
        }
    }

    /** 治理重放与自动投递共用合法性检查，不能借缓存事件删除其他域的 Redis key。 */
    boolean validEvent(PermissionStateStore.CacheEvent event) {
        if (event.version() < 1) {
            return false;
        }
        if (PermissionStateStore.PERMISSIONS.equals(event.kind())) {
            return SystemKeys.permissionVersion().equals(event.cacheKey())
                    && event.version() <= store.currentVersion(PermissionStateStore.PERMISSIONS);
        }
        if ("dictionary".equals(event.kind())) {
            String prefix = RedisKeys.base("system") + ":dict:";
            return event.cacheKey() != null && event.cacheKey().matches(
                    Pattern.quote(prefix) + "[a-f0-9]{64}:db-v" + (event.version() - 1));
        }
        return false;
    }

    private void send(PermissionStateStore.CacheEvent event) {
        if (PermissionStateStore.PERMISSIONS.equals(event.kind())) {
            redis.advanceVersionTo(event.cacheKey(), event.version(), Duration.ofDays(7));
        } else {
            redis.evict(event.cacheKey());
        }
    }

    private void recordFailure(PermissionStateStore.CacheEvent event, CacheOutboxFailure.Category category) {
        String failureId = CacheOutboxFailure.newId();
        boolean updated = Boolean.TRUE.equals(transactions.execute(status -> store.retry(event, category, failureId,
                LocalDateTime.now().plusSeconds(retryDelay(event.attempts())))));
        metrics.count(updated ? CacheOutboxMetrics.Outcome.RETRIED : CacheOutboxMetrics.Outcome.SUPERSEDED);
        if (updated) {
            metrics.failure(category);
            log.warn("缓存通知等待重试 eventId={} category={} failureId={}", event.id(), category, failureId);
        }
    }

    private static long retryDelay(int attempts) {
        long base = Math.min(300, 5L << Math.min(Math.max(attempts, 0), 6));
        long jitter = Math.max(1, base / 5);
        return Math.min(300, ThreadLocalRandom.current().nextLong(base, base + jitter + 1));
    }

    private void refreshSnapshot() {
        try {
            metrics.update(store.snapshot());
        } catch (RuntimeException error) {
            databaseFailure();
        }
    }

    private void databaseFailure() {
        String failureId = CacheOutboxFailure.newId();
        metrics.failure(CacheOutboxFailure.Category.DATABASE_FAILURE);
        metrics.count(CacheOutboxMetrics.Outcome.DATABASE_FAILURE);
        log.error("缓存通知数据库处理失败 category={} failureId={}",
                CacheOutboxFailure.Category.DATABASE_FAILURE, failureId);
    }
}
