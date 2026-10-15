package com.robot.system.config;

import com.robot.common.core.util.JdbcUrlParameters;
import com.robot.common.core.util.ProductionConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** 生产开放前检查数据库实际交接事实，配置中的完成声明不能替代它。 */
@Component
@Order(200)
@RequiredArgsConstructor
public class SystemProductionBootstrapGuard implements ApplicationRunner {
    /** 持久解释合同；不等同于授权epoch，不由普通HTTP请求修改。 */
    public static final String AUTHORIZATION_UTC_CONTRACT_SCOPE = "authorization-time-utc-v1";

    private static final long MAX_DATABASE_MILLIS = 5000;
    private static final long MAX_SOCKET_MILLIS = 10000;
    private static final long MAX_LOCK_WAIT_SECONDS = 5;

    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments arguments) {
        if (!isProduction()) {
            return;
        }
        validateNetworkBudget();
        validateSessionBudget();
        validateBootstrapFacts();
        validateTemporalContract();
    }

    private boolean isProduction() {
        return ProductionConfiguration.protectedEnvironment(
                environment.getActiveProfiles(), environment::getProperty);
    }

    private void validateNetworkBudget() {
        String url = environment.getProperty("spring.datasource.url", "");
        if (!boundedUrlTimeout(url, "connectTimeout", MAX_DATABASE_MILLIS)
                || !boundedUrlTimeout(url, "socketTimeout", MAX_SOCKET_MILLIS)) {
            throw new IllegalStateException("生产数据库连接与网络预算未配置");
        }
    }

    private void validateSessionBudget() {
        Long locks = jdbc.queryForObject("SELECT @@session.innodb_lock_wait_timeout", Long.class);
        Long queries = jdbc.queryForObject("SELECT @@session.max_execution_time", Long.class);
        String zone = jdbc.queryForObject("SELECT @@session.time_zone", String.class);
        if (locks == null
                || locks < 1
                || locks > MAX_LOCK_WAIT_SECONDS
                || queries == null
                || queries < 1
                || queries > MAX_DATABASE_MILLIS
                || !"+00:00".equals(zone)) {
            throw new IllegalStateException("生产数据库查询/锁预算或UTC会话尚未生效");
        }
    }

    private void validateBootstrapFacts() {
        Long administrators =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_user WHERE platform_admin_flag = 1 "
                                + "AND delete_flag = 1 AND status_flag = 1",
                        Long.class);
        boolean developerCredential =
                jdbc
                        .queryForList(
                                "SELECT password_hash FROM sys_user WHERE username = 'admin' "
                                        + "AND delete_flag = 1 AND status_flag = 1",
                                String.class)
                        .stream()
                        .anyMatch(hash -> passwordEncoder.matches("admin123", hash));
        if (administrators == null || administrators < 1 || developerCredential) {
            throw new IllegalStateException("生产账号与平台管理员交接尚未完成");
        }
    }

    private void validateTemporalContract() {
        if (hasUtcContract()) {
            return;
        }
        Long legacy =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_temp_authorization"
                                + " WHERE delete_flag = 1 AND status_flag = 1",
                        Long.class);
        if (legacy == null || legacy > 0) {
            throw new IllegalStateException("临时授权UTC合同未确认，请先受控对账历史时间");
        }
        jdbc.update(
                "INSERT INTO sys_cache_epoch(scope, version) SELECT ?, 1 FROM DUAL"
                        + " WHERE NOT EXISTS (SELECT 1 FROM sys_temp_authorization"
                        + " WHERE delete_flag = 1 AND status_flag = 1)"
                        + " ON DUPLICATE KEY UPDATE version = version",
                AUTHORIZATION_UTC_CONTRACT_SCOPE);
        if (!hasUtcContract()) {
            throw new IllegalStateException("临时授权UTC合同初始化失败，请重新受控核验");
        }
    }

    private boolean hasUtcContract() {
        var markers =
                jdbc.queryForList(
                        "SELECT scope, version FROM sys_cache_epoch WHERE scope = ?",
                        AUTHORIZATION_UTC_CONTRACT_SCOPE);
        if (markers.size() != 1) {
            return false;
        }
        var marker = markers.get(0);
        return AUTHORIZATION_UTC_CONTRACT_SCOPE.equals(marker.get("scope"))
                && marker.get("version") instanceof Number version
                && version.longValue() >= 1;
    }

    private static boolean boundedUrlTimeout(String url, String key, long maximum) {
        String value = JdbcUrlParameters.uniqueValue(url, key).orElse(null);
        if (value == null) {
            return false;
        }
        try {
            long millis = Long.parseLong(value);
            return millis > 0 && millis <= maximum;
        } catch (NumberFormatException error) {
            return false;
        }
    }
}
