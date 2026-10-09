package com.robot.common.web.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.robot.common.web.filter.TraceIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.format.DateTimeFormatter;

/**
 * Web 层自动装配：Jackson 序列化约定 + TraceId 过滤器。
 *
 * <p>序列化约定（前后端契约）：
 * <ul>
 *   <li>时间统一 {@code yyyy-MM-dd HH:mm:ss} 字符串；</li>
 *   <li>ID 由合同使用 String；计数、页码和令牌期限保持 JSON number。</li>
 *   <li>反序列化忽略未知字段，保证契约向后兼容。</li>
 * </ul>
 */
@AutoConfiguration
public class RobotWebAutoConfiguration implements WebMvcConfigurer {

    public static final String DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    @ConditionalOnMissingBean
    public Jackson2ObjectMapperBuilderCustomizer robotJacksonCustomizer() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATETIME_PATTERN);
        return builder -> builder
                .serializers(new LocalDateTimeSerializer(formatter))
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
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Integer.MIN_VALUE + 100);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
