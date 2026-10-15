package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robot.system.api.dto.cache.CacheOutboxReplayReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboxDiagnosticSafetyTest {
    private static final int EVENT_REVISION = 3;
    private static final int NEXT_REVISION = EVENT_REVISION + 1;
    private static final int PENDING_EVENTS = 4;
    private static final int PENDING_RETRIES = 3;
    private static final int OLDEST_EVENT_SECONDS = 10;

    @Test
    void actualNestedEventAuditAndPlanDiagnosticsExcludeActorAndRedisTarget() throws Exception {
        String target = "private-company:user-secret:cache-target";
        String actor = "private-actor-identity";
        var now = LocalDateTime.parse("2026-10-14T00:00:00");
        var event =
                new PermissionStateStore.CacheEvent(
                        "event-1",
                        "USER",
                        target,
                        1,
                        2,
                        PENDING_RETRIES,
                        now,
                        now,
                        null,
                        "category",
                        "private-failure",
                        null,
                        null);
        var legacy =
                new PermissionStateStore.CacheEvent(
                        "event-2",
                        "USER",
                        target,
                        1,
                        2,
                        EVENT_REVISION,
                        now,
                        now,
                        null,
                        "category",
                        "private-failure");
        var audit =
                new PermissionStateStore.ReplayAudit(
                        "audit",
                        "event-1",
                        actor,
                        "private-company",
                        "private-reason",
                        EVENT_REVISION,
                        NEXT_REVISION,
                        now);
        var type =
                Class.forName(
                        "com.robot.system.permission.service.CacheOutboxGovernanceService$ReplayPlan");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object plan =
                constructor.newInstance(
                        actor,
                        "private-company",
                        CacheOutboxReplayReason.OPERATOR_RETRY,
                        "event-1",
                        "USER",
                        target,
                        1L,
                        (long) EVENT_REVISION,
                        2,
                        now,
                        now,
                        null,
                        "category",
                        "private-failure");
        String diagnostic = List.of(event, legacy, audit, plan).toString();
        assertTrue(diagnostic.contains("event-1"));
        assertTrue(diagnostic.contains("revision=3"));
        for (String forbidden :
                List.of(target, actor, "private-company", "private-failure", "private-reason")) {
            assertFalse(diagnostic.contains(forbidden), forbidden);
        }
    }

    @Test
    void scrapingBeforeAndAfterRefreshExposesStalenessWithoutDatabaseOrEventIdentity() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new CacheOutboxMetrics(registry);
            assertTrue(
                    Double.isNaN(
                            registry.get("robot.cache.outbox.snapshot.age.seconds")
                                    .gauge()
                                    .value()));
            assertTrue(
                    Double.isNaN(
                            registry.get("robot.cache.outbox.round.age.seconds").gauge().value()));
            metrics.update(
                    new PermissionStateStore.OutboxSnapshot(
                            PENDING_EVENTS, 2, 1, PENDING_RETRIES, 2, OLDEST_EVENT_SECONDS, 1));
            metrics.started();
            assertEquals(1, registry.get("robot.cache.outbox.inflight").gauge().value());
            metrics.duration(System.nanoTime());
            metrics.roundFinished(System.nanoTime());
            assertEquals(0, registry.get("robot.cache.outbox.inflight").gauge().value());
            assertEquals(
                    PENDING_EVENTS, registry.get("robot.cache.outbox.pending").gauge().value());
            assertEquals(1, registry.get("robot.cache.outbox.quarantined").gauge().value());
            assertTrue(
                    registry.get("robot.cache.outbox.snapshot.age.seconds").gauge().value() >= 0);
            assertTrue(registry.get("robot.cache.outbox.round.age.seconds").gauge().value() >= 0);
            metrics.count(CacheOutboxMetrics.Outcome.QUARANTINED);
            metrics.failure(CacheOutboxFailure.Category.INVALID_EVENT);
            assertEquals(
                    1,
                    registry.get("robot.cache.outbox.results")
                            .tag("outcome", "quarantined")
                            .counter()
                            .count());
            assertEquals(1, registry.get("robot.cache.outbox.delivery").timer().count());
        } finally {
            registry.close();
        }
    }
}
