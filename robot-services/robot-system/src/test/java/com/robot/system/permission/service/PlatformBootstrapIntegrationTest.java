package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robot.system.config.PlatformAdministratorBootstrap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 真实事务证明独立平台交接与审计/安全版本/epoch/outbox不可部分提交。 */
class PlatformBootstrapIntegrationTest {
    private static final int TEST_BCRYPT_COST = 4;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitFirstHandoverIsIdempotentAndDoesNotElevateAnotherCompanyAccount(
            boolean booleanColumn) {
        try (var context = context(booleanColumn)) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var bootstrap = context.getBean(PlatformAdministratorBootstrap.class);
            Object initial =
                    jdbc.queryForMap("SELECT platform_admin_flag FROM sys_user WHERE id='u1'")
                            .get("platform_admin_flag");
            if (booleanColumn) {
                assertEquals(Boolean.FALSE, initial);
            } else {
                assertInstanceOf(Number.class, initial);
            }
            bootstrap.run(new DefaultApplicationArguments());
            if (booleanColumn) {
                assertEquals(
                        Boolean.TRUE,
                        jdbc.queryForMap("SELECT platform_admin_flag FROM sys_user WHERE id='u1'")
                                .get("platform_admin_flag"));
            }
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT platform_admin_flag FROM sys_user WHERE id='u1'",
                            Integer.class));
            assertEquals(
                    1L,
                    jdbc.queryForObject(
                            "SELECT security_version FROM sys_user WHERE id='u1'", Long.class));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sys_permission_audit_log WHERE"
                                    + " action='bootstrap_platform_admin'",
                            Integer.class));
            assertEquals(
                    1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_cache_outbox", Integer.class));
            bootstrap.run(new DefaultApplicationArguments());
            assertEquals(
                    1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_cache_outbox", Integer.class));
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "next-user",
                                    Map.of("robot.bootstrap.platform-admin-user-id", "u2")));
            assertThrows(
                    com.robot.common.core.exception.ServiceException.class,
                    () -> bootstrap.run(new DefaultApplicationArguments()));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT platform_admin_flag FROM sys_user WHERE id='u2'",
                            Integer.class));
        }
    }

    @Test
    void failedSuccessAuditRollsBackIndependentPlatformFactAndEveryVersion() {
        try (var context = context()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute(
                    "ALTER TABLE sys_permission_audit_log ADD CONSTRAINT reject_bootstrap CHECK"
                            + " (action <> 'bootstrap_platform_admin')");
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            context.getBean(PlatformAdministratorBootstrap.class)
                                    .run(new DefaultApplicationArguments()));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT platform_admin_flag FROM sys_user WHERE id='u1'",
                            Integer.class));
            assertEquals(
                    0L,
                    jdbc.queryForObject(
                            "SELECT security_version FROM sys_user WHERE id='u1'", Long.class));
            assertEquals(
                    1L,
                    jdbc.queryForObject(
                            "SELECT version FROM sys_cache_epoch WHERE scope='permissions'",
                            Long.class));
            assertEquals(
                    0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_cache_outbox", Integer.class));
        }
    }

    private static AnnotationConfigApplicationContext context() {
        return context(false);
    }

    private static AnnotationConfigApplicationContext context(boolean booleanColumn) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "explicit-handover",
                                Map.of(
                                        "robot.bootstrap.platform-admin-enabled",
                                        "true",
                                        "robot.bootstrap.platform-admin-user-id",
                                        "u1",
                                        "test.platform.boolean-column",
                                        booleanColumn)));
        context.register(Config.class);
        context.refresh();
        return context;
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(PlatformAdministratorBootstrap.class)
    static class Config {
        @Bean
        DataSource source() {
            var source =
                    new DriverManagerDataSource(
                            "jdbc:h2:mem:"
                                    + UUID.randomUUID()
                                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                            "sa",
                            "");
            new ResourceDatabasePopulator(new ClassPathResource("write-command-schema.sql"))
                    .execute(source);
            return source;
        }

        @Bean
        JdbcTemplate jdbc(DataSource source, org.springframework.core.env.Environment environment) {
            var jdbc = new JdbcTemplate(source);
            if (environment.getProperty("test.platform.boolean-column", Boolean.class, false)) {
                jdbc.execute("ALTER TABLE sys_user ALTER COLUMN platform_admin_flag BOOLEAN");
            }
            jdbc.execute(
                    "CREATE TABLE sys_tenant(id VARCHAR(64) PRIMARY KEY, delete_flag INT,"
                            + " status_flag INT)");
            jdbc.execute(
                    "CREATE TABLE sys_company(id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64),"
                            + " delete_flag INT, status_flag INT)");
            jdbc.update("INSERT INTO sys_tenant VALUES('t1',1,1)");
            jdbc.update("INSERT INTO sys_company VALUES('c1','t1',1,1)");
            return jdbc;
        }

        @Bean
        DataSourceTransactionManager manager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        PermissionStateStore state(JdbcTemplate jdbc) {
            return new PermissionStateStore(jdbc);
        }

        @Bean
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwords() {
            return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(
                    TEST_BCRYPT_COST);
        }
    }
}
