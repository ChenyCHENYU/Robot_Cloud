package com.robot.system.permission.service;

import com.robot.common.redis.core.RedisHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用真实 JDBC 事务验证提交、回滚、竞争和失败恢复；Redis 仅作为可注入的外部通知替身。 */
class CommittedCacheChangesTest {

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private PermissionStateStore store;
    private RedisHelper redis;
    private CacheEventDispatcher dispatcher;
    private CommittedCacheChanges changes;

    @BeforeEach
    void database() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_cache_epoch (scope VARCHAR(255) PRIMARY KEY, version BIGINT NOT NULL)");
        jdbc.execute("INSERT INTO sys_cache_epoch VALUES ('permissions', 1)");
        jdbc.execute("CREATE TABLE sys_cache_outbox (id VARCHAR(64) PRIMARY KEY, event_kind VARCHAR(16), "
                + "cache_key VARCHAR(255), cache_version BIGINT, created_at TIMESTAMP, next_attempt_at TIMESTAMP, "
                + "completed_at TIMESTAMP, attempts INT, last_error VARCHAR(255), delivery_revision BIGINT DEFAULT 0 NOT NULL, last_failure_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE business_change (id INT PRIMARY KEY)");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        store = new PermissionStateStore(jdbc);
        redis = mock(RedisHelper.class);
        dispatcher = new CacheEventDispatcher(store, redis, manager);
        changes = new CommittedCacheChanges(store);
    }

    @Test
    void notificationWaitsForCommitAndConfirmationPersists() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO business_change VALUES (1)");
                changes.permissionsChanged();
                assertEquals(2, store.currentVersion("permissions"));
                verifyNoInteractions(redis);
                try {
                    assertEquals(1L, executor.submit(() -> store.currentVersion("permissions")).get());
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        } finally {
            executor.shutdownNow();
        }
        assertEquals(2, store.currentVersion("permissions"));
        verifyNoInteractions(redis);
        assertEquals(1, store.pending().size());
        dispatcher.retryPending();
        verify(redis).advanceVersionTo(anyString(), eq(2L), eq(Duration.ofDays(7)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_cache_outbox WHERE completed_at IS NOT NULL", Integer.class));
    }

    @Test
    void rollbackPublishesNothingAndKeepsOldVersion() {
        assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_change VALUES (1)");
            changes.permissionsChanged();
            throw new IllegalArgumentException("force rollback");
        }));
        assertEquals(1, store.currentVersion("permissions"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM business_change", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_cache_outbox", Integer.class));
        verifyNoInteractions(redis);
    }

    @Test
    void redisFailureLeavesDurableEventAndDoesNotLoseCommittedEpoch() {
        doThrow(new IllegalStateException("redis unavailable")).when(redis).advanceVersionTo(anyString(), anyLong(), any());
        transactions.executeWithoutResult(status -> changes.permissionsChanged());
        dispatcher.retryPending();
        assertEquals(2, store.currentVersion("permissions"));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM sys_cache_outbox", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_cache_outbox WHERE completed_at IS NULL", Integer.class));
        doNothing().when(redis).advanceVersionTo(anyString(), anyLong(), any());
        jdbc.update("UPDATE sys_cache_outbox SET next_attempt_at = TIMESTAMP '2020-01-01 00:00:00'");
        dispatcher.retryPending();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_cache_outbox WHERE completed_at IS NOT NULL", Integer.class));
        dispatcher.retryPending();
        verify(redis, times(2)).advanceVersionTo(anyString(), anyLong(), any());
    }

    @Test
    void dictionaryNegativeCacheUsesANewCommittedNamespace() {
        assertEquals(0, store.currentVersion(CommittedCacheChanges.dictionaryScope("new_code")));
        transactions.executeWithoutResult(status -> changes.dictionaryChanged("new_code"));
        assertEquals(1, store.currentVersion(CommittedCacheChanges.dictionaryScope("new_code")));
        dispatcher.retryPending();
        verify(redis).evict(com.robot.system.support.SystemKeys.dictCache("new_code", 0));
    }

    @Test
    void concurrentVersionsNeverLoseAnIncrement() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> futures = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(i -> executor.submit(() -> transactions.execute(
                            status -> store.incrementVersion("permissions")))).toList();
            HashSet<Long> versions = new HashSet<>();
            for (Future<Long> future : futures) {
                versions.add(future.get());
            }
            assertEquals(100, versions.size());
            assertEquals(101, store.currentVersion("permissions"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void firstCreationOfADictionaryEpochIsSafeUnderConcurrency() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> futures = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(i -> executor.submit(() -> transactions.execute(
                            status -> store.incrementVersion("dictionary:new")))).toList();
            HashSet<Long> versions = new HashSet<>();
            for (Future<Long> future : futures) {
                versions.add(future.get());
            }
            assertEquals(100, versions.size());
            assertEquals(100, store.currentVersion("dictionary:new"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mutationOutsideATransactionIsRejected() {
        assertThrows(IllegalStateException.class, changes::permissionsChanged);
        verifyNoInteractions(redis);
    }

    @Test
    void deliveryInsideABusinessTransactionIsRejectedBeforeTakingAnotherConnection() {
        transactions.executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, dispatcher::retryPending));
        verifyNoInteractions(redis);
    }
}
