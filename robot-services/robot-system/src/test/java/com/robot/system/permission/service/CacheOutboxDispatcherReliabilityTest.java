package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robot.common.redis.core.RedisHelper;
import com.robot.system.support.SystemKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 JDBC 竞争/快照；外部 Redis IO 是可控替身，不宣称验证实际 Redis 可用性。 */
class CacheOutboxDispatcherReliabilityTest {
    private static final int BARRIER_TIMEOUT_SECONDS = 5;
    private static final int OLDEST_EVENT_SECONDS = 120;
    private static final int TIMING_TOLERANCE_SECONDS = 1;
    private static final int RETRY_DELAY_SECONDS = 60;
    private AnnotationConfigApplicationContext context;
    private PermissionStateStore store;
    private JdbcTemplate jdbc;
    private RedisHelper redis;
    private TransactionTemplate transactions;
    private CacheEventDispatcher first;
    private CacheEventDispatcher second;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(CacheOutboxTestConfiguration.class);
        store = context.getBean(PermissionStateStore.class);
        jdbc = context.getBean(JdbcTemplate.class);
        redis = context.getBean(RedisHelper.class);
        var manager = context.getBean(PlatformTransactionManager.class);
        transactions = new TransactionTemplate(manager);
        meters = context.getBean(SimpleMeterRegistry.class);
        first = context.getBean(CacheEventDispatcher.class);
        second =
                new CacheEventDispatcher(
                        store, redis, manager, new CacheOutboxMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void cleanup() {
        context.close();
    }

    @Test
    void twoDispatchersMaySendTwiceButOnlyOneConfirmationIsAccepted() throws Exception {
        String id = enqueue();
        CountDownLatch sending = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            assertFalse(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            sending.countDown();
                            assertTrue(release.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                            return null;
                        })
                .when(redis)
                .advanceVersionTo(anyString(), anyLong(), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(first::retryPending);
            var two = executor.submit(second::retryPending);
            assertTrue(sending.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, meters.get("robot.cache.outbox.inflight").gauge().value());
            release.countDown();
            one.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            two.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, store.find(id).revision());
        assertNotNull(store.find(id).completedAt());
        assertEquals(0, store.find(id).attempts());
        assertEquals(1, store.currentVersion(PermissionStateStore.PERMISSIONS));
        assertEquals(0, meters.get("robot.cache.outbox.inflight").gauge().value());
        verify(redis, times(2)).advanceVersionTo(anyString(), eq(1L), any());
    }

    @Test
    void staleFailureAfterRequeueCannotDelayOrIncrementTheNewRevision() throws Exception {
        String id = enqueue();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            sending.countDown();
                            assertTrue(release.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                            throw new RedisConnectionFailureException(
                                    "Authorization: Bearer simulated-sensitive-value");
                        })
                .when(redis)
                .advanceVersionTo(anyString(), anyLong(), any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(first::retryPending);
            assertTrue(sending.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            transactions.executeWithoutResult(
                    status -> assertTrue(store.reschedule(store.find(id))));
            release.countDown();
            future.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, store.find(id).revision());
        assertEquals(0, store.find(id).attempts());
        assertNull(store.find(id).errorCategory());
        assertEquals(
                1,
                meters.get("robot.cache.outbox.results")
                        .tag("outcome", "superseded")
                        .counter()
                        .count());
    }

    @Test
    void redisFailurePersistsSafeDiagnosisAndRecoversWithoutLosingEpoch() {
        String id = enqueue();
        String sensitive = "Authorization: Bearer simulated-sensitive-value";
        doThrow(new RedisConnectionFailureException(sensitive))
                .when(redis)
                .advanceVersionTo(anyString(), anyLong(), any());
        Logger logger = (Logger) LoggerFactory.getLogger(CacheEventDispatcher.class);
        ListAppender<ILoggingEvent> output = new ListAppender<>();
        output.start();
        logger.addAppender(output);
        try {
            first.retryPending();
            var failed = store.find(id);
            assertEquals("REDIS_UNAVAILABLE", failed.errorCategory());
            assertTrue(failed.failureId().matches("[a-f0-9-]{36}"));
            assertEquals(1, failed.attempts());
            assertNull(failed.completedAt());
            assertTrue(
                    output.list.stream()
                            .noneMatch(event -> event.getFormattedMessage().contains(sensitive)));
            assertTrue(output.list.stream().allMatch(event -> event.getThrowableProxy() == null));
            assertEquals(1, store.currentVersion(PermissionStateStore.PERMISSIONS));
            doNothing().when(redis).advanceVersionTo(anyString(), anyLong(), any());
            jdbc.update(
                    "UPDATE sys_cache_outbox SET next_attempt_at=? WHERE id=?",
                    LocalDateTime.now().minusSeconds(1),
                    id);
            first.retryPending();
            assertNotNull(store.find(id).completedAt());
            assertEquals(2, store.find(id).revision());
            assertEquals(1, store.find(id).attempts());
        } finally {
            logger.detachAppender(output);
            output.stop();
        }
    }

    @Test
    void metricsAreOneSnapshotWithoutEventOrTenantLabelsAndKeepFailureFacts() {
        String one = enqueue();
        String two = enqueue();
        transactions.executeWithoutResult(
                status ->
                        store.retry(
                                store.find(one),
                                CacheOutboxFailure.Category.REDIS_FAILURE,
                                "diagnostic-id",
                                LocalDateTime.now().plusHours(1)));
        jdbc.update(
                "UPDATE sys_cache_outbox SET created_at=?",
                LocalDateTime.now().minusSeconds(OLDEST_EVENT_SECONDS));
        doNothing().when(redis).advanceVersionTo(anyString(), anyLong(), any());
        first.retryPending();
        assertNotNull(store.find(two).completedAt());
        assertEquals(1, meters.get("robot.cache.outbox.pending").gauge().value());
        assertEquals(0, meters.get("robot.cache.outbox.due").gauge().value());
        assertEquals(1, meters.get("robot.cache.outbox.failed.pending").gauge().value());
        assertEquals(1, meters.get("robot.cache.outbox.retries.pending").gauge().value());
        assertTrue(
                meters.get("robot.cache.outbox.oldest.age.seconds").gauge().value()
                        >= OLDEST_EVENT_SECONDS - TIMING_TOLERANCE_SECONDS);
        assertTrue(
                meters.getMeters().stream()
                        .flatMap(m -> m.getId().getTags().stream())
                        .allMatch(
                                tag ->
                                        tag.getKey().equals("outcome")
                                                || tag.getKey().equals("category")));
    }

    private String enqueue() {
        return transactions.execute(
                status ->
                        store.enqueue(
                                PermissionStateStore.PERMISSIONS,
                                SystemKeys.permissionVersion(),
                                1));
    }

    @Test
    void poisonNotificationIsQuarantinedWithoutBlockingHealthyRecovery() {
        String poison =
                transactions.execute(status -> store.enqueue("invalid", "never-touch-this-key", 1));
        String healthy = enqueue();
        first.retryPending();
        assertNotNull(store.find(poison).quarantinedAt());
        assertNull(store.find(poison).completedAt());
        assertEquals("INVALID_EVENT", store.find(poison).errorCategory());
        assertEquals(1, store.find(poison).attempts());
        assertNotNull(store.find(healthy).completedAt());
        first.retryPending();
        assertEquals(
                1,
                store.find(poison).attempts(),
                "permanent invalid event is not retried indefinitely");
        assertEquals(1, store.snapshot().quarantined());
        assertEquals(0, store.snapshot().due());
        verify(redis, never()).evict("never-touch-this-key");
    }

    @Test
    void retryDeadlineUsesDatabaseRelativeTimeAndRevisionFence() {
        String id = enqueue();
        var event = store.find(id);
        transactions.executeWithoutResult(
                status ->
                        assertTrue(
                                store.retryAfter(
                                        event,
                                        CacheOutboxFailure.Category.REDIS_FAILURE,
                                        "synthetic-id",
                                        RETRY_DELAY_SECONDS)));
        long delay =
                jdbc.queryForObject(
                        "SELECT DATEDIFF('SECOND', CURRENT_TIMESTAMP, next_attempt_at) FROM"
                                + " sys_cache_outbox WHERE id=?",
                        Long.class,
                        id);
        assertTrue(
                delay >= RETRY_DELAY_SECONDS - TIMING_TOLERANCE_SECONDS
                        && delay <= RETRY_DELAY_SECONDS);
        transactions.executeWithoutResult(
                status ->
                        assertFalse(
                                store.retryAfter(
                                        event,
                                        CacheOutboxFailure.Category.REDIS_FAILURE,
                                        "late-id",
                                        0)));
        assertEquals(1, store.find(id).attempts());
    }
}
