package com.robot.common.security.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.robot.common.security.handler.NotLoginExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 安全自动装配：注册 Sa-Token 拦截器（注解式鉴权）与未登录异常处理。
 *
 * <p>策略：拦截器只做「注解有效性 + 登录校验」；
 * 路由级白名单（/auth/login 等）由网关负责，服务侧通过
 * {@code robot.security.exclude-paths} 排除内部接口。
 */
@AutoConfiguration
@ConditionalOnClass(StpUtil.class)
@EnableConfigurationProperties(RobotSecurityProperties.class)
public class RobotSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NotLoginExceptionHandler robotNotLoginExceptionHandler() {
        return new NotLoginExceptionHandler();
    }

    /** 登录后的 Feign 委托上下文透传（仅消费方具备 Feign 时装配）。 */
    @Bean
    @ConditionalOnClass(feign.RequestInterceptor.class)
    @ConditionalOnMissingBean(com.robot.common.security.interceptor.InnerTrustRelayInterceptor.class)
    public feign.RequestInterceptor innerTrustRelayInterceptor() {
        return new com.robot.common.security.interceptor.InnerTrustRelayInterceptor();
    }

    @Bean
    public WebMvcConfigurer robotSaTokenInterceptorConfigurer(RobotSecurityProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new SaInterceptor())
                        .addPathPatterns("/**")
                        .excludePathPatterns(properties.getExcludePaths());
                // 内部接口（/inner/**）不走登录态，改走服务间密钥校验
                registry.addInterceptor(new com.robot.common.security.interceptor.InnerCallInterceptor(
                                properties))
                        .addPathPatterns("/inner/**");
            }
        };
    }
}
