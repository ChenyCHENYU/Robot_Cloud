package com.robot.system.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.robot.common.mybatis.config.RobotMybatisAutoConfiguration;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.role.TempAuthorizationCreateRequest;
import com.robot.system.api.dto.user.UserSaveRequest;
import com.robot.system.api.entity.SysRole;
import com.robot.system.config.PlatformAdministratorBootstrap;
import com.robot.system.permission.service.CommittedCacheChanges;
import com.robot.system.permission.service.DataScopeService;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.GlobalDirectoryPolicy;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.role.service.RoleCommandService;
import com.robot.system.role.service.TemporaryAuthorizationCommandService;
import com.robot.system.user.service.MemberRoleCommandSupport;
import com.robot.system.user.service.UserQueryService;
import com.robot.system.user.service.UserService;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 真MySQL/真Service代理/Mapper/审计/epoch/Outbox，用屏障证明锁在检查之前。 仅替换操作者授权供给；认证上界由正式安全测试独立验证。 */
class AggregateReferenceConcurrencyIT {
    private static final int BARRIER_TIMEOUT_SECONDS = 8;
    private static final int BLOCKED_CONTENDER_MILLISECONDS = 200;
    private static final int MAX_TCP_PORT = 65535;
    private static final int FIXTURE_BCRYPT_COST = 4;
    private static final long V13_SEEDED_PERMISSIONS_EPOCH = 2L;

    @Test
    @Timeout(40)
    void mysqlBootstrapHandlesBooleanMappingAndKeepsFirstHandoverIdempotent() throws Exception {
        isolated(PlatformBootstrapMysqlAssertions::handoverAndIdempotence);
    }

    @Test
    @Timeout(40)
    void mysqlBootstrapFailedSuccessAuditRollsBackPlatformFactAndEpoch() throws Exception {
        isolated(PlatformBootstrapMysqlAssertions::failedAuditRollback);
    }

    @Test
    @Timeout(40)
    void roleDeletionWinsBeforeMemberBindingAndLeavesNoDanglingReference() throws Exception {
        isolated(
                context -> {
                    var roles = context.getBean(RoleCommandService.class);
                    var users = context.getBean(UserService.class);
                    UserSaveRequest request = new UserSaveRequest();
                    request.setUsername("race_member");
                    request.setMembershipRevision(0);
                    request.setRoleIds(List.of("r-race"));
                    compete(
                            context,
                            () -> roles.delete("r-race", 0),
                            () -> users.update("u-race", request));
                    assertCounts(context, "sys_user_role", "delete_role");
                });
    }

    @Test
    @Timeout(40)
    void roleDeletionWinsBeforeTemporaryGrantAndLeavesNoDanglingReference() throws Exception {
        isolated(
                context -> {
                    var roles = context.getBean(RoleCommandService.class);
                    var temporary = context.getBean(TemporaryAuthorizationCommandService.class);
                    compete(
                            context,
                            () -> roles.delete("r-race", 0),
                            () -> temporary.createTempAuthorization(request()));
                    assertCounts(context, "sys_temp_authorization", "delete_role");
                });
    }

    @Test
    @Timeout(40)
    void memberRemovalWinsBeforeTemporaryGrantAndLeavesNoDanglingReference() throws Exception {
        isolated(
                context -> {
                    var users = context.getBean(UserService.class);
                    var temporary = context.getBean(TemporaryAuthorizationCommandService.class);
                    compete(
                            context,
                            () -> users.delete("u-race", 0),
                            () -> temporary.createTempAuthorization(request()));
                    assertCounts(context, "sys_temp_authorization", "remove_member");
                });
    }

    @Test
    @Timeout(40)
    void grantThenRemovalIsALegalSequenceWithBothSuccessAuditsPreserved() throws Exception {
        isolated(
                context -> {
                    actor(
                            () ->
                                    context.getBean(TemporaryAuthorizationCommandService.class)
                                            .createTempAuthorization(request()));
                    actor(() -> context.getBean(UserService.class).delete("u-race", 0));
                    var jdbc = context.getBean(JdbcTemplate.class);
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_temp_authorization WHERE"
                                            + " user_id='u-race' AND status_flag=1",
                                    Integer.class));
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_permission_audit_log WHERE action IN"
                                            + " ('temp_authorize','remove_member')",
                                    Integer.class));
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_cache_outbox", Integer.class));
                });
    }

    private static TempAuthorizationCreateRequest request() {
        var request = new TempAuthorizationCreateRequest();
        request.setUserId("u-race");
        request.setRoleId("r-race");
        request.setEffectFrom(LocalDateTime.now().minusMinutes(1));
        request.setEffectTo(LocalDateTime.now().plusHours(1));
        request.setReason("isolated-concurrency-verification");
        return request;
    }

    private static void compete(
            AnnotationConfigApplicationContext context, Runnable winner, Runnable contender)
            throws Exception {
        PermissionStateStore store =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                        context.getBean(PermissionStateStore.class));
        CountDownLatch locked = new CountDownLatch(1),
                attempted = new CountDownLatch(1),
                release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            if (Thread.currentThread().getName().equals("aggregate-winner")) {
                                invocation.callRealMethod();
                                locked.countDown();
                                assertTrue(
                                        release.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                            } else {
                                attempted.countDown();
                                invocation.callRealMethod();
                            }
                            return null;
                        })
                .when(store)
                .lockCompanyWrites("cmp-robot");
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first =
                    pool.submit(
                            () -> {
                                Thread.currentThread().setName("aggregate-winner");
                                actor(winner);
                            });
            assertTrue(locked.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Future<?> second = pool.submit(() -> actor(contender));
            assertTrue(attempted.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertThrows(
                    TimeoutException.class,
                    () -> second.get(BLOCKED_CONTENDER_MILLISECONDS, TimeUnit.MILLISECONDS),
                    "contender cannot check/write while the owner holds its company fact lock");
            release.countDown();
            first.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ExecutionException rejected =
                    assertThrows(
                            ExecutionException.class,
                            () -> second.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(
                    com.robot.common.core.exception.ServiceException.class, rejected.getCause());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void actor(Runnable work) {
        try (var actor = mockStatic(AuthUtil.class)) {
            actor.when(AuthUtil::getLoginCompanyId).thenReturn("cmp-robot");
            actor.when(AuthUtil::getLoginUserId).thenReturn("operator");
            actor.when(AuthUtil::getLoginUsername).thenReturn("operator");
            work.run();
        }
    }

    private static void assertCounts(
            AnnotationConfigApplicationContext context, String relation, String action) {
        var jdbc = context.getBean(JdbcTemplate.class);
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM "
                                + relation
                                + " WHERE user_id='u-race' AND delete_flag=1",
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_permission_audit_log WHERE action=?",
                        Integer.class,
                        action));
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_cache_outbox", Integer.class));
        assertEquals(
                V13_SEEDED_PERMISSIONS_EPOCH + 1,
                jdbc.queryForObject(
                        "SELECT version FROM sys_cache_epoch WHERE scope='permissions'",
                        Long.class));
    }

    @Test
    @Timeout(40)
    @ResourceLock(Resources.TIME_ZONE)
    void mysqlControlTimestampsRetainUtcWallValuesUnderNonUtcJvmDefault() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
        try {
            isolated(
                    context -> {
                        var jdbc = context.getBean(JdbcTemplate.class);
                        var store = context.getBean(PermissionStateStore.class);
                        LocalDateTime expected = LocalDateTime.parse("2020-06-01T00:00:00");
                        jdbc.update(
                                "INSERT INTO"
                                        + (" sys_cache_outbox(id,event_kind,cache_key,cache_version,"
                                                + "created_at,next_attempt_at,completed_at,archived_at)")
                                        + " VALUES"
                                        + " ('utc-wall','permissions','cache-key',1,?,?,?,?)",
                                expected,
                                expected,
                                expected,
                                expected);
                        var event = store.find("utc-wall");
                        assertEquals(expected, event.createdAt());
                        assertEquals(expected, event.nextAttemptAt());
                        assertEquals(expected, event.completedAt());
                        assertEquals(expected, event.archivedAt());
                        jdbc.update(
                                "INSERT INTO"
                                        + (" sys_cache_outbox_replay_log(id,event_id,actor_user_id,actor_company_id,"
                                                + "reason,previous_revision,new_revision,created_at)")
                                        + " VALUES"
                                        + " ('utc-audit','utc-wall','fixture','cmp-robot','RECOVERY_CHECK',0,1,?)",
                                expected);
                        assertEquals(expected, store.replayAudits("utc-wall").get(0).createdAt());
                    });
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    private static void isolated(Check check) throws Exception {
        assertEquals("true", System.getProperty("robot.verify.mysql.enabled"));
        String database = "robot_verify_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(url(""), user(), password())) {
            try (var sql = admin.createStatement()) {
                sql.executeUpdate("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
            }
            try {
                Flyway.configure()
                        .dataSource(url(database), user(), password())
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(false)
                        .cleanDisabled(true)
                        .load()
                        .migrate();
                try (var context = new AnnotationConfigApplicationContext()) {
                    context.getEnvironment()
                            .getPropertySources()
                            .addFirst(
                                    new MapPropertySource(
                                            "isolated-mysql",
                                            Map.of(
                                                    "test.database.url",
                                                    url(database),
                                                    "mybatis-plus.mapper-locations",
                                                    "classpath*:mapper/**/*.xml",
                                                    "mybatis-plus.global-config.db-config.logic-delete-field",
                                                    "deleteFlag",
                                                    "mybatis-plus.global-config.db-config.id-type",
                                                    "assign_uuid",
                                                    "mybatis-plus.configuration.map-underscore-to-camel-case",
                                                    "true",
                                                    "mybatis-plus.global-config.db-config.logic-delete-value",
                                                    "0",
                                                    "mybatis-plus.global-config.db-config.logic-not-delete-value",
                                                    "1",
                                                    "robot.mybatis.tenant.enabled",
                                                    "false",
                                                    "robot.bootstrap.platform-admin-enabled",
                                                    "true",
                                                    "robot.bootstrap.platform-admin-user-id",
                                                    "usr-admin")));
                    context.register(Config.class);
                    context.refresh();
                    var jdbc = context.getBean(JdbcTemplate.class);
                    jdbc.update(
                            "INSERT INTO"
                                    + (" sys_user(id,username,password_hash,nickname,status_flag,"
                                            + "create_user_no,create_date_time)")
                                    + " VALUES('u-race','race_member','fixture-hash','Race"
                                    + " member',1,'fixture',CURRENT_TIMESTAMP)");
                    jdbc.update(
                            "INSERT INTO"
                                    + ((" sys_user_company(id,user_id,company_id,primary_flag,"
                                                    + "status_flag,create_user_no,")
                                            + "create_date_time)")
                                    + " VALUES('m-race','u-race','cmp-robot',1,1,'fixture',CURRENT_TIMESTAMP)");
                    jdbc.update(
                            "INSERT INTO"
                                    + (" sys_role(id,company_id,role_name,role_code,data_scope,status_flag,"
                                            + "create_user_no,create_date_time)")
                                    + " VALUES('r-race','cmp-robot','Race"
                                    + " role','RACE_ROLE','self',1,'fixture',CURRENT_TIMESTAMP)");
                    check.run(context);
                }
            } finally {
                try (var sql = admin.createStatement()) {
                    sql.executeUpdate("DROP DATABASE " + database);
                }
            }
        }
    }

    private static String url(String database) {
        String host = System.getenv().getOrDefault("ROBOT_VERIFY_MYSQL_HOST", "127.0.0.1");
        if (!List.of("127.0.0.1", "localhost", "::1").contains(host)) {
            throw new IllegalArgumentException("loopback only");
        }
        int port =
                Integer.parseInt(System.getenv().getOrDefault("ROBOT_VERIFY_MYSQL_PORT", "3306"));
        if (port < 1 || port > MAX_TCP_PORT) {
            throw new IllegalArgumentException("invalid port");
        }
        return "jdbc:mysql://"
                + (host.contains(":") ? "[" + host + "]" : host)
                + ":"
                + port
                + "/"
                + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
                + "&connectTimeout=2000&socketTimeout=10000";
    }

    private static String user() {
        String user = System.getenv("ROBOT_VERIFY_MYSQL_USER");
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("ROBOT_VERIFY_MYSQL_USER required");
        }
        return user;
    }

    private static String password() {
        return System.getenv().getOrDefault("ROBOT_VERIFY_MYSQL_PASSWORD", "");
    }

    @FunctionalInterface
    interface Check {
        void run(AnnotationConfigApplicationContext context) throws Exception;
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @ImportAutoConfiguration({
        MybatisPlusAutoConfiguration.class,
        RobotMybatisAutoConfiguration.class
    })
    @MapperScan(basePackages = "com.robot.system", annotationClass = Mapper.class)
    @Import({
        RoleCommandService.class,
        TemporaryAuthorizationCommandService.class,
        UserService.class,
        UserQueryService.class,
        MemberRoleCommandSupport.class,
        PlatformAdministratorBootstrap.class,
        PermissionChangeRecorder.class,
        CommittedCacheChanges.class
    })
    static class Config {
        @Bean
        DataSource dataSource(org.springframework.core.env.Environment environment) {
            return new DriverManagerDataSource(
                    environment.getRequiredProperty("test.database.url"), user(), password());
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PermissionStateStore stateStore(JdbcTemplate jdbc) {
            return spy(new PermissionStateStore(jdbc));
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        BCryptPasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(FIXTURE_BCRYPT_COST);
        }

        @Bean
        GlobalDirectoryPolicy directory() {
            return mock(GlobalDirectoryPolicy.class);
        }

        @Bean
        DataScopeService scopes() {
            return mock(DataScopeService.class);
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper mapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        }

        @Bean
        PermissionGrantPolicy grants() {
            var policy = mock(PermissionGrantPolicy.class);
            var role = new SysRole();
            role.setId("r-operator");
            when(policy.requireOperator())
                    .thenReturn(
                            new PermissionGrantPolicy.Operator(
                                    "operator",
                                    "cmp-robot",
                                    new EffectiveRoleResolver.EffectiveRoles(true, List.of(role))));
            return policy;
        }
    }
}
