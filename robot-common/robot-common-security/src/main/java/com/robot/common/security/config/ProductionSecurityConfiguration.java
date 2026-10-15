package com.robot.common.security.config;

import com.robot.common.core.util.ProductionConfiguration;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Bind the canonical Redis namespace and enforce the declared protected-environment startup
 * posture.
 */
@Configuration(proxyBeanMethods = false)
public class ProductionSecurityConfiguration {
    @Bean
    public SmartInitializingSingleton robotProductionConfigurationGuard(Environment environment) {
        return () -> {
            ProductionConfiguration.validate(
                    environment.getActiveProfiles(), environment::getProperty);
            com.robot.common.redis.key.RedisKeys.configureEnvironment(
                    environment.getProperty(
                            "robot.env", environment.getProperty("ROBOT_ENV", "local")));
        };
    }
}
