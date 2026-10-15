package com.robot.system.permission.service;

import java.util.UUID;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

/** 错误诊断只传播固定分类和随机关联 ID，绝不读取下游异常 message。 */
final class CacheOutboxFailure {
    enum Category {
        REDIS_UNAVAILABLE,
        REDIS_TIMEOUT,
        REDIS_FAILURE,
        DATABASE_FAILURE,
        INVALID_EVENT
    }

    private CacheOutboxFailure() {}

    static Category redisCategory(RuntimeException error) {
        if (error instanceof RedisConnectionFailureException) {
            return Category.REDIS_UNAVAILABLE;
        }
        if (error instanceof QueryTimeoutException) {
            return Category.REDIS_TIMEOUT;
        }
        return Category.REDIS_FAILURE;
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }
}
