package com.robot.system.permission.service;

import com.robot.system.api.dto.cache.CacheOutboxReplayReason;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.core.util.Digests;
import com.robot.system.api.vo.cache.CacheOutboxEventView;
import com.robot.system.api.vo.cache.CacheOutboxReplayPreview;
import com.robot.system.api.vo.cache.CacheOutboxReplayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

/** 全局控制账本只允许当前永久超级管理员治理；重放不变更业务事实或授权 epoch。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CacheOutboxGovernanceService {
    private final PermissionStateStore store;
    private final PermissionGrantPolicy policy;
    private final CacheEventDispatcher dispatcher;
    private final CacheOutboxMetrics metrics;
    private final ObjectMapper objectMapper;

    /** 读取数据库积压事实，不接受请求公司头选择控制账本。 */
    public PermissionStateStore.OutboxSnapshot summary() {
        requireOperator();
        return store.snapshot();
    }

    /** 有界读取最近事件，输出不含 Redis key 或任意下游错误消息。 */
    public List<CacheOutboxEventView> recent(int limit) {
        requireOperator();
        ServiceAssert.isTrue(limit >= 1 && limit <= 100, ResultCode.PARAM_INVALID);
        return store.recent(limit).stream().map(CacheOutboxGovernanceService::view).toList();
    }

    /** 查询单个事件的最近重放审计，操作人公司仅用于审计而非客户端授权事实。 */
    public List<PermissionStateStore.ReplayAudit> replayHistory(String eventId) {
        requireOperator();
        validateId(eventId);
        requireEvent(store.find(eventId));
        return store.replayAudits(eventId);
    }

    /** 生成绑定当前操作者和状态的确认摘要，不改变事件、不执行 Redis 操作。 */
    public CacheOutboxReplayPreview preview(String eventId, CacheOutboxReplayReason reason) {
        PermissionGrantPolicy.Operator operator = requireOperator();
        validateId(eventId);
        ServiceAssert.notNull(reason, ResultCode.PARAM_INVALID);
        PermissionStateStore.CacheEvent event = requireEvent(store.find(eventId));
        ServiceAssert.isTrue(dispatcher.validEvent(event), ResultCode.PARAM_INVALID, "通知类型或目标不符合缓存契约");
        return new CacheOutboxReplayPreview(view(event), reason, planHash(operator, event, reason));
    }

    /**
     * 在行锁内重查确认摘要，同事务重新排队并追加审计；漂移、无权限或审计失败均零写入。
     * 重放只安排后台通知，调用时不连接 Redis。
     */
    @Transactional(rollbackFor = Exception.class)
    public CacheOutboxReplayResult replay(String eventId, CacheOutboxReplayReason reason, String expectedPlanHash) {
        PermissionGrantPolicy.Operator operator = requireOperator();
        validateId(eventId);
        ServiceAssert.notNull(reason, ResultCode.PARAM_INVALID);
        ServiceAssert.isTrue(expectedPlanHash != null && expectedPlanHash.matches("[a-f0-9]{64}"), ResultCode.PARAM_INVALID);
        try {
            return replayLocked(operator, eventId, reason, expectedPlanHash);
        } catch (DataAccessException error) {
            String failureId = CacheOutboxFailure.newId();
            log.error("缓存通知重放失败 category={} failureId={}", CacheOutboxFailure.Category.DATABASE_FAILURE, failureId);
            throw new ServiceException(ResultCode.FAILED, "缓存通知重放失败，关联ID=" + failureId);
        }
    }

    private CacheOutboxReplayResult replayLocked(PermissionGrantPolicy.Operator operator, String eventId,
                                                CacheOutboxReplayReason reason, String expectedPlanHash) {
        PermissionStateStore.CacheEvent event = requireEvent(store.lock(eventId));
        String actual = planHash(operator, event, reason);
        ServiceAssert.isTrue(MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                        expectedPlanHash.getBytes(StandardCharsets.US_ASCII)), ResultCode.DATA_CONCURRENT_CONFLICT,
                "通知或操作者状态已变化，请重新预览");
        ServiceAssert.isTrue(dispatcher.validEvent(event), ResultCode.PARAM_INVALID, "通知类型或目标不符合缓存契约");
        ServiceAssert.isTrue(store.reschedule(event), ResultCode.DATA_CONCURRENT_CONFLICT);
        String auditId = store.auditReplay(event, operator.userId(), operator.companyId(), reason);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 只有排队与审计一起提交成功才计入重放次数，回滚不增加运营指标。 */
            @Override
            public void afterCommit() {
                metrics.count(CacheOutboxMetrics.Outcome.REPLAYED);
            }
        });
        return new CacheOutboxReplayResult(auditId, eventId, event.revision() + 1);
    }

    private PermissionGrantPolicy.Operator requireOperator() {
        PermissionGrantPolicy.Operator operator = policy.requireOperator();
        ServiceAssert.isTrue(operator.roles().superAdmin(), ResultCode.FORBIDDEN,
                "缓存控制账本治理仅限当前永久超级管理员");
        return operator;
    }

    private String planHash(PermissionGrantPolicy.Operator operator, PermissionStateStore.CacheEvent event,
                            CacheOutboxReplayReason reason) {
        ReplayPlan plan = new ReplayPlan(operator.userId(), operator.companyId(), reason, event.id(), event.kind(),
                event.cacheKey(), event.version(), event.revision(), event.attempts(), event.createdAt(),
                event.nextAttemptAt(), event.completedAt(), event.errorCategory(), event.failureId());
        try {
            return Digests.sha256Hex(objectMapper.writeValueAsString(plan));
        } catch (JsonProcessingException error) {
            throw new ServiceException(ResultCode.FAILED, "缓存通知预览生成失败");
        }
    }

    private static PermissionStateStore.CacheEvent requireEvent(PermissionStateStore.CacheEvent event) {
        return ServiceAssert.notNull(event, ResultCode.NOT_FOUND);
    }

    private static void validateId(String eventId) {
        ServiceAssert.isTrue(eventId != null && eventId.matches("[A-Za-z0-9_-]{1,64}"), ResultCode.PARAM_INVALID);
    }

    private static CacheOutboxEventView view(PermissionStateStore.CacheEvent event) {
        String category = event.errorCategory() == null ? null : "LEGACY_FAILURE";
        for (CacheOutboxFailure.Category allowed : CacheOutboxFailure.Category.values()) {
            if (allowed.name().equals(event.errorCategory())) {
                category = allowed.name();
            }
        }
        return new CacheOutboxEventView(event.id(), event.kind(), event.version(),
                event.completedAt() == null ? "PENDING" : "COMPLETED", event.revision(), event.attempts(),
                event.createdAt(), event.nextAttemptAt(), event.completedAt(), category, event.failureId());
    }

    private record ReplayPlan(String actorUserId, String actorCompanyId, CacheOutboxReplayReason reason,
                              String eventId, String kind, String key, long version, long revision,
                              int attempts, LocalDateTime createdAt, LocalDateTime nextAttemptAt,
                              LocalDateTime completedAt, String errorCategory, String failureId) {
        /** 诊断文本仅展示事件与围栏版本，避免输出操作者身份或 Redis 目标。 */
        @Override
        public String toString() {
            return "ReplayPlan[eventId=" + eventId + ", revision=" + revision + "]";
        }
    }
}
