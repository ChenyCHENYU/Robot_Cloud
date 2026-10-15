package com.robot.common.web.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.robot.common.web.filter.TraceIdFilter;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层自动装配：Jackson 序列化约定 + TraceId 过滤器。
 *
 * <p>序列化约定（前后端契约）：
 *
 * <ul>
 *   <li>时间统一 UTC {@code yyyy-MM-dd HH:mm:ss} 字符串，非法日历日期拒绝而不归一；
 *   <li>ID 由合同使用 String；计数、页码和令牌期限保持 JSON number。
 *   <li>反序列化忽略未知字段，保证契约向后兼容。
 * </ul>
 */
@AutoConfiguration(
        afterName =
                "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration")
public class RobotWebAutoConfiguration implements WebMvcConfigurer {

    private static final int TRACE_FILTER_OFFSET = 100;
    private static final int BODY_FILTER_OFFSET = 110;

    public static final String DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    @ConditionalOnMissingBean
    public Jackson2ObjectMapperBuilderCustomizer robotJacksonCustomizer() {
        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                        .withResolverStyle(ResolverStyle.STRICT);
        return builder ->
                builder.serializers(new LocalDateTimeSerializer(formatter))
                        .deserializers(new LocalDateTimeDeserializer(formatter))
                        .failOnUnknownProperties(false);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.robot.common.web.handler.GlobalExceptionHandler robotGlobalExceptionHandler() {
        return new com.robot.common.web.handler.GlobalExceptionHandler();
    }

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Integer.MIN_VALUE + TRACE_FILTER_OFFSET);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<com.robot.common.web.filter.RequestBodyLimitFilter>
            requestBodyLimitFilter() {
        var registration =
                new FilterRegistrationBean<>(
                        new com.robot.common.web.filter.RequestBodyLimitFilter());
        registration.setOrder(Integer.MIN_VALUE + BODY_FILTER_OFFSET);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
            io.micrometer.core.instrument.MeterRegistry.class)
    @ConditionalOnMissingBean
    public com.robot.common.web.handler.BusinessResultMetrics businessResultMetrics(
            io.micrometer.core.instrument.MeterRegistry registry) {
        return new com.robot.common.web.handler.BusinessResultMetrics(registry);
    }
}
