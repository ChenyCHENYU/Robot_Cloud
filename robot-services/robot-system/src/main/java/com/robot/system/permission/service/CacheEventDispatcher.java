package com.robot.system.permission.service;

import com.robot.common.redis.core.RedisHelper;
import com.robot.common.redis.key.RedisKeys;
import com.robot.system.support.SystemKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 可重试的幂等缓存通知；数据库 epoch 是权威，旧回写受 revision 围栏约束。 */
@Slf4j
@Component
public class CacheEventDispatcher {
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;
    private static final int ROUND_BUDGET_SECONDS = 20;
    private static final int VERSION_CACHE_TTL_DAYS = 7;
    private static final long INITIAL_RETRY_SECONDS = 5L;
    private static final long MAX_RETRY_SECONDS = 300L;
    private static final int MAX_RETRY_EXPONENT = 6;
    private static final int JITTER_FRACTION = 5;
    private final PermissionStateStore store;
    private final RedisHelper redis;
    private final CacheOutboxMetrics metrics;
    private final TransactionTemplate transactions;

    /**
     * 独立事务测试入口；生产由 Spring 注入共享 MeterRegistry 对应的指标组件。
     *
     * @param store 数据库权威缓存账本
     * @param redis Redis访问入口
     * @param manager 独立投递事务管理器
     */
    public CacheEventDispatcher(
            PermissionStateStore store, RedisHelper redis, PlatformTransactionManager manager) {
        this(store, redis, manager, new CacheOutboxMetrics(new SimpleMeterRegistry()));
    }

    /**
     * 初始化当前用例的明确协作依赖或兼容值元组。
     *
     * @param store 数据库权威缓存账本
     * @param redis Redis访问入口
     * @param manager 独立投递事务管理器
     * @param metrics 固定低基数投递指标
     */
    @Autowired
    public CacheEventDispatcher(
            PermissionStateStore store,
            RedisHelper redis,
            PlatformTransactionManager manager,
            CacheOutboxMetrics metrics) {
        this.store = store;
        this.redis = redis;
        this.metrics = metrics;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    }

    /**
     * 每轮有界扫描；多实例可重复投递，数据库/Redis 失败不能回退已经提交的授权版本。
     *
     * @throws IllegalStateException 当前身份/状态不满足用例约束或受控操作失败
     */
    public void retryPending() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("缓存投递必须由独立调度运行，不能进入业务事务");
        }
        long startedAt = System.nanoTime();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(ROUND_BUDGET_SECONDS).toNanos();
            for (PermissionStateStore.CacheEvent event : store.pending()) {
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                    break;
                }
                dispatch(event.id());
            }
        } catch (RuntimeException error) {
            databaseFailure();
        } finally {
            refreshSnapshot();
            metrics.roundFinished(startedAt);
        }
    }

    /** Redis 调用不持有数据库事务，确认与失败记录各自提交。 */
    private void dispatch(String eventId) {
        try {
            List<PermissionStateStore.CacheEvent> events =
                    transactions.execute(status -> store.findPending(eventId));
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
                String failureId = CacheOutboxFailure.newId();
                boolean changed =
                        Boolean.TRUE.equals(
                                transactions.execute(status -> store.quarantine(event, failureId)));
                metrics.count(
                        changed
                                ? CacheOutboxMetrics.Outcome.QUARANTINED
                                : CacheOutboxMetrics.Outcome.SUPERSEDED);
                if (changed) {
                    metrics.failure(CacheOutboxFailure.Category.INVALID_EVENT);
                }
                return;
            }
            try {
                send(event);
            } catch (RuntimeException error) {
                recordFailure(event, CacheOutboxFailure.redisCategory(error));
                return;
            }
            boolean completed =
                    Boolean.TRUE.equals(transactions.execute(status -> store.complete(event)));
            metrics.count(
                    completed
                            ? CacheOutboxMetrics.Outcome.CONFIRMED
                            : CacheOutboxMetrics.Outcome.SUPERSEDED);
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
            return event.cacheKey() != null
                    && event.cacheKey()
                            .matches(
                                    Pattern.quote(prefix)
                                            + "[a-f0-9]{64}:db-v"
                                            + (event.version() - 1));
        }
        return false;
    }

    private void send(PermissionStateStore.CacheEvent event) {
        if (PermissionStateStore.PERMISSIONS.equals(event.kind())) {
            redis.advanceVersionTo(
                    event.cacheKey(), event.version(), Duration.ofDays(VERSION_CACHE_TTL_DAYS));
        } else {
            redis.evict(event.cacheKey());
        }
    }

    private void recordFailure(
            PermissionStateStore.CacheEvent event, CacheOutboxFailure.Category category) {
        String failureId = CacheOutboxFailure.newId();
        boolean updated =
                Boolean.TRUE.equals(
                        transactions.execute(
                                status ->
                                        store.retryAfter(
                                                event,
                                                category,
                                                failureId,
                                                retryDelay(event.attempts()))));
        metrics.count(
                updated
                        ? CacheOutboxMetrics.Outcome.RETRIED
                        : CacheOutboxMetrics.Outcome.SUPERSEDED);
        if (updated) {
            metrics.failure(category);
            log.warn(
                    "缓存通知等待重试 eventId={} category={} failureId={}",
                    event.id(),
                    category,
                    failureId);
        }
    }

    private static long retryDelay(int attempts) {
        long base =
                Math.min(
                        MAX_RETRY_SECONDS,
                        INITIAL_RETRY_SECONDS
                                << Math.min(Math.max(attempts, 0), MAX_RETRY_EXPONENT));
        long jitter = Math.max(1, base / JITTER_FRACTION);
        return Math.min(
                MAX_RETRY_SECONDS, ThreadLocalRandom.current().nextLong(base, base + jitter + 1));
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
        log.error(
                "缓存通知数据库处理失败 category={} failureId={}",
                CacheOutboxFailure.Category.DATABASE_FAILURE,
                failureId);
    }
}
