package com.robot.common.redis.core;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 序列化约定（wl-skills-bd standards/20 §6）：
 *
 * <ul>
 *   <li>Key / HashKey 一律 StringRedisSerializer，保证可读可排查；
 *   <li>Value 用 Jackson JSON + 类型信息，注册 JavaTimeModule 支持 LocalDateTime；
 *   <li>只允许 Robot 缓存模型与已登记的基础容器，拒绝任意第三方类型。
 * </ul>
 */
@AutoConfiguration
public class RobotRedisAutoConfiguration {

    /**
     * 配置绑定完成后，将未提供的 Redis 凭据归一为 null，避免 Redisson 发送空 AUTH。 static 且无依赖，保证注册此处理器时不会提前实例化 Redis
     * 客户端或配置 Bean。
     */
    @Bean
    public static BeanPostProcessor redisCredentialsNormalizer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataRedisProperties properties) {
                    properties.setUsername(blankToNull(properties.getUsername()));
                    properties.setPassword(blankToNull(properties.getPassword()));
                }
                return bean;
            }
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Bean
    @ConditionalOnMissingBean(name = "robotRedisTemplate")
    public RedisTemplate<String, Object> robotRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.robot.")
                        .allowIfSubType(java.util.ArrayList.class)
                        .allowIfSubType(java.util.HashMap.class)
                        .allowIfSubType(java.util.LinkedHashMap.class)
                        .allowIfSubType(java.util.HashSet.class)
                        .allowIfSubType(java.util.LinkedHashSet.class)
                        .allowIfSubType(java.time.LocalDateTime.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        Jackson2JsonRedisSerializer<Object> valueSerializer =
                new Jackson2JsonRedisSerializer<>(mapper, Object.class);
        StringRedisSerializer keySerializer = StringRedisSerializer.UTF_8;

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnMissingBean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
