package com.robot.auth.authn.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.common.core.domain.Result;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.redis.key.RedisKeys;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 正式隔离Redis入口；仅显式loopback disposable实例，无FLUSH/KEYS，不访问业务System。 */
public final class AuthRedisOutboxProbe {
    private static final int MIN_DISPOSABLE_PORT = 1024;
    private static final int MAX_PORT = 65535;
    private static final int RESTART_QUEUE_CAPACITY = 10;
    private static final int PROBE_BATCH_BOUND = 20;
    private static final int CONCURRENT_WORKERS = 8;
    private static final int CAS_COMPETITORS = 16;

    private AuthRedisOutboxProbe() {}

    /**
     * Run one explicitly isolated probe phase against the owned disposable Redis.
     *
     * @param args full, prepare or verify phase
     * @throws Exception if a real storage/serialization invariant cannot be verified
     * @throws IllegalArgumentException if phase/environment do not select an owned disposable
     *     instance
     */
    public static void main(String[] args) throws Exception {
        ProbeEnvironment environment = environment();
        RedisKeys.configureEnvironment(environment.namespace());
        var factory = new LettuceConnectionFactory("127.0.0.1", environment.port());
        factory.afterPropertiesSet();
        factory.start();
        try {
            var template = new StringRedisTemplate(factory);
            var redis = new RedisHelper(template, null);
            var mapper = new ObjectMapper();
            String phase = args.length == 0 ? "full" : args[0];
            switch (phase) {
                case "prepare" -> prepareRestart(redis, template, mapper);
                case "verify" -> verifyRestart(redis, mapper);
                case "full" -> full(redis, template, mapper);
                default -> throw new IllegalArgumentException("phase必须full/prepare/verify");
            }
            System.out.println(
                    "{\"result\":\"passed\",\"phase\":\""
                            + phase
                            + "\",\"environment\":\""
                            + environment.namespace()
                            + "\"}");
        } finally {
            factory.destroy();
        }
    }

    private static ProbeEnvironment environment() {
        String host = System.getenv("ROBOT_VERIFY_REDIS_HOST");
        String namespace = System.getenv("ROBOT_VERIFY_REDIS_NAMESPACE");
        if (!"127.0.0.1".equals(host)
                || !"1".equals(System.getenv("ROBOT_VERIFY_REDIS_DISPOSABLE"))
                || namespace == null
                || !namespace.matches("verify-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("必须显式选择loopback disposable Redis和唯一verify环境");
        }
        int port = Integer.parseInt(System.getenv("ROBOT_VERIFY_REDIS_PORT"));
        if (port < MIN_DISPOSABLE_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("隔离端口无效");
        }
        return new ProbeEnvironment(namespace, port);
    }

    private static void prepareRestart(
            RedisHelper redis, StringRedisTemplate template, ObjectMapper mapper) throws Exception {
        check(
                !Boolean.TRUE.equals(template.hasKey(AuthKeys.auditHash()))
                        && !Boolean.TRUE.equals(template.hasKey(AuthKeys.auditDue())),
                "fresh_restart_namespace");
        check(
                redis.setStringIfAbsentExact(
                        AuthKeys.sessionBase() + ":probe-ownership",
                        "restart",
                        Duration.ofHours(1)),
                "exclusive_restart_namespace");
        String payload =
                new AuthAuditQueue(
                                redis,
                                receiver(new AtomicInteger(), ConcurrentHashMap.newKeySet()),
                                mapper)
                        .event("login", "restart-user", "restart-user", "restart-company", 1);
        check(
                redis.stateAndEvent(
                        new RedisHelper.StateTransition(
                                AuthKeys.family("restart-case"),
                                null,
                                "restart-committed",
                                Duration.ofHours(1)),
                        appendCommand(
                                AuthKeys.auditHash(),
                                AuthKeys.auditDue(),
                                mapper.readValue(payload, RemoteLoginLogDTO.class).getEventId(),
                                payload,
                                RESTART_QUEUE_CAPACITY)),
                "restart_prepare");
        check(
                redis.ttlSeconds(AuthKeys.auditHash()) == -1
                        && redis.ttlSeconds(AuthKeys.auditDue()) == -1,
                "pending_without_ttl");
    }

    private static void verifyRestart(RedisHelper redis, ObjectMapper mapper) {
        check(
                redis.getString(AuthKeys.sessionBase() + ":probe-ownership")
                        .orElseThrow()
                        .equals("restart"),
                "owned_restart_namespace");
        check(
                redis.getString(AuthKeys.family("restart-case"))
                        .orElseThrow()
                        .equals("restart-committed"),
                "aof_state_survived");
        check(
                redis.dueEventIds(
                                        AuthKeys.auditDue(),
                                        System.currentTimeMillis(),
                                        PROBE_BATCH_BOUND)
                                .size()
                        == 1,
                "aof_pending_survived");
        var calls = new AtomicInteger();
        new AuthAuditQueue(redis, receiver(calls, ConcurrentHashMap.newKeySet()), mapper)
                .dispatch();
        check(
                calls.get() == 1
                        && redis.dueEventIds(AuthKeys.auditDue(), Long.MAX_VALUE, PROBE_BATCH_BOUND)
                                .isEmpty(),
                "restart_delivery_ack");
        redis.delete(AuthKeys.family("restart-case"));
        redis.delete(AuthKeys.sessionBase() + ":probe-ownership");
    }

    private static void full(RedisHelper redis, StringRedisTemplate template, ObjectMapper mapper)
            throws Exception {
        String key = AuthKeys.family("probe-family");
        String hash = AuthKeys.auditHash(), due = AuthKeys.auditDue();
        String ownership = AuthKeys.sessionBase() + ":probe-ownership";
        check(
                !Boolean.TRUE.equals(template.hasKey(hash))
                        && !Boolean.TRUE.equals(template.hasKey(due))
                        && redis.getString(key).isEmpty(),
                "fresh_namespace");
        check(
                redis.setStringIfAbsentExact(ownership, "full", Duration.ofHours(1)),
                "exclusive_probe_namespace");
        try {
            capacityBeforeMutation(redis, key, hash, due);
            concurrentSingleWinner(redis, template, key, hash, due);
            typeBeforeMutation(redis, hash, due);
            uncertainDeliveryRetried(redis, template, mapper, hash, due);
            managerAtomicLifecycle(redis, mapper, due);
            AuthResolvedAccountRedisProbe.verify(redis, mapper);
        } finally {
            for (String known :
                    List.of(
                            key,
                            hash,
                            due,
                            AuthKeys.family("capacity-loser"),
                            AuthKeys.family("type-loser"),
                            ownership)) {
                redis.delete(known);
            }
        }
    }

    private static void capacityBeforeMutation(
            RedisHelper redis, String key, String hash, String due) {
        check(
                redis.stateAndEvent(
                        new RedisHelper.StateTransition(
                                key, null, "initial", Duration.ofMinutes(2)),
                        appendCommand(hash, due, "initial", "payload", 1)),
                "atomic_create");
        check(redis.ttlSeconds(hash) == -1 && redis.ttlSeconds(due) == -1, "pending_without_ttl");
        try {
            redis.stateAndEvent(
                    new RedisHelper.StateTransition(
                            AuthKeys.family("capacity-loser"),
                            null,
                            "forbidden",
                            Duration.ofMinutes(2)),
                    appendCommand(hash, due, "full", "payload", 1));
            throw new AssertionError("capacity should refuse");
        } catch (IllegalStateException expected) {
            check(
                    redis.getString(AuthKeys.family("capacity-loser")).isEmpty(),
                    "capacity_precedes_state");
        }
        redis.acknowledgeEvent(hash, due, "initial", "payload");
    }

    private static void concurrentSingleWinner(
            RedisHelper redis, StringRedisTemplate template, String key, String hash, String due)
            throws Exception {
        var pool = Executors.newFixedThreadPool(CONCURRENT_WORKERS);
        try {
            List<java.util.concurrent.Future<Boolean>> competitors = new ArrayList<>();
            for (int i = 0; i < CAS_COMPETITORS; i++) {
                String candidate = "candidate-" + i;
                competitors.add(
                        pool.submit(
                                () ->
                                        redis.stateAndEvent(
                                                new RedisHelper.StateTransition(
                                                        key,
                                                        "initial",
                                                        candidate,
                                                        Duration.ofMinutes(2)),
                                                appendCommand(
                                                        hash,
                                                        due,
                                                        candidate,
                                                        candidate,
                                                        PROBE_BATCH_BOUND))));
            }
            int winners = 0;
            for (var candidate : competitors) {
                if (candidate.get()) {
                    winners++;
                }
            }
            check(
                    winners == 1 && template.opsForHash().size(hash) == 1,
                    "concurrent_cas_single_event");
        } finally {
            pool.shutdownNow();
        }
        for (String id : redis.dueEventIds(due, Long.MAX_VALUE, PROBE_BATCH_BOUND)) {
            redis.acknowledgeEvent(hash, due, id, redis.eventPayload(hash, id).orElseThrow());
        }
    }

    private static void typeBeforeMutation(RedisHelper redis, String hash, String due) {
        redis.delete(due);
        redis.setStringExact(due, "wrong-type", Duration.ofMinutes(1));
        try {
            redis.stateAndEvent(
                    new RedisHelper.StateTransition(
                            AuthKeys.family("type-loser"),
                            null,
                            "forbidden",
                            Duration.ofMinutes(2)),
                    appendCommand(hash, due, "type", "payload", PROBE_BATCH_BOUND));
            throw new AssertionError("wrong type should refuse");
        } catch (org.springframework.dao.DataAccessException expected) {
            check(redis.getString(AuthKeys.family("type-loser")).isEmpty(), "type_precedes_state");
        }
        redis.delete(due);
    }

    private static void uncertainDeliveryRetried(
            RedisHelper redis,
            StringRedisTemplate template,
            ObjectMapper mapper,
            String hash,
            String due) {
        var calls = new AtomicInteger();
        java.util.Set<String> accepted = ConcurrentHashMap.newKeySet();
        RemoteSystemService remote = receiver(calls, accepted);
        AtomicBoolean lostAck = new AtomicBoolean(true);
        var uncertainRedis =
                new RedisHelper(template, null) {
                    @Override
                    public void acknowledgeEvent(String h, String d, String id, String payload) {
                        if (lostAck.getAndSet(false)) {
                            throw new IllegalStateException("simulated acknowledgement loss");
                        }
                        super.acknowledgeEvent(h, d, id, payload);
                    }
                };
        var queue = new AuthAuditQueue(uncertainRedis, remote, mapper);
        queue.append(queue.event("login", "probe-user", "probe-user", "probe-company", 1));
        queue.dispatch();
        check(
                accepted.size() == 1
                        && redis.dueEventIds(due, Long.MAX_VALUE, PROBE_BATCH_BOUND).size() == 1,
                "unconfirmed_delivery_retained");
        for (String id : redis.dueEventIds(due, Long.MAX_VALUE, PROBE_BATCH_BOUND)) {
            redis.retryEvent(hash, due, id, 0);
        }
        new AuthAuditQueue(redis, remote, mapper).dispatch();
        check(
                calls.get() == 2
                        && accepted.size() == 1
                        && redis.dueEventIds(due, Long.MAX_VALUE, PROBE_BATCH_BOUND).isEmpty(),
                "same_event_retry_ack");
    }

    private static void managerAtomicLifecycle(RedisHelper redis, ObjectMapper mapper, String due) {
        RemoteSystemService remote = receiver(new AtomicInteger(), ConcurrentHashMap.newKeySet());
        var tokens = new AuthStateTestSupport.MemoryAccessTokens();
        var manager =
                new AuthSessionManager(
                        redis,
                        tokens,
                        mapper,
                        new AuthAuditQueue(redis, remote, mapper),
                        new AuthCleanupWorker(true));
        var user =
                RemoteUserDTO.builder()
                        .id("user")
                        .username("user")
                        .passwordHash("synthetic-hash")
                        .securityVersion(0L)
                        .build();
        var context =
                RemoteUserContextDTO.builder()
                        .contextId("ctx")
                        .companyId("company")
                        .securityVersion(0L)
                        .roles(List.of())
                        .build();
        var session = manager.issue("login", user, context, List.of(context));
        check(
                manager.requireRefresh(session.getRefreshToken()) != null
                        && redis.dueEventIds(due, Long.MAX_VALUE, PROBE_BATCH_BOUND).size() == 1,
                "real_manager_state_and_event");
        tokens.current.set(session.getToken());
        manager.logoutCurrent();
        new AuthAuditQueue(redis, remote, mapper).dispatch();
        check(
                redis.dueEventIds(due, Long.MAX_VALUE, PROBE_BATCH_BOUND).isEmpty(),
                "real_manager_logout_event");
    }

    private static RemoteSystemService receiver(
            AtomicInteger calls, java.util.Set<String> accepted) {
        return (RemoteSystemService)
                Proxy.newProxyInstance(
                        RemoteSystemService.class.getClassLoader(),
                        new Class<?>[] {RemoteSystemService.class},
                        (proxy, method, arguments) -> {
                            if (!method.getName().equals("recordLogin")) {
                                throw new UnsupportedOperationException("probe只允许审计接收");
                            }
                            calls.incrementAndGet();
                            accepted.add(((RemoteLoginLogDTO) arguments[0]).getEventId());
                            return Result.success();
                        });
    }

    private static RedisHelper.EventAppend appendCommand(
            String hash, String due, String id, String payload, int capacity) {
        return new RedisHelper.EventAppend(
                new RedisHelper.EventQueue(hash, due, capacity),
                new RedisHelper.EventPayload(id, payload, 0));
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
        System.out.println("{\"check\":\"" + name + "\",\"result\":\"passed\"}");
    }

    private record ProbeEnvironment(String namespace, int port) {}
}
