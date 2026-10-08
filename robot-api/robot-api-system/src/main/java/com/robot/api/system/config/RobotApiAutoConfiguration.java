package com.robot.api.system.config;

import com.robot.api.system.feign.InnerCallRelayInterceptor;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.function.Supplier;

/**
 * Feign 内部调用装配：向所有 Feign 客户端注入 X-Inner-Call 透传拦截器。
 * 密钥与提供方（robot.security.inner-secret）同源读取，防两侧漂移。
 */
@AutoConfiguration
public class RobotApiAutoConfiguration {

    private static final String SECRET_PROPERTY = "robot.security.inner-secret";

    private static final String SECRET_ENV = "ROBOT_INNER_SECRET";

    @Bean
    public RequestInterceptor innerCallRelayInterceptor(Environment environment) {
        Supplier<String> supplier = () -> InnerCallRelayInterceptor.resolveSecret(
                environment.getProperty(SECRET_PROPERTY),
                System.getenv(SECRET_ENV));
        return new InnerCallRelayInterceptor(supplier);
    }
}
