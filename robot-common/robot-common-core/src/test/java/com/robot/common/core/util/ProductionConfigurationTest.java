package com.robot.common.core.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductionConfigurationTest {
    private static final int SECRET_FILLER_CHARACTERS = 32;

    private Map<String, String> valid() {
        Map<String, String> values = new HashMap<>();
        values.put("robot.env", "prod-x");
        values.put("robot.management.secret", "management-" + "x".repeat(SECRET_FILLER_CHARACTERS));
        values.put(
                "robot.security.inner-secret", "service-" + "y".repeat(SECRET_FILLER_CHARACTERS));
        values.put("spring.data.redis.host", "redis.fixture");
        values.put("robot.production.redis-isolated", "true");
        values.put("spring.data.redis.ssl.enabled", "true");
        values.put("spring.data.redis.password", "redis-" + "z".repeat(SECRET_FILLER_CHARACTERS));
        values.put("robot.production.bootstrap-complete", "true");
        values.put(
                "spring.datasource.url",
                "jdbc:mysql://db.fixture/database?sslMode=VERIFY_IDENTITY");
        values.put("spring.datasource.username", "robot-runtime");
        values.put(
                "spring.datasource.password", "database-" + "a".repeat(SECRET_FILLER_CHARACTERS));
        values.put("spring.flyway.user", "robot-migration");
        values.put("spring.flyway.password", "migration-" + "b".repeat(SECRET_FILLER_CHARACTERS));
        return values;
    }

    @Test
    void acceptsCompleteConfigurationAndLocalRemainsUsable() {
        assertDoesNotThrow(
                () -> ProductionConfiguration.validate(new String[] {"prod"}, valid()::get));
        assertDoesNotThrow(
                () ->
                        ProductionConfiguration.validate(
                                new String[] {"local"}, Map.<String, String>of()::get));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "robot.env",
                "robot.management.secret",
                "robot.security.inner-secret",
                "spring.data.redis.host",
                "robot.production.redis-isolated",
                "spring.data.redis.ssl.enabled",
                "spring.data.redis.password",
                "robot.production.bootstrap-complete",
                "spring.datasource.username",
                "spring.datasource.password",
                "spring.flyway.user",
                "spring.flyway.password"
            })
    void refusesEachMissingRequirementWithoutIncludingTheValue(String key) {
        Map<String, String> values = valid();
        String secret = values.remove(key);
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> ProductionConfiguration.validate(new String[] {"prod"}, values::get),
                        key);
        assertFalse(failure.getMessage().contains(secret));
    }

    @Test
    void refusesIllegalEnvironmentAndRootDatabaseIdentity() {
        Map<String, String> values = valid();
        values.put("robot.env", "Prod_X");
        assertThrows(
                IllegalStateException.class,
                () -> ProductionConfiguration.validate(new String[] {"prod"}, values::get));
        Map<String, String> root = valid();
        root.put("spring.datasource.username", "root");
        assertThrows(
                IllegalStateException.class,
                () -> ProductionConfiguration.validate(new String[] {"prod"}, root::get));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://db.fixture/database?sslMode=VERIFY_IDENTITY&sslMode=DISABLED",
                "jdbc:mysql://db.fixture/database?sslMode=DISABLED&sslMode=VERIFY_IDENTITY",
                "jdbc:mysql://db.fixture/database?sslMode=VERIFY_IDENTITY&ssl%4dode=DISABLED",
                "jdbc:mysql://db.fixture/database?sslMode=VERIFY_IDENTITY&SSLMODE=DISABLED",
                "jdbc:mysql://db.fixture/database?label=sslMode=VERIFY_IDENTITY",
                "jdbc:mysql://db.fixture/database?sslMode=VERIFY_IDENTITY_EXTRA",
                "jdbc:mysql://db.fixture/database#sslMode=VERIFY_IDENTITY",
                "jdbc:mysql://db.fixture/database?sslMode=VERIFY_IDENTITY#fragment",
                "jdbc:mysql://db.fixture/database?sslMode=%ZZ",
                "jdbc:postgresql://db.fixture/database?sslMode=VERIFY_IDENTITY"
            })
    void refusesAmbiguousOrMisleadingTlsConfiguration(String jdbc) {
        Map<String, String> values = valid();
        values.put("spring.datasource.url", jdbc);
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> ProductionConfiguration.validate(new String[] {"prod"}, values::get));
        assertFalse(failure.getMessage().contains(jdbc));
    }

    @Test
    void productionIdentityCannotAccidentallyStartAsLocalAndRemoteImportCannotBeOptional() {
        Map<String, String> values = valid();
        assertThrows(
                IllegalStateException.class,
                () -> ProductionConfiguration.validate(new String[] {"local"}, values::get));
        values.put("spring.cloud.nacos.config.enabled", "true");
        values.put("spring.config.import", "optional:nacos:robot-system.yaml");
        assertThrows(
                IllegalStateException.class,
                () -> ProductionConfiguration.validate(new String[] {"nacos"}, values::get));
        values.put("spring.config.import", "nacos:robot-system.yaml");
        assertDoesNotThrow(
                () -> ProductionConfiguration.validate(new String[] {"nacos"}, values::get));
    }
}
