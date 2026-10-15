package com.robot.gateway.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AuthPropertiesBindingTest {
    @Test
    void whitelistBindingReplacesTheDefaultsWithAnIndependentSnapshot() {
        new ApplicationContextRunner()
                .withUserConfiguration(Binding.class)
                .withPropertyValues("robot.gateway.auth.white-paths[0]=/auth/login")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var properties = context.getBean(AuthProperties.class);
                            assertEquals(List.of("/auth/login"), properties.getWhitePaths());
                            var source = new ArrayList<>(List.of("/auth/login"));
                            properties.setWhitePaths(source);
                            source.add("/**");
                            assertEquals(List.of("/auth/login"), properties.getWhitePaths());
                            assertThrows(
                                    UnsupportedOperationException.class,
                                    () -> properties.getWhitePaths().add("/**"));
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class Binding {}
}
