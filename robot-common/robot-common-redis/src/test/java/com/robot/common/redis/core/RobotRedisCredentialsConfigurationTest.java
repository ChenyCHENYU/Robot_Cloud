package com.robot.common.redis.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/** 使用真实 Spring 配置绑定与 Bean 生命周期；仅连接工厂替换为不联网的测试替身。 */
class RobotRedisCredentialsConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RobotRedisAutoConfiguration.class))
                    .withUserConfiguration(BindingConfiguration.class)
                    .withBean(
                            RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));

    @Test
    void absentCredentialsRemainNull() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    DataRedisProperties properties = context.getBean(DataRedisProperties.class);
                    BindingObserver observer = context.getBean(BindingObserver.class);
                    assertThat(observer.observed).isTrue();
                    assertThat(observer.usernameBeforeInitialization).isNull();
                    assertThat(observer.passwordBeforeInitialization).isNull();
                    assertThat(properties.getUsername()).isNull();
                    assertThat(properties.getPassword()).isNull();
                    verifyNoInteractions(context.getBean(RedisConnectionFactory.class));
                });
    }

    @Test
    void emptyCredentialsAreNormalizedAfterBinding() {
        assertNormalized("", "");
    }

    @Test
    void whitespaceCredentialsAreNormalizedAfterBinding() {
        assertNormalized(" \t", "\t \n");
    }

    @Test
    void nonblankCredentialsKeepTheirExactBoundValue() {
        withCredentials(" test-user ", " test-password ")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            DataRedisProperties properties =
                                    context.getBean(DataRedisProperties.class);
                            BindingObserver observer = context.getBean(BindingObserver.class);
                            assertThat(observer.observed).isTrue();
                            assertThat(observer.usernameBeforeInitialization)
                                    .isEqualTo(" test-user ");
                            assertThat(observer.passwordBeforeInitialization)
                                    .isEqualTo(" test-password ");
                            assertThat(properties.getUsername()).isEqualTo(" test-user ");
                            assertThat(properties.getPassword()).isEqualTo(" test-password ");
                            verifyNoInteractions(context.getBean(RedisConnectionFactory.class));
                        });
    }

    private void assertNormalized(String username, String password) {
        withCredentials(username, password)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            DataRedisProperties properties =
                                    context.getBean(DataRedisProperties.class);
                            BindingObserver observer = context.getBean(BindingObserver.class);
                            assertThat(observer.observed).isTrue();
                            assertThat(observer.usernameBeforeInitialization).isEqualTo(username);
                            assertThat(observer.passwordBeforeInitialization).isEqualTo(password);
                            assertThat(properties.getUsername()).isNull();
                            assertThat(properties.getPassword()).isNull();
                            verifyNoInteractions(context.getBean(RedisConnectionFactory.class));
                        });
    }

    private ApplicationContextRunner withCredentials(String username, String password) {
        return contextRunner.withInitializer(
                context ->
                        context.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new MapPropertySource(
                                                "redis-credentials-test",
                                                Map.of(
                                                        "spring.data.redis.username", username,
                                                        "spring.data.redis.password", password))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataRedisProperties.class)
    static class BindingConfiguration {
        @Bean
        static BindingObserver redisBindingObserver() {
            return new BindingObserver();
        }
    }

    /** 在 Boot 绑定处理器之后观察原值，验证生产处理器只在初始化完成后规范化。 */
    static class BindingObserver implements BeanPostProcessor, PriorityOrdered {
        private boolean observed;
        private String usernameBeforeInitialization;
        private String passwordBeforeInitialization;

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 2;
        }

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (bean instanceof DataRedisProperties properties) {
                observed = true;
                usernameBeforeInitialization = properties.getUsername();
                passwordBeforeInitialization = properties.getPassword();
            }
            return bean;
        }
    }
}
