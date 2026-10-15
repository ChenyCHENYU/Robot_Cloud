package com.robot.auth.authn.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.common.redis.core.RedisHelper;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 持久有界审计队列，无TTL；成功事件与族状态原子提交，ACK只在System幂等接收成功之后。 */
@Slf4j
@Component
public final class AuthAuditQueue {
    static final int CAPACITY = 10_000;
    private static final int MAX_IP_LENGTH = 45;
    private static final int MAX_AGENT_LENGTH = 255;
    private static final int MAX_EVENT_LENGTH = 2048;
    private static final int DISPATCH_BATCH = 20;
    private static final long RETRY_DELAY_MILLIS = 5000L;
    private final RedisHelper redis;
    private final RemoteSystemService remote;
    private final ObjectMapper mapper;
    private final org.springframework.beans.factory.ObjectProvider<
                    io.micrometer.core.instrument.MeterRegistry>
            meters;

    /**
     * Construct the queue without an optional diagnostics registry.
     *
     * @param redis shared durable queue storage
     * @param remote idempotent audit receiver
     * @param mapper configured queue payload codec
     */
    public AuthAuditQueue(RedisHelper redis, RemoteSystemService remote, ObjectMapper mapper) {
        this(redis, remote, mapper, null);
    }

    /**
     * Construct the durable queue and optional diagnostics sink.
     *
     * @param redis shared durable queue storage
     * @param remote idempotent audit receiver
     * @param mapper configured queue payload codec
     * @param meters optional diagnostics sink
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AuthAuditQueue(
            RedisHelper redis,
            RemoteSystemService remote,
            ObjectMapper mapper,
            org.springframework.beans.factory.ObjectProvider<
                            io.micrometer.core.instrument.MeterRegistry>
                    meters) {
        this.redis = redis;
        this.remote = remote;
        this.mapper = mapper;
        this.meters = meters;
    }

    /**
     * Build a bounded event with a new delivery identity and sanitized request metadata.
     *
     * @param type fixed authentication operation
     * @param userId trusted account ID, or null for an unknown account
     * @param username account name or nonreversible failure pseudonym
     * @param companyId trusted active company, or null before activation
     * @param status success=1, failure=0
     * @return encoded durable event payload
     * @throws IllegalStateException if encoding or payload capacity validation fails
     */
    public String event(String type, String userId, String username, String companyId, int status) {
        String ip = null;
        String userAgent = null;
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            ip = attributes.getRequest().getRemoteAddr();
            if (ip != null && ip.length() > MAX_IP_LENGTH) {
                ip = null;
            }
            String rawAgent = attributes.getRequest().getHeader("User-Agent");
            if (rawAgent != null) {
                String cleaned = rawAgent.replaceAll("[\\r\\n\\p{Cntrl}]", "");
                userAgent = cleaned.substring(0, Math.min(MAX_AGENT_LENGTH, cleaned.length()));
            }
        }
        try {
            String payload =
                    mapper.writeValueAsString(
                            RemoteLoginLogDTO.builder()
                                    .eventId(UUID.randomUUID().toString())
                                    .loginType(type)
                                    .userId(userId)
                                    .username(username)
                                    .companyId(companyId)
                                    .status(status)
                                    .ip(ip)
                                    .userAgent(userAgent)
                                    .build());
            if (payload.length() > MAX_EVENT_LENGTH) {
                throw new IllegalStateException("审计事件超出容量预算");
            }
            return payload;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("审计事件无法编码");
        }
    }

    /**
     * Atomically compare/create family state and append its event before reporting success.
     *
     * @param key family authority key
     * @param expected exact previous state, or null for creation
     * @param state replacement state, or null for deletion
     * @param ttl family lifetime; pending events deliberately have no TTL
     * @param payload bounded event payload
     * @return whether the comparison committed
     */
    public boolean commit(String key, String expected, String state, Duration ttl, String payload) {
        try {
            return redis.stateAndEvent(
                    new RedisHelper.StateTransition(key, expected, state, ttl),
                    appendCommand(payload));
        } catch (RuntimeException error) {
            count("robot.auth.audit.commit.unconfirmed");
            throw error;
        }
    }

    /**
     * Append a credential failure; capacity/storage failures propagate to the caller.
     *
     * @param payload bounded failure event
     */
    public void append(String payload) {
        try {
            redis.appendEvent(appendCommand(payload));
        } catch (RuntimeException error) {
            count("robot.auth.audit.append.rejected");
            throw error;
        }
    }

    private RedisHelper.EventAppend appendCommand(String payload) {
        return new RedisHelper.EventAppend(
                new RedisHelper.EventQueue(AuthKeys.auditHash(), AuthKeys.auditDue(), CAPACITY),
                new RedisHelper.EventPayload(
                        decode(payload).getEventId(), payload, System.currentTimeMillis()));
    }

    /** Deliver a bounded due batch and ACK only a confirmed idempotent System response. */
    @Scheduled(fixedDelayString = "${robot.auth.audit-dispatch-ms:1000}")
    public void dispatch() {
        try {
            for (String id :
                    redis.dueEventIds(
                            AuthKeys.auditDue(), System.currentTimeMillis(), DISPATCH_BATCH)) {
                var pending = redis.eventPayload(AuthKeys.auditHash(), id);
                if (pending.isEmpty()) {
                    continue;
                }
                String payload = pending.get();
                try {
                    var result = remote.recordLogin(decode(payload));
                    if (result == null || !result.successValue()) {
                        throw new IllegalStateException("审计接收未确认");
                    }
                    redis.acknowledgeEvent(AuthKeys.auditHash(), AuthKeys.auditDue(), id, payload);
                } catch (RuntimeException error) {
                    count("robot.auth.audit.delivery.failures");
                    redis.retryEvent(
                            AuthKeys.auditHash(),
                            AuthKeys.auditDue(),
                            id,
                            System.currentTimeMillis() + RETRY_DELAY_MILLIS);
                    log.warn("登录审计暂未确认，将重试");
                }
            }
        } catch (RuntimeException error) {
            count("robot.auth.audit.round.failures");
            log.warn("登录审计投递轮次失败，将重试");
        }
    }

    /** 指标失败不能改变已提交/拒绝的认证结果；无账号、事件、key或消息标签。 */
    private void count(String name) {
        try {
            if (meters != null && meters.getIfAvailable() != null) {
                meters.getIfAvailable().counter(name).increment();
            }
        } catch (RuntimeException metricFailure) {
            log.warn("登录审计指标暂不可更新");
        }
    }

    private RemoteLoginLogDTO decode(String payload) {
        try {
            return mapper.readValue(payload, RemoteLoginLogDTO.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("审计事件格式无效");
        }
    }
}
