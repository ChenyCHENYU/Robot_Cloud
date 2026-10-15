package com.robot.gateway.config;

import com.robot.common.core.util.ProductionConfiguration;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Enforce gateway protected-environment configuration before serving requests. */
@Configuration(proxyBeanMethods = false)
public class ProductionConfigurationGuard {
    @Bean
    public SmartInitializingSingleton robotGatewayProductionStartupGuard(Environment environment) {
        return () ->
                ProductionConfiguration.validate(
                        environment.getActiveProfiles(), environment::getProperty);
    }
}
