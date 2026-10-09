package com.robot.common.security.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.robot.common.security.handler.NotLoginExceptionHandler;
import com.robot.common.security.filter.InnerTrustContextFilter;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.interceptor.InnerCallInterceptor;
import com.robot.common.security.interceptor.LoginSessionValidationInterceptor;
import com.robot.common.security.session.SessionStateVerifier;
import com.robot.common.security.session.RedisSessionStateVerifier;
import com.robot.common.security.session.SessionSubjectVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 安全自动装配：默认登录校验、注解式授权、刷新族复验与内部服务鉴权。
 *
 * <p>策略：拦截器只做「注解有效性 + 登录校验」；
 * 服务侧公共路径通过 {@code robot.security.exclude-paths} 显式排除；
 * 内部接口先验证服务凭据，已有登录态仍须复验刷新族，才进入授权/业务读取。
 */
@AutoConfiguration
@ConditionalOnClass(StpUtil.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(RobotSecurityProperties.class)
public class RobotSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NotLoginExceptionHandler robotNotLoginExceptionHandler() {
        return new NotLoginExceptionHandler();
    }

    /** 所有 HTTP 调度的入口/出口清理，不依赖 MVC handler 成功匹配或放行。 */
    @Bean
    public FilterRegistrationBean<InnerTrustContextFilter> innerTrustContextFilter() {
        FilterRegistrationBean<InnerTrustContextFilter> registration =
                new FilterRegistrationBean<>(new InnerTrustContextFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST,
                jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(SessionStateVerifier.class)
    public SessionStateVerifier sessionStateVerifier(RedisHelper redisHelper, ObjectMapper objectMapper,
            ObjectProvider<SessionSubjectVerifier> subjectVerifiers) {
        return new RedisSessionStateVerifier(redisHelper, objectMapper, subjectVerifiers);
    }

    @Bean
    public WebMvcConfigurer robotSaTokenInterceptorConfigurer(RobotSecurityProperties properties,
            SessionStateVerifier sessionStateVerifier) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new InnerCallInterceptor(properties))
                        .addPathPatterns("/inner/**").order(Ordered.HIGHEST_PRECEDENCE);
                registry.addInterceptor(new SaInterceptor(handler -> StpUtil.checkLogin()))
                        .addPathPatterns("/**")
                        .excludePathPatterns("/inner/**")
                        .excludePathPatterns(properties.getExcludePaths());
                registry.addInterceptor(new LoginSessionValidationInterceptor(sessionStateVerifier))
                        .addPathPatterns("/**")
                        .order(Ordered.HIGHEST_PRECEDENCE + 1)
                        .excludePathPatterns(properties.getExcludePaths().stream()
                                .filter(path -> !path.startsWith("/inner")).toList());
            }
        };
    }
}
