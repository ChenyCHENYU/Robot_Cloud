package com.robot.system.permission.service;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.robot.common.redis.core.RedisHelper;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Explicitly registered outbox transaction/metrics fixtures, excluded from application component
 * scans.
 */
@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
final class CacheOutboxTestConfiguration {
    @Bean
    DataSource source() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("cache-outbox-schema.sql"))
                .execute(source);
        return source;
    }

    @Bean
    JdbcTemplate jdbc(DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    PlatformTransactionManager manager(DataSource source) {
        return new DataSourceTransactionManager(source);
    }

    @Bean
    PermissionStateStore store(JdbcTemplate jdbc) {
        return new PermissionStateStore(jdbc);
    }

    @Bean
    RedisHelper redis() {
        return mock(RedisHelper.class);
    }

    @Bean
    EffectiveRoleResolver resolver() {
        return mock(EffectiveRoleResolver.class);
    }

    @Bean
    PermissionGrantPolicy policy(EffectiveRoleResolver resolver) {
        return new PermissionGrantPolicy(
                resolver, mock(SysRoleMenuMapper.class), mock(SysRoleButtonMapper.class));
    }

    @Bean
    SimpleMeterRegistry meters() {
        return new SimpleMeterRegistry();
    }

    @Bean
    CacheOutboxMetrics metrics(SimpleMeterRegistry meters) {
        return new CacheOutboxMetrics(meters);
    }

    @Bean
    CacheEventDispatcher dispatcher(
            PermissionStateStore store,
            RedisHelper redis,
            PlatformTransactionManager manager,
            CacheOutboxMetrics metrics) {
        return new CacheEventDispatcher(store, redis, manager, metrics);
    }

    @Bean
    ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Bean
    CacheOutboxGovernanceService service(
            PermissionStateStore store,
            PermissionGrantPolicy policy,
            CacheEventDispatcher dispatcher,
            CacheOutboxMetrics metrics,
            ObjectMapper mapper) {
        return new CacheOutboxGovernanceService(
                store, policy, dispatcher, metrics, mapper, mock(GlobalDirectoryPolicy.class));
    }
}
