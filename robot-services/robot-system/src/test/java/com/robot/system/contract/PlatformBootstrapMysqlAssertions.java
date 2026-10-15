package com.robot.system.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robot.common.core.exception.ServiceException;
import com.robot.system.config.PlatformAdministratorBootstrap;
import com.robot.system.permission.service.PermissionStateStore;
import java.util.Map;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 默认MySQL驱动TINYINT(1)布尔映射和真实Spring事务的首次交接断言。 */
final class PlatformBootstrapMysqlAssertions {
    private PlatformBootstrapMysqlAssertions() {}

    static void handoverAndIdempotence(AnnotationConfigApplicationContext context) {
        var jdbc = context.getBean(JdbcTemplate.class);
        var bootstrap = context.getBean(PlatformAdministratorBootstrap.class);
        assertEquals(Boolean.FALSE, flag(jdbc, "usr-admin"));
        long revision = accountVersion(jdbc, "revision");
        long security = accountVersion(jdbc, "security_version");
        long epoch = context.getBean(PermissionStateStore.class).currentVersion("permissions");
        bootstrap.run(new DefaultApplicationArguments());
        assertEquals(Boolean.TRUE, flag(jdbc, "usr-admin"));
        assertEquals(revision + 1, accountVersion(jdbc, "revision"));
        assertEquals(security + 1, accountVersion(jdbc, "security_version"));
        assertCommittedControlFacts(jdbc, epoch + 1, 1);
        bootstrap.run(new DefaultApplicationArguments());
        assertEquals(revision + 1, accountVersion(jdbc, "revision"));
        assertEquals(security + 1, accountVersion(jdbc, "security_version"));
        assertCommittedControlFacts(jdbc, epoch + 1, 1);
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "refuse-second-bootstrap",
                                Map.of("robot.bootstrap.platform-admin-user-id", "u-race")));
        assertThrows(
                ServiceException.class, () -> bootstrap.run(new DefaultApplicationArguments()));
        assertEquals(Boolean.FALSE, flag(jdbc, "u-race"));
        assertCommittedControlFacts(jdbc, epoch + 1, 1);
    }

    static void failedAuditRollback(AnnotationConfigApplicationContext context) {
        var jdbc = context.getBean(JdbcTemplate.class);
        long revision = accountVersion(jdbc, "revision");
        long security = accountVersion(jdbc, "security_version");
        long epoch = context.getBean(PermissionStateStore.class).currentVersion("permissions");
        jdbc.execute(
                "ALTER TABLE sys_permission_audit_log ADD CONSTRAINT reject_bootstrap "
                        + "CHECK (action <> 'bootstrap_platform_admin')");
        assertThrows(
                DataAccessException.class,
                () ->
                        context.getBean(PlatformAdministratorBootstrap.class)
                                .run(new DefaultApplicationArguments()));
        assertEquals(Boolean.FALSE, flag(jdbc, "usr-admin"));
        assertEquals(revision, accountVersion(jdbc, "revision"));
        assertEquals(security, accountVersion(jdbc, "security_version"));
        assertCommittedControlFacts(jdbc, epoch, 0);
    }

    private static Object flag(JdbcTemplate jdbc, String id) {
        return jdbc.queryForMap("SELECT platform_admin_flag FROM sys_user WHERE id = ?", id)
                .get("platform_admin_flag");
    }

    private static long accountVersion(JdbcTemplate jdbc, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM sys_user WHERE id='usr-admin'", Long.class);
    }

    private static void assertCommittedControlFacts(JdbcTemplate jdbc, long epoch, int changes) {
        assertEquals(
                epoch,
                jdbc.queryForObject(
                        "SELECT version FROM sys_cache_epoch WHERE scope='permissions'",
                        Long.class));
        assertEquals(
                changes,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_permission_audit_log WHERE"
                                + " action='bootstrap_platform_admin' AND target='usr-admin' AND"
                                + " actor_user_no='BOOTSTRAP' AND company_id='cmp-robot'",
                        Integer.class));
        assertEquals(
                changes,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_cache_outbox WHERE event_kind='permissions'",
                        Integer.class));
    }
}
