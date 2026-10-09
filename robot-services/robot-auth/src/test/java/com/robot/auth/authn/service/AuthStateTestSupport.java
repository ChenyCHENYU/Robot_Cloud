package com.robot.auth.authn.service;

import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.context.LoginUser;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 内存实现只替换 Redis 原子原语及 Sa 存储，不替换认证流程或族状态决策。 */
final class AuthStateTestSupport {

    private AuthStateTestSupport() {
    }

    static final class MemoryRedis extends RedisHelper {

        final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
        final AtomicLong now = new AtomicLong();
        volatile Consumer<String> beforeSet = key -> { };
        volatile Consumer<Boolean> afterCas = committed -> { };

        MemoryRedis() {
            super(null, null);
        }

        @Override
        public Optional<String> getString(String key) {
            Entry entry = entries.get(key);
            if (entry == null || entry.expiresAt <= now.get()) {
                return Optional.empty();
            }
            return Optional.of(entry.value);
        }

        @Override
        public void setStringExact(String key, String value, Duration ttl) {
            beforeSet.accept(key);
            entries.put(key, new Entry(value, now.get() + ttl.toMillis()));
        }

        @Override
        public boolean setStringIfAbsentExact(String key, String value, Duration ttl) {
            AtomicBoolean inserted = new AtomicBoolean();
            entries.compute(key, (ignored, old) -> {
                if (old == null || old.expiresAt <= now.get()) {
                    inserted.set(true);
                    return new Entry(value, now.get() + ttl.toMillis());
                }
                return old;
            });
            return inserted.get();
        }

        @Override
        public Optional<String> consumeString(String key) {
            Entry entry = entries.remove(key);
            return entry == null || entry.expiresAt <= now.get()
                    ? Optional.empty() : Optional.of(entry.value);
        }

        @Override
        public boolean compareAndSetStringExact(String key, String expected, String replacement,
                                                Duration ttl) {
            AtomicBoolean replaced = new AtomicBoolean();
            entries.computeIfPresent(key, (ignored, old) -> {
                if (old.expiresAt > now.get() && old.value.equals(expected)) {
                    replaced.set(true);
                    return new Entry(replacement, now.get() + ttl.toMillis());
                }
                return old;
            });
            afterCas.accept(replaced.get());
            return replaced.get();
        }

        @Override
        public boolean delete(String key) {
            return entries.remove(key) != null;
        }

        record Entry(String value, long expiresAt) {
        }
    }

    static final class MemoryAccessTokens implements AuthAccessTokens {

        final ConcurrentHashMap<String, LoginUser> active = new ConcurrentHashMap<>();
        final AtomicInteger counter = new AtomicInteger();
        final ThreadLocal<String> current = new ThreadLocal<>();
        volatile Consumer<String> afterIssue = token -> { };

        @Override
        public IssuedAccess issue(LoginUser user, String familyId, String fingerprint) {
            String token = "access-" + counter.incrementAndGet();
            active.put(token, user);
            current.set(token);
            afterIssue.accept(token);
            return new IssuedAccess(token, 1800);
        }

        @Override
        public String currentToken() {
            return current.get();
        }

        @Override
        public void revoke(String token) {
            active.remove(token);
        }
    }
}
