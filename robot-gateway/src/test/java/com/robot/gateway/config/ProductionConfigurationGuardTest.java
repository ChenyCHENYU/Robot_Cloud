package com.robot.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionConfigurationGuardTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(ProductionConfigurationGuard.class);

    @Test
    void localAssemblyUsesUniqueBeanNameWithoutEnablingOverrides() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(ProductionConfigurationGuard.class);
                            assertThat(context).hasBean("robotGatewayProductionStartupGuard");
                        });
    }

    @Test
    void productionAssemblyRefusesMissingConfigAndAcceptsExplicitGatewayPosture() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("生产配置校验失败");
                        });
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(
                        "spring.application.name=robot-gateway",
                        "robot.env=prod-fixture",
                        "robot.management.secret=synthetic-management-secret-that-is-long-enough",
                        "robot.production.bootstrap-complete=true",
                        "management.endpoint.gateway.access=none")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void legacyDisabledFlagCannotPermitBoot4GatewayActuatorAccessOrWildcardExposure() {
        var production =
                runner.withInitializer(
                                context -> context.getEnvironment().setActiveProfiles("prod"))
                        .withPropertyValues(
                                "spring.application.name=robot-gateway",
                                "robot.env=prod-fixture",
                                "robot.management.secret=synthetic-management-secret-that-is-long-enough",
                                "robot.production.bootstrap-complete=true",
                                "management.endpoint.gateway.enabled=false");
        production.run(context -> assertThat(context).hasFailed());
        for (String access : new String[] {"read-only", "unrestricted"}) {
            production
                    .withPropertyValues("management.endpoint.gateway.access=" + access)
                    .run(context -> assertThat(context).hasFailed());
        }
        for (String exposure : new String[] {"*", "health,gateway", "health, gateway,info"}) {
            production
                    .withPropertyValues(
                            "management.endpoint.gateway.access=none",
                            "management.endpoints.web.exposure.include=" + exposure)
                    .run(context -> assertThat(context).hasFailed());
        }
        production
                .withPropertyValues(
                        "management.endpoint.gateway.access=none",
                        "management.endpoints.web.exposure.include=health,info,prometheus")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
