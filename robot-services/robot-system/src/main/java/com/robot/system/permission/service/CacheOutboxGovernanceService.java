package com.robot.system.permission.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.core.util.Digests;
import com.robot.system.api.dto.cache.CacheOutboxReplayReason;
import com.robot.system.api.vo.cache.CacheOutboxEventView;
import com.robot.system.api.vo.cache.CacheOutboxReplayPreview;
import com.robot.system.api.vo.cache.CacheOutboxReplayResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 全局控制账本只允许独立平台管理员且具备当前公司永久超级管理员身份治理；重放不变更业务事实或授权 epoch。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CacheOutboxGovernanceService {
    private static final int MIN_RETENTION_DAYS = 30;
    private static final int MAX_RETENTION_DAYS = 3650;
    private static final int MAX_ARCHIVE_BATCH = 500;
    private static final int MAX_EVENT_QUERY_LIMIT = 100;

    private final PermissionStateStore store;
    private final PermissionGrantPolicy policy;
    private final CacheEventDispatcher dispatcher;
    private final CacheOutboxMetrics metrics;
    private final ObjectMapper objectMapper;
    private final GlobalDirectoryPolicy directoryPolicy;

    /**
     * 有界、可核对的已完成历史归档预览。
     *
     * @param retentionDays 完成记录保留天数
     * @param limit 本次查询/操作的数量上限
     * @return 当前事实派生的结果
     */
    public com.robot.system.api.vo.cache.CacheOutboxArchivePreview previewArchive(
            int retentionDays, int limit) {
        var operator = requireOperator();
        archiveBounds(retentionDays, limit);
        var events = store.archiveCandidates(retentionDays, limit);
        return new com.robot.system.api.vo.cache.CacheOutboxArchivePreview(
                events.stream().map(CacheOutboxGovernanceService::view).toList(),
                archiveHash(operator, retentionDays, limit, events));
    }

    /**
     * 锁内重查同计划，版本漂移整批回滚；归档及操作审计同事务。
     *
     * @param retentionDays 完成记录保留天数
     * @param limit 本次查询/操作的数量上限
     * @param expectedHash expectedHash协作输入
     * @return 当前事实派生的结果
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public int archive(int retentionDays, int limit, String expectedHash) {
        var operator = requireOperator();
        archiveBounds(retentionDays, limit);
        ServiceAssert.isTrue(
                expectedHash != null && expectedHash.matches("[a-f0-9]{64}"),
                ResultCode.PARAM_INVALID);
        var candidates = store.archiveCandidates(retentionDays, limit);
        var locked =
                candidates.stream()
                        .map(PermissionStateStore.CacheEvent::id)
                        .sorted()
                        .map(store::lock)
                        .toList();
        String actual = archiveHash(operator, retentionDays, limit, locked);
        ServiceAssert.isTrue(
                MessageDigest.isEqual(
                        actual.getBytes(StandardCharsets.US_ASCII),
                        expectedHash.getBytes(StandardCharsets.US_ASCII)),
                ResultCode.DATA_CONCURRENT_CONFLICT,
                "归档候选已变化，请重新预览");
        for (var event : locked) {
            ServiceAssert.isTrue(store.archive(event), ResultCode.DATA_CONCURRENT_CONFLICT);
            store.auditArchive(event, operator.userId(), operator.companyId());
        }
        return locked.size();
    }

    private static void archiveBounds(int retentionDays, int limit) {
        ServiceAssert.isTrue(
                retentionDays >= MIN_RETENTION_DAYS
                        && retentionDays <= MAX_RETENTION_DAYS
                        && limit >= 1
                        && limit <= MAX_ARCHIVE_BATCH,
                ResultCode.PARAM_INVALID);
    }

    private String archiveHash(
            PermissionGrantPolicy.Operator operator,
            int retentionDays,
            int limit,
            List<PermissionStateStore.CacheEvent> events) {
        try {
            return Digests.sha256Hex(
                    objectMapper.writeValueAsString(
                            List.of(
                                    operator.userId(),
                                    operator.companyId(),
                                    retentionDays,
                                    limit,
                                    events.stream()
                                            .sorted(
                                                    java.util.Comparator.comparing(
                                                            PermissionStateStore.CacheEvent::id))
                                            .toList())));
        } catch (JsonProcessingException error) {
            throw new ServiceException(ResultCode.FAILED, "归档预览失败");
        }
    }

    /**
     * 读取数据库积压事实，不接受请求公司头选择控制账本。
     *
     * @return 当前事实派生的结果
     */
    public PermissionStateStore.OutboxSnapshot summary() {
        requireOperator();
        return store.snapshot();
    }

    /**
     * 有界读取最近事件，输出不含 Redis key 或任意下游错误消息。
     *
     * @param limit 本次查询/操作的数量上限
     * @return 当前事实派生的结果
     */
    public List<CacheOutboxEventView> recent(int limit) {
        requireOperator();
        ServiceAssert.isTrue(
                limit >= 1 && limit <= MAX_EVENT_QUERY_LIMIT, ResultCode.PARAM_INVALID);
        return store.recent(limit).stream().map(CacheOutboxGovernanceService::view).toList();
    }

    /**
     * 查询单个事件的最近重放审计，操作人公司仅用于审计而非客户端授权事实。
     *
     * @param eventId 缓存通知投递ID
     * @return 当前事实派生的结果
     */
    public List<PermissionStateStore.ReplayAudit> replayHistory(String eventId) {
        requireOperator();
        validateId(eventId);
        requireEvent(store.find(eventId));
        return store.replayAudits(eventId);
    }

    /**
     * 生成绑定当前操作者和状态的确认摘要，不改变事件、不执行 Redis 操作。
     *
     * @param eventId 缓存通知投递ID
     * @param reason 已校验的操作原因
     * @return 当前事实派生的结果
     */
    public CacheOutboxReplayPreview preview(String eventId, CacheOutboxReplayReason reason) {
        PermissionGrantPolicy.Operator operator = requireOperator();
        validateId(eventId);
        ServiceAssert.notNull(reason, ResultCode.PARAM_INVALID);
        PermissionStateStore.CacheEvent event = requireEvent(store.find(eventId));
        ServiceAssert.isTrue(
                dispatcher.validEvent(event), ResultCode.PARAM_INVALID, "通知类型或目标不符合缓存契约");
        return new CacheOutboxReplayPreview(view(event), reason, planHash(operator, event, reason));
    }

    /**
     * 在行锁内重查确认摘要，同事务重新排队并追加审计；漂移、无权限或审计失败均零写入。 重放只安排后台通知，调用时不连接 Redis。
     *
     * @param eventId 缓存通知投递ID
     * @param reason 已校验的操作原因
     * @param expectedPlanHash expectedPlanHash协作输入
     * @return 当前事实派生的结果
     * @throws ServiceException 当前身份/状态不满足用例约束或受控操作失败
     */
    @Transactional(rollbackFor = Exception.class)
    public CacheOutboxReplayResult replay(
            String eventId, CacheOutboxReplayReason reason, String expectedPlanHash) {
        PermissionGrantPolicy.Operator operator = requireOperator();
        validateId(eventId);
        ServiceAssert.notNull(reason, ResultCode.PARAM_INVALID);
        ServiceAssert.isTrue(
                expectedPlanHash != null && expectedPlanHash.matches("[a-f0-9]{64}"),
                ResultCode.PARAM_INVALID);
        try {
            return replayLocked(operator, eventId, reason, expectedPlanHash);
        } catch (DataAccessException error) {
            String failureId = CacheOutboxFailure.newId();
            log.error(
                    "缓存通知重放失败 category={} failureId={}",
                    CacheOutboxFailure.Category.DATABASE_FAILURE,
                    failureId);
            throw new ServiceException(ResultCode.FAILED, "缓存通知重放失败，关联ID=" + failureId);
        }
    }

    private CacheOutboxReplayResult replayLocked(
            PermissionGrantPolicy.Operator operator,
            String eventId,
            CacheOutboxReplayReason reason,
            String expectedPlanHash) {
        PermissionStateStore.CacheEvent event = requireEvent(store.lock(eventId));
        String actual = planHash(operator, event, reason);
        ServiceAssert.isTrue(
                MessageDigest.isEqual(
                        actual.getBytes(StandardCharsets.US_ASCII),
                        expectedPlanHash.getBytes(StandardCharsets.US_ASCII)),
                ResultCode.DATA_CONCURRENT_CONFLICT,
                "通知或操作者状态已变化，请重新预览");
        ServiceAssert.isTrue(
                dispatcher.validEvent(event), ResultCode.PARAM_INVALID, "通知类型或目标不符合缓存契约");
        ServiceAssert.isTrue(store.reschedule(event), ResultCode.DATA_CONCURRENT_CONFLICT);
        String auditId = store.auditReplay(event, operator.userId(), operator.companyId(), reason);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    /** 只有排队与审计一起提交成功才计入重放次数，回滚不增加运营指标。 */
                    @Override
                    public void afterCommit() {
                        metrics.count(CacheOutboxMetrics.Outcome.REPLAYED);
                    }
                });
        return new CacheOutboxReplayResult(auditId, eventId, event.revision() + 1);
    }

    private PermissionGrantPolicy.Operator requireOperator() {
        directoryPolicy.requirePlatformAdministrator();
        PermissionGrantPolicy.Operator operator = policy.requireOperator();
        ServiceAssert.isTrue(
                operator.roles().superAdmin(), ResultCode.FORBIDDEN, "缓存控制账本治理仅限当前永久超级管理员");
        return operator;
    }

    private String planHash(
            PermissionGrantPolicy.Operator operator,
            PermissionStateStore.CacheEvent event,
            CacheOutboxReplayReason reason) {
        ReplayPlan plan =
                new ReplayPlan(
                        operator.userId(),
                        operator.companyId(),
                        reason,
                        event.id(),
                        event.kind(),
                        event.cacheKey(),
                        event.version(),
                        event.revision(),
                        event.attempts(),
                        event.createdAt(),
                        event.nextAttemptAt(),
                        event.completedAt(),
                        event.errorCategory(),
                        event.failureId());
        try {
            return Digests.sha256Hex(objectMapper.writeValueAsString(plan));
        } catch (JsonProcessingException error) {
            throw new ServiceException(ResultCode.FAILED, "缓存通知预览生成失败");
        }
    }

    private static PermissionStateStore.CacheEvent requireEvent(
            PermissionStateStore.CacheEvent event) {
        return ServiceAssert.notNull(event, ResultCode.NOT_FOUND);
    }

    private static void validateId(String eventId) {
        ServiceAssert.isTrue(
                eventId != null && eventId.matches("[A-Za-z0-9_-]{1,64}"),
                ResultCode.PARAM_INVALID);
    }

    private static CacheOutboxEventView view(PermissionStateStore.CacheEvent event) {
        String category = event.errorCategory() == null ? null : "LEGACY_FAILURE";
        for (CacheOutboxFailure.Category allowed : CacheOutboxFailure.Category.values()) {
            if (allowed.name().equals(event.errorCategory())) {
                category = allowed.name();
            }
        }
        return new CacheOutboxEventView(
                event.id(),
                event.kind(),
                event.version(),
                event.archivedAt() != null
                        ? "ARCHIVED"
                        : event.quarantinedAt() != null
                                ? "QUARANTINED"
                                : event.completedAt() == null ? "PENDING" : "COMPLETED",
                event.revision(),
                event.attempts(),
                event.createdAt(),
                event.nextAttemptAt(),
                event.completedAt(),
                category,
                event.failureId());
    }

    private record ReplayPlan(
            String actorUserId,
            String actorCompanyId,
            CacheOutboxReplayReason reason,
            String eventId,
            String kind,
            String key,
            long version,
            long revision,
            int attempts,
            LocalDateTime createdAt,
            LocalDateTime nextAttemptAt,
            LocalDateTime completedAt,
            String errorCategory,
            String failureId) {
        /** 诊断文本仅展示事件与围栏版本，避免输出操作者身份或 Redis 目标。 */
        @Override
        public String toString() {
            return "ReplayPlan[eventId=" + eventId + ", revision=" + revision + "]";
        }
    }
}
