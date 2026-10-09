package com.robot.api.system.feign;

import com.robot.api.system.config.RemoteSystemFeignConfiguration;
import com.robot.api.system.config.RobotApiAutoConfiguration;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.config.RobotSecurityAutoConfiguration;
import com.robot.common.redis.core.RedisHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Target;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RemoteSystemFeignTrustTest {
    @AfterEach
    void cleanup() {
        InnerTrustContext.clear();
    }

    @Test
    void apiAutoConfigurationDoesNotInstallGlobalCredentials() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RobotApiAutoConfiguration.class,
                        RobotSecurityAutoConfiguration.class))
                .withBean(RedisHelper.class, () -> mock(RedisHelper.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(RobotSecurityAutoConfiguration.class);
                    assertThat(context.getBeansOfType(RequestInterceptor.class)).isEmpty();
                    FeignClient client = RemoteSystemService.class.getAnnotation(FeignClient.class);
                    assertThat(client.configuration()).containsExactly(RemoteSystemFeignConfiguration.class);
                    try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
                        child.setParent(context.getSourceApplicationContext());
                        child.register(RemoteSystemFeignConfiguration.class);
                        child.refresh();
                        assertThat(child.getBeansOfType(RequestInterceptor.class)).hasSize(2);
                        assertThat(context.getBeansOfType(RequestInterceptor.class)).isEmpty();
                    }
                });
    }

    @Test
    void clientConfigurationReadsEnvironmentAndOverwritesForgedHeadersOnlyForItsAudience() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("robot.security.inner-secret", "client-secret")));
        RemoteSystemFeignConfiguration configuration = new RemoteSystemFeignConfiguration();
        RequestInterceptor secret = configuration.innerCallRelayInterceptor(environment);
        RequestInterceptor delegate = configuration.innerTrustRelayInterceptor();
        InnerTrustContext.bindVerifiedService("u1", "c1", "alice");
        RequestTemplate request = template(RemoteSystemService.class, "/inner/menu/c1/routes");
        request.header(RobotConstants.HEADER_INNER_CALL, "spoofed");
        request.header(RobotConstants.HEADER_INNER_USER, "spoofed");
        secret.apply(request);
        delegate.apply(request);
        assertThat(request.headers().get(RobotConstants.HEADER_INNER_CALL)).containsExactly("client-secret");
        assertThat(request.headers().get(RobotConstants.HEADER_INNER_USER)).containsExactly("u1");
        assertThat(request.headers().get(RobotConstants.HEADER_INNER_COMPANY)).containsExactly("c1");
        assertThat(request.headers().get(RobotConstants.HEADER_INNER_USERNAME)).containsExactly("alice");

        RequestTemplate provision = template(RemoteSystemService.class, "/inner/user/u2/contexts");
        provision.header(RobotConstants.HEADER_INNER_USER, "spoofed");
        secret.apply(provision);
        delegate.apply(provision);
        assertThat(provision.headers().get(RobotConstants.HEADER_INNER_CALL)).containsExactly("client-secret");
        assertThat(provision.headers()).doesNotContainKeys(RobotConstants.HEADER_INNER_USER,
                RobotConstants.HEADER_INNER_COMPANY, RobotConstants.HEADER_INNER_USERNAME);

        for (RequestTemplate unrelated : new RequestTemplate[]{
                template(UnrelatedService.class, "/inner/probe"),
                template(RemoteSystemService.class, "/public/probe")}) {
            unrelated.header(RobotConstants.HEADER_INNER_CALL, "spoofed");
            unrelated.header(RobotConstants.HEADER_INNER_USER, "spoofed");
            secret.apply(unrelated);
            delegate.apply(unrelated);
            assertThat(unrelated.headers()).doesNotContainKeys(RobotConstants.HEADER_INNER_CALL,
                    RobotConstants.HEADER_INNER_USER, RobotConstants.HEADER_INNER_COMPANY,
                    RobotConstants.HEADER_INNER_USERNAME);
        }
        InnerTrustContext.clear();
        delegate.apply(request);
        assertThat(request.headers()).doesNotContainKeys(RobotConstants.HEADER_INNER_USER,
                RobotConstants.HEADER_INNER_COMPANY, RobotConstants.HEADER_INNER_USERNAME);
    }

    private static <T> RequestTemplate template(Class<T> type, String path) {
        RequestTemplate template = new RequestTemplate().uri(path);
        template.feignTarget(new Target.HardCodedTarget<>(type, "service", "http://service"));
        return template;
    }

    interface UnrelatedService { }
}
