package com.robot.common.redis.core;

import com.robot.common.redis.key.RedisKeys;
import com.robot.common.redis.lock.LockTemplate;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.data.redis.core.RedisTemplate;

/** 缓存读穿透与持锁双检：区分有效空占位、类型错误和真实未命中。 */
final class RedisCacheLoader {
    private RedisCacheLoader() {}

    record Request<T>(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        @Override
        public String toString() {
            return "CacheRequest[业务输入已隐藏]";
        }
    }

    private record Lookup<T>(boolean present, T value) {
        @Override
        public String toString() {
            return "CacheLookup[present=" + present + "]";
        }
    }

    static <T> T load(
            RedisTemplate<String, Object> template, LockTemplate lock, Request<T> request) {
        Lookup<T> cached = lookup(template, request);
        if (cached.present()) {
            return cached.value();
        }
        if (lock != null) {
            return lock.execute(
                    RedisKeys.rebuildLock(request.key()),
                    () -> {
                        Lookup<T> filled = lookup(template, request);
                        return filled.present() ? filled.value() : loadAndFill(template, request);
                    });
        }
        return loadAndFill(template, request);
    }

    private static <T> Lookup<T> lookup(
            RedisTemplate<String, Object> template, Request<T> request) {
        Object cached = template.opsForValue().get(request.key());
        if (cached == null) {
            return new Lookup<>(false, null);
        }
        if (RedisHelper.NULL_PLACEHOLDER.equals(cached)) {
            return new Lookup<>(true, null);
        }
        if (request.type().isInstance(cached)) {
            return new Lookup<>(true, request.type().cast(cached));
        }
        template.unlink(request.key());
        return new Lookup<>(false, null);
    }

    private static <T> T loadAndFill(RedisTemplate<String, Object> template, Request<T> request) {
        T loaded = request.loader().get();
        if (loaded == null) {
            template.opsForValue()
                    .set(
                            request.key(),
                            RedisHelper.NULL_PLACEHOLDER,
                            RedisKeys.TTL_NULL_PLACEHOLDER);
            return null;
        }
        template.opsForValue().set(request.key(), loaded, RedisHelper.withJitter(request.ttl()));
        return loaded;
    }
}
