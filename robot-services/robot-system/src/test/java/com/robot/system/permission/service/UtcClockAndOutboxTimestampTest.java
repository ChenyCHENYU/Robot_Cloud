package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robot.system.config.PermissionClockConfiguration;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 非UTC客户端默认下验证授权Clock与实际JDBC时间提取，不改写任何存量时间。 */
@ResourceLock(Resources.TIME_ZONE)
class UtcClockAndOutboxTimestampTest {
    private static final LocalDateTime DATABASE_WALL_TIME =
            LocalDateTime.parse("2020-06-01T00:00:00");

    @Test
    void configuredAuthorizationClockIgnoresNonUtcJvmDefaultAndAllowsFixedClockInjection() {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
        try {
            var clock = new PermissionClockConfiguration().permissionClock();
            assertEquals(ZoneOffset.UTC, clock.getZone());
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime actual = LocalDateTime.now(clock);
            LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);
            assertTrue(!actual.isBefore(before) && !actual.isAfter(after));
            Clock fixed = Clock.fixed(DATABASE_WALL_TIME.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
            try (var context = new AnnotationConfigApplicationContext()) {
                context.registerBean(Clock.class, () -> fixed);
                context.register(PermissionClockConfiguration.class);
                context.refresh();
                assertSame(fixed, context.getBean(Clock.class));
                assertEquals(DATABASE_WALL_TIME, LocalDateTime.now(context.getBean(Clock.class)));
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void actualJdbcOutboxAndReplayTimesRetainUtcWallValuesUnderNonUtcJvmDefault() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
        try (var connection =
                        DriverManager.getConnection(
                                "jdbc:h2:mem:utc_"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                                "sa",
                                "");
                var source = new SingleConnectionDataSource(connection, true)) {
            new ResourceDatabasePopulator(new ClassPathResource("cache-outbox-schema.sql"))
                    .execute(source);
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("SET TIME ZONE 'UTC'");
            var store = new PermissionStateStore(jdbc);
            insert(jdbc, "pending", null, null, null);
            insert(jdbc, "completed", DATABASE_WALL_TIME, null, null);
            insert(jdbc, "quarantined", null, DATABASE_WALL_TIME, null);
            insert(jdbc, "archived", DATABASE_WALL_TIME, null, DATABASE_WALL_TIME);
            for (String id : List.of("pending", "completed", "quarantined", "archived")) {
                var event = store.find(id);
                assertEquals(DATABASE_WALL_TIME, event.createdAt());
                assertEquals(DATABASE_WALL_TIME, event.nextAttemptAt());
            }
            assertNull(store.find("pending").completedAt());
            assertEquals(DATABASE_WALL_TIME, store.find("completed").completedAt());
            assertEquals(DATABASE_WALL_TIME, store.find("quarantined").quarantinedAt());
            assertEquals(DATABASE_WALL_TIME, store.find("archived").archivedAt());
            jdbc.update(
                    "INSERT INTO"
                            + " sys_cache_outbox_replay_log(id,event_id,actor_user_id,actor_company_id,"
                            + "reason,previous_revision,new_revision,created_at) VALUES"
                            + " ('audit','pending','actor','company','RECOVERY_CHECK',0,1,?)",
                    DATABASE_WALL_TIME);
            assertEquals(DATABASE_WALL_TIME, store.replayAudits("pending").get(0).createdAt());
            LocalDateTime before =
                    jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
            long age = store.snapshot().oldestAgeSeconds();
            LocalDateTime after =
                    jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
            assertTrue(age >= java.time.Duration.between(DATABASE_WALL_TIME, before).getSeconds());
            assertTrue(age <= java.time.Duration.between(DATABASE_WALL_TIME, after).getSeconds());
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    private static void insert(
            JdbcTemplate jdbc,
            String id,
            LocalDateTime completed,
            LocalDateTime quarantined,
            LocalDateTime archived) {
        jdbc.update(
                "INSERT INTO"
                        + " sys_cache_outbox(id,event_kind,cache_key,cache_version,created_at,next_attempt_at,"
                        + "completed_at,quarantined_at,archived_at) VALUES"
                        + " (?,'permissions','cache-key',1,?,?,?,?,?)",
                id,
                DATABASE_WALL_TIME,
                DATABASE_WALL_TIME,
                completed,
                quarantined,
                archived);
    }
}
