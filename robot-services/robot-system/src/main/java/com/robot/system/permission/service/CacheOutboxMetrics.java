package com.robot.system.permission.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** 固定低基数指标；采集线程只读快照，不在 Prometheus scrape 中连接数据库或暴露事件身份。 */
@Component
public class CacheOutboxMetrics {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /** 固定低基数投递结果类型。 */
    public enum Outcome {
        CONFIRMED,
        RETRIED,
        SUPERSEDED,
        DATABASE_FAILURE,
        REPLAYED,
        QUARANTINED
    }

    private final AtomicReference<PermissionStateStore.OutboxSnapshot> snapshot =
            new AtomicReference<>(PermissionStateStore.OutboxSnapshot.EMPTY);
    private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);
    private final Map<CacheOutboxFailure.Category, Counter> failures =
            new EnumMap<>(CacheOutboxFailure.Category.class);
    private final Timer delivery;
    private final Timer rounds;
    private final AtomicInteger inflight = new AtomicInteger();
    private volatile long refreshedAtNanos;
    private volatile long roundFinishedAtNanos;

    /**
     * 初始化当前用例的明确协作依赖或兼容值元组。
     *
     * @param registry 指标注册表
     */
    public CacheOutboxMetrics(MeterRegistry registry) {
        Gauge.builder("robot.cache.outbox.inflight", inflight, AtomicInteger::get)
                .register(registry);
        Gauge.builder("robot.cache.outbox.pending", snapshot, value -> value.get().pending())
                .register(registry);
        Gauge.builder("robot.cache.outbox.due", snapshot, value -> value.get().due())
                .register(registry);
        Gauge.builder("robot.cache.outbox.failed.pending", snapshot, value -> value.get().failed())
                .register(registry);
        Gauge.builder(
                        "robot.cache.outbox.retries.pending",
                        snapshot,
                        value -> value.get().retries())
                .register(registry);
        Gauge.builder(
                        "robot.cache.outbox.max.attempts",
                        snapshot,
                        value -> value.get().maxAttempts())
                .register(registry);
        Gauge.builder(
                        "robot.cache.outbox.oldest.age.seconds",
                        snapshot,
                        value -> value.get().oldestAgeSeconds())
                .register(registry);
        Gauge.builder(
                        "robot.cache.outbox.snapshot.age.seconds",
                        this,
                        CacheOutboxMetrics::snapshotAge)
                .register(registry);
        Gauge.builder(
                        "robot.cache.outbox.quarantined",
                        snapshot,
                        value -> value.get().quarantined())
                .register(registry);
        Gauge.builder(
                        "robot.cache.outbox.round.age.seconds",
                        this,
                        value ->
                                value.roundFinishedAtNanos == 0
                                        ? Double.NaN
                                        : Math.max(
                                                        0,
                                                        System.nanoTime()
                                                                - value.roundFinishedAtNanos)
                                                / NANOS_PER_SECOND)
                .register(registry);
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(
                    outcome,
                    Counter.builder("robot.cache.outbox.results")
                            .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                            .register(registry));
        }
        for (CacheOutboxFailure.Category category : CacheOutboxFailure.Category.values()) {
            failures.put(
                    category,
                    Counter.builder("robot.cache.outbox.failures")
                            .tag("category", category.name().toLowerCase(java.util.Locale.ROOT))
                            .register(registry));
        }
        delivery = Timer.builder("robot.cache.outbox.delivery").register(registry);
        rounds = Timer.builder("robot.cache.outbox.round").register(registry);
    }

    /**
     * 成功刷新才替换快照，数据库异常时保留最近事实并由 snapshot age 显示过期。
     *
     * @param value 待校验或快照复制的输入值
     */
    public void update(PermissionStateStore.OutboxSnapshot value) {
        snapshot.set(value);
        refreshedAtNanos = System.nanoTime();
    }

    /**
     * 累加固定投递结果计数，事件身份不作为指标标签。
     *
     * @param outcome 受控投递结果
     */
    public void count(Outcome outcome) {
        outcomes.get(outcome).increment();
    }

    void failure(CacheOutboxFailure.Category category) {
        failures.get(category).increment();
    }

    void started() {
        inflight.incrementAndGet();
    }

    void duration(long startedAtNanos) {
        inflight.decrementAndGet();
        delivery.record(Math.max(0, System.nanoTime() - startedAtNanos), TimeUnit.NANOSECONDS);
    }

    void roundFinished(long startedAtNanos) {
        roundFinishedAtNanos = System.nanoTime();
        rounds.record(Math.max(0, roundFinishedAtNanos - startedAtNanos), TimeUnit.NANOSECONDS);
    }

    private double snapshotAge() {
        return refreshedAtNanos == 0
                ? Double.NaN
                : Math.max(0, System.nanoTime() - refreshedAtNanos) / NANOS_PER_SECOND;
    }
}
