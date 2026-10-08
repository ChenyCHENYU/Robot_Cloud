package com.robot.common.redis.core;

import com.robot.common.redis.lock.LockTemplate;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Redis 组件装配：RedisHelper 门面 + LockTemplate 分布式锁。
 */
@AutoConfiguration
public class RobotRedisHelperAutoConfiguration {

    @Bean
    public RedisHelper redisHelper(org.springframework.data.redis.core.StringRedisTemplate stringTemplate,
                                   org.springframework.data.redis.core.RedisTemplate<String, Object> robotRedisTemplate,
                                   org.springframework.beans.factory.ObjectProvider<LockTemplate> lockTemplates) {
        RedisHelper helper = new RedisHelper(stringTemplate, robotRedisTemplate);
        // 锁可用时自动启用互斥重建（防击穿）；无 Redisson 环境退化为直接回源
        LockTemplate lockTemplate = lockTemplates.getIfAvailable();
        if (lockTemplate != null) {
            helper.setLockTemplate(lockTemplate);
        }
        return helper;
    }

    @Bean
    public LockTemplate lockTemplate(RedissonClient redissonClient) {
        return new LockTemplate(redissonClient);
    }
}
