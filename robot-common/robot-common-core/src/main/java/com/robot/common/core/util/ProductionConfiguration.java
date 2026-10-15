package com.robot.common.core.util;

import java.util.Arrays;
import java.util.function.Function;

/** 三服务共同的生产配置前置条件；异常只报配置名称，不输出值或凭据。 */
public final class ProductionConfiguration {
    private static final int MIN_SECRET_CHARACTERS = 32;

    private ProductionConfiguration() {}

    /**
     * Detects protected deployments from all explicit profile and environment signals.
     *
     * @param profiles active Spring profiles
     * @param values resolved property lookup
     * @return whether production guards must be enforced
     */
    public static boolean protectedEnvironment(String[] profiles, Function<String, String> values) {
        String declared = values.apply("robot.env");
        return Boolean.parseBoolean(values.apply("robot.production.enabled"))
                || Arrays.stream(profiles).anyMatch(p -> p.matches("(?i)prod|production|pre"))
                || declared != null && declared.matches("(?i)(prod|production|pre)([-_ ].*)?");
    }

    /**
     * Validates resolved production requirements before accepting requests.
     *
     * @param profiles active Spring profiles
     * @param values resolved property lookup
     * @throws IllegalStateException when a protected configuration is incomplete or unsafe
     */
    public static void validate(String[] profiles, Function<String, String> values) {
        if (!protectedEnvironment(profiles, values)) {
            return;
        }
        require(
                Arrays.stream(profiles).noneMatch(p -> p.equalsIgnoreCase("local")),
                "spring.profiles.active");
        String env = values.apply("robot.env");
        require(
                env != null && env.matches("[a-z0-9]+(?:-[a-z0-9]+)*") && !env.equals("local"),
                "robot.env");
        require(strongSecret(values.apply("robot.management.secret")), "robot.management.secret");
        if (!"robot-gateway".equals(values.apply("spring.application.name"))) {
            validateRedisAndServiceTrust(values);
        }
        require(
                Boolean.parseBoolean(values.apply("robot.production.bootstrap-complete")),
                "robot.production.bootstrap-complete");
        validateManagement(values);
        validateDiscovery(values);
        validateDatabase(values);
    }

    private static void validateManagement(Function<String, String> values) {
        require(
                !Boolean.parseBoolean(values.apply("management.endpoint.gateway.enabled")),
                "management.endpoint.gateway.enabled");
        if (!"robot-gateway".equals(values.apply("spring.application.name"))) {
            return;
        }
        require(
                "none".equals(values.apply("management.endpoint.gateway.access")),
                "management.endpoint.gateway.access");
        String exposed = values.apply("management.endpoints.web.exposure.include");
        if (exposed != null) {
            require(
                    Arrays.stream(exposed.split(","))
                            .map(String::trim)
                            .noneMatch(
                                    endpoint -> endpoint.equals("*") || endpoint.equals("gateway")),
                    "management.endpoints.web.exposure.include gateway");
        }
    }

    private static void validateRedisAndServiceTrust(Function<String, String> values) {
        require(values.apply("spring.data.redis.host") != null, "spring.data.redis.host");
        require(
                strongSecret(values.apply("robot.security.inner-secret")),
                "robot.security.inner-secret");
        require(
                Boolean.parseBoolean(values.apply("robot.production.redis-isolated")),
                "robot.production.redis-isolated");
        require(
                Boolean.parseBoolean(values.apply("spring.data.redis.ssl.enabled")),
                "spring.data.redis.ssl.enabled");
        require(
                strongSecret(values.apply("spring.data.redis.password")),
                "spring.data.redis.password");
    }

    private static void validateDiscovery(Function<String, String> values) {
        if (Boolean.parseBoolean(values.apply("spring.cloud.nacos.config.enabled"))) {
            String imports = values.apply("spring.config.import");
            require(
                    imports != null
                            && imports.contains("nacos:")
                            && !imports.contains("optional:nacos:"),
                    "spring.config.import mandatory nacos");
        }
    }

    private static void validateDatabase(Function<String, String> values) {
        String jdbc = values.apply("spring.datasource.url");
        if (jdbc != null) {
            require(verifiedIdentityTls(jdbc), "spring.datasource.url TLS");
            String username = values.apply("spring.datasource.username");
            require(
                    username != null && !username.isBlank() && !username.equalsIgnoreCase("root"),
                    "spring.datasource.username");
            require(
                    strongSecret(values.apply("spring.datasource.password")),
                    "spring.datasource.password");
            require(
                    values.apply("spring.flyway.user") != null
                            && !values.apply("spring.flyway.user").equals(username),
                    "spring.flyway.user");
            require(strongSecret(values.apply("spring.flyway.password")), "spring.flyway.password");
        }
    }

    private static boolean verifiedIdentityTls(String jdbc) {
        return JdbcUrlParameters.uniqueValue(jdbc, "sslMode")
                .filter("VERIFY_IDENTITY"::equals)
                .isPresent();
    }

    private static boolean strongSecret(String value) {
        return value != null
                && value.length() >= MIN_SECRET_CHARACTERS
                && !value.isBlank()
                && !value.toLowerCase(java.util.Locale.ROOT).contains("dev-secret")
                && !value.toLowerCase(java.util.Locale.ROOT).contains("change-me");
    }

    private static void require(boolean condition, String setting) {
        if (!condition) {
            throw new IllegalStateException("生产配置校验失败：" + setting);
        }
    }
}
