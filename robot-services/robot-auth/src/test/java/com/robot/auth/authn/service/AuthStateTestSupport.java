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
    private static final int ACCESS_TTL_SECONDS = 1800;

    private AuthStateTestSupport() {}

    static final class MemoryRedis extends RedisHelper {

        final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
        final AtomicLong now = new AtomicLong();
        volatile Consumer<String> beforeSet = key -> {};
        volatile Consumer<String> beforeDelete = key -> {};
        volatile Consumer<StateTransition> beforeStateEvent = transition -> {};
        volatile Consumer<Boolean> afterCas = committed -> {};
        final ConcurrentHashMap<String, String> events = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Long> due = new ConcurrentHashMap<>();

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
            entries.compute(
                    key,
                    (ignored, old) -> {
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
                    ? Optional.empty()
                    : Optional.of(entry.value);
        }

        @Override
        public boolean compareAndSetStringExact(
                String key, String expected, String replacement, Duration ttl) {
            AtomicBoolean replaced = new AtomicBoolean();
            entries.computeIfPresent(
                    key,
                    (ignored, old) -> {
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
            beforeDelete.accept(key);
            return entries.remove(key) != null;
        }

        @Override
        public synchronized boolean incrementWithinLimit(String key, int limit, Duration ttl) {
            var old = getString(key);
            int count = old.map(Integer::parseInt).orElse(0);
            if (count >= limit) {
                return false;
            }
            if (count == 0) {
                setStringExact(key, "1", ttl);
            } else {
                entries.computeIfPresent(
                        key,
                        (ignored, entry) -> new Entry(String.valueOf(count + 1), entry.expiresAt));
            }
            return true;
        }

        @Override
        public boolean stateAndEvent(StateTransition transition, EventAppend command) {
            beforeStateEvent.accept(transition);
            String key = transition.key(),
                    expected = transition.expected(),
                    replacement = transition.replacement();
            Duration ttl = transition.ttl();
            EventPayload event = command.event();
            boolean changed;
            synchronized (this) {
                String current = getString(key).orElse(null);
                changed = java.util.Objects.equals(expected, current);
                if (changed) {
                    if (events.size() >= command.queue().capacity()) {
                        throw new IllegalStateException("queue full");
                    }
                    if (replacement == null) {
                        entries.remove(key);
                    } else {
                        entries.put(key, new Entry(replacement, now.get() + ttl.toMillis()));
                    }
                    events.put(event.id(), event.payload());
                    due.put(event.id(), event.dueAtMillis());
                }
            }
            if (expected != null && replacement != null) {
                afterCas.accept(changed);
            }
            return changed;
        }

        @Override
        public synchronized void appendEvent(EventAppend command) {
            if (events.size() >= command.queue().capacity()) {
                throw new IllegalStateException("queue full");
            }
            events.put(command.event().id(), command.event().payload());
            due.put(command.event().id(), command.event().dueAtMillis());
        }

        @Override
        public java.util.List<String> dueEventIds(String key, long at, int batch) {
            return due.entrySet().stream()
                    .filter(e -> e.getValue() <= at)
                    .map(java.util.Map.Entry::getKey)
                    .limit(batch)
                    .toList();
        }

        @Override
        public Optional<String> eventPayload(String key, String id) {
            return Optional.ofNullable(events.get(id));
        }

        @Override
        public synchronized void acknowledgeEvent(
                String key, String dueKey, String id, String payload) {
            if (events.remove(id, payload)) {
                due.remove(id);
            }
        }

        @Override
        public void retryEvent(String key, String dueKey, String id, long at) {
            if (events.containsKey(id)) {
                due.put(id, at);
            }
        }

        record Entry(String value, long expiresAt) {}
    }

    static final class MemoryAccessTokens implements AuthAccessTokens {

        final ConcurrentHashMap<String, LoginUser> active = new ConcurrentHashMap<>();
        final AtomicInteger counter = new AtomicInteger();
        final ThreadLocal<String> current = new ThreadLocal<>();
        volatile Consumer<String> afterIssue = token -> {};

        @Override
        public IssuedAccess issue(LoginUser user, String familyId, String fingerprint) {
            String token = "access-" + counter.incrementAndGet();
            active.put(token, user);
            current.set(token);
            afterIssue.accept(token);
            return new IssuedAccess(token, ACCESS_TTL_SECONDS);
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
