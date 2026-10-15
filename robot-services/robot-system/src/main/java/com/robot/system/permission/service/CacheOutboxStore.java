package com.robot.system.permission.service;

import com.robot.system.api.dto.cache.CacheOutboxReplayReason;
import com.robot.system.permission.service.PermissionStateStore.CacheEvent;
import com.robot.system.permission.service.PermissionStateStore.OutboxSnapshot;
import com.robot.system.permission.service.PermissionStateStore.ReplayAudit;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Outbox行状态、围栏CAS、逻辑留存与投递审计的持久化，和权威epoch职责分开。 */
final class CacheOutboxStore {
    private static final String EVENT_COLUMNS =
            "id, event_kind, cache_key, cache_version, attempts, delivery_revision, created_at,"
                    + " next_attempt_at, completed_at, last_error, last_failure_id, quarantined_at,"
                    + " archived_at";
    private static final RowMapper<CacheEvent> EVENT_MAPPER =
            (rs, row) ->
                    new CacheEvent(
                            rs.getString("id"),
                            rs.getString("event_kind"),
                            rs.getString("cache_key"),
                            rs.getLong("cache_version"),
                            rs.getInt("attempts"),
                            rs.getLong("delivery_revision"),
                            rs.getObject("created_at", LocalDateTime.class),
                            rs.getObject("next_attempt_at", LocalDateTime.class),
                            rs.getObject("completed_at", LocalDateTime.class),
                            rs.getString("last_error"),
                            rs.getString("last_failure_id"),
                            rs.getObject("quarantined_at", LocalDateTime.class),
                            rs.getObject("archived_at", LocalDateTime.class));
    private final JdbcTemplate jdbc;

    CacheOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记与业务事务一起提交的通知；事件内容在重放时保持不变。
     *
     * @param kind 通知类型
     * @param cacheKey 目标缓存键
     * @param version 权威缓存版本
     * @return 当前事实派生的结果
     */
    String enqueue(String kind, String cacheKey, long version) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO sys_cache_outbox (id, event_kind, cache_key, cache_version,"
                        + " created_at, next_attempt_at, attempts) VALUES (?, ?, ?, ?,"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                id,
                kind,
                cacheKey,
                version);
        return id;
    }

    /**
     * 有界读取到期事件；并发投递允许重复，结果回写由单调 revision 围栏保护。
     *
     * @return 当前事实派生的结果
     */
    List<CacheEvent> pending() {
        return jdbc.query(
                "SELECT "
                        + EVENT_COLUMNS
                        + " FROM sys_cache_outbox WHERE completed_at IS NULL AND quarantined_at IS"
                        + " NULL AND next_attempt_at <= CURRENT_TIMESTAMP ORDER BY created_at, id"
                        + " LIMIT 100",
                EVENT_MAPPER);
    }

    /**
     * 回源重查到期状态，避免另一投递者已安排退避后仍使用旧扫描结果。
     *
     * @param id 记录ID
     * @return 当前事实派生的结果
     */
    List<CacheEvent> findPending(String id) {
        return jdbc.query(
                "SELECT "
                        + EVENT_COLUMNS
                        + " FROM sys_cache_outbox WHERE id = ? AND completed_at IS NULL AND"
                        + " quarantined_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP",
                EVENT_MAPPER,
                id);
    }

    /**
     * 完成确认只接受当前快照，过时投递者不得覆盖人工重放或其他投递结果。
     *
     * @param event 通知事实快照
     * @return 是否成功更新当前围栏事实
     */
    boolean complete(CacheEvent event) {
        return jdbc.update(
                        "UPDATE sys_cache_outbox SET completed_at = CURRENT_TIMESTAMP,"
                                + " delivery_revision = delivery_revision + 1 WHERE id = ? AND"
                                + " completed_at IS NULL AND quarantined_at IS NULL AND"
                                + " delivery_revision = ?",
                        event.id(),
                        event.revision())
                == 1;
    }

    /**
     * 失败只保存固定分类与不含凭据的关联 ID；同一快照最多计入一次失败。
     *
     * @param event 通知事实快照
     * @param category 受控故障类别
     * @param failureId 无敏感信息的诊断关联ID
     * @param nextAttempt 计划重试时间
     * @return 是否成功更新当前围栏事实
     */
    boolean retry(
            CacheEvent event,
            CacheOutboxFailure.Category category,
            String failureId,
            LocalDateTime nextAttempt) {
        return jdbc.update(
                        "UPDATE sys_cache_outbox SET attempts = attempts + 1, last_error = ?,"
                                + " last_failure_id = ?, next_attempt_at = ?, delivery_revision ="
                                + " delivery_revision + 1 WHERE id = ? AND completed_at IS NULL AND"
                                + " delivery_revision = ?",
                        category.name(),
                        failureId,
                        nextAttempt,
                        event.id(),
                        event.revision())
                == 1;
    }

    /**
     * 重试退避完全使用数据库相对时间，节点zone/clock不参与到期合同。
     *
     * @param event 通知事实快照
     * @param category 受控故障类别
     * @param failureId 无敏感信息的诊断关联ID
     * @param seconds 数据库相对退避秒数
     * @return 是否成功更新当前围栏事实
     */
    boolean retryAfter(
            CacheEvent event,
            CacheOutboxFailure.Category category,
            String failureId,
            long seconds) {
        return jdbc.update(
                        "UPDATE sys_cache_outbox SET attempts = attempts + 1, last_error = ?,"
                                + " last_failure_id = ?, next_attempt_at = TIMESTAMPADD(SECOND, ?,"
                                + " CURRENT_TIMESTAMP), delivery_revision = delivery_revision + 1 WHERE"
                                + " id = ? AND completed_at IS NULL AND quarantined_at IS NULL AND"
                                + " delivery_revision = ?",
                        category.name(),
                        failureId,
                        seconds,
                        event.id(),
                        event.revision())
                == 1;
    }

    /**
     * 永久非法事件不无限挤占到期批次，仍保留原载荷/围栏供人工审查。
     *
     * @param event 通知事实快照
     * @param failureId 无敏感信息的诊断关联ID
     * @return 是否成功更新当前围栏事实
     */
    boolean quarantine(CacheEvent event, String failureId) {
        return jdbc.update(
                        "UPDATE sys_cache_outbox SET quarantined_at = CURRENT_TIMESTAMP, last_error"
                                + " = 'INVALID_EVENT', last_failure_id = ?, attempts = attempts + 1,"
                                + " delivery_revision = delivery_revision + 1 WHERE id = ? AND"
                                + " completed_at IS NULL AND quarantined_at IS NULL AND"
                                + " delivery_revision = ?",
                        failureId,
                        event.id(),
                        event.revision())
                == 1;
    }

    /**
     * 只选择到期已完成历史，未发/隔离事件不进入归档计划。
     *
     * @param retentionDays 完成记录保留天数
     * @param limit 本次查询/操作的数量上限
     * @return 当前事实派生的结果
     */
    List<CacheEvent> archiveCandidates(int retentionDays, int limit) {
        return jdbc.query(
                "SELECT "
                        + EVENT_COLUMNS
                        + " FROM sys_cache_outbox WHERE archived_at IS NULL AND quarantined_at IS"
                        + " NULL AND completed_at IS NOT NULL AND completed_at < TIMESTAMPADD(DAY,"
                        + " ?, CURRENT_TIMESTAMP) ORDER BY completed_at, id LIMIT ?",
                EVENT_MAPPER,
                -retentionDays,
                limit);
    }

    /**
     * 逻辑归档保留核对和重放能力，版本围栏防止并发重排的事件被归档。
     *
     * @param event 通知事实快照
     * @return 是否成功更新当前围栏事实
     */
    boolean archive(CacheEvent event) {
        return jdbc.update(
                        "UPDATE sys_cache_outbox SET archived_at = CURRENT_TIMESTAMP,"
                                + " delivery_revision = delivery_revision + 1 WHERE id = ? AND"
                                + " delivery_revision = ? AND completed_at IS NOT NULL AND"
                                + " quarantined_at IS NULL AND archived_at IS NULL",
                        event.id(),
                        event.revision())
                == 1;
    }

    /**
     * 治理查询返回最近记录，不因后台扫描而加载无限历史。
     *
     * @param limit 本次查询/操作的数量上限
     * @return 当前事实派生的结果
     */
    List<CacheEvent> recent(int limit) {
        return jdbc.query(
                "SELECT "
                        + EVENT_COLUMNS
                        + " FROM sys_cache_outbox "
                        + "WHERE archived_at IS NULL ORDER BY created_at DESC, id DESC LIMIT ?",
                EVENT_MAPPER,
                limit);
    }

    /**
     * 预览读取普通快照；真正重放仍使用行锁和 revision 重查。
     *
     * @param id 记录ID
     * @return 当前事实派生的结果
     */
    CacheEvent find(String id) {
        return jdbc
                .query(
                        "SELECT " + EVENT_COLUMNS + " FROM sys_cache_outbox WHERE id = ?",
                        EVENT_MAPPER,
                        id)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 重放事务中锁定目标，不在该事务执行 Redis 调用。
     *
     * @param id 记录ID
     * @return 当前事实派生的结果
     */
    CacheEvent lock(String id) {
        return jdbc
                .query(
                        "SELECT "
                                + EVENT_COLUMNS
                                + " FROM sys_cache_outbox WHERE id = ? FOR UPDATE",
                        EVENT_MAPPER,
                        id)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 只重新安排相同通知，不修改 cache_version、epoch 或历史失败次数。
     *
     * @param event 通知事实快照
     * @return 是否成功更新当前围栏事实
     */
    boolean reschedule(CacheEvent event) {
        return jdbc.update(
                        "UPDATE sys_cache_outbox SET completed_at = NULL, quarantined_at = NULL,"
                                + " archived_at = NULL, next_attempt_at = CURRENT_TIMESTAMP,"
                                + " delivery_revision = delivery_revision + 1 WHERE id = ? AND"
                                + " delivery_revision = ?",
                        event.id(),
                        event.revision())
                == 1;
    }

    /**
     * 审计和重排必须在同一事务，审计写失败应让重放回滚。
     *
     * @param event 通知事实快照
     * @param actorUserId 可信操作人账号ID
     * @param actorCompanyId 可信操作人公司ID
     * @param reason 已校验的操作原因
     * @return 当前事实派生的结果
     */
    String auditReplay(
            CacheEvent event,
            String actorUserId,
            String actorCompanyId,
            CacheOutboxReplayReason reason) {
        return auditOperation(event, actorUserId, actorCompanyId, reason.name());
    }

    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @param event 通知事实快照
     * @param actorUserId 可信操作人账号ID
     * @param actorCompanyId 可信操作人公司ID
     * @return 当前事实派生的结果
     */
    String auditArchive(CacheEvent event, String actorUserId, String actorCompanyId) {
        return auditOperation(event, actorUserId, actorCompanyId, "RETENTION_ARCHIVE");
    }

    private String auditOperation(
            CacheEvent event, String actorUserId, String actorCompanyId, String reason) {
        String id = UUID.randomUUID().toString();
        int inserted =
                jdbc.update(
                        "INSERT INTO sys_cache_outbox_replay_log (id, event_id, actor_user_id,"
                                + " actor_company_id, reason, previous_revision, new_revision,"
                                + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                        id,
                        event.id(),
                        actorUserId,
                        actorCompanyId,
                        reason,
                        event.revision(),
                        event.revision() + 1);
        if (inserted != 1) {
            throw new IllegalStateException("缓存通知重放审计写入失败");
        }
        return id;
    }

    /**
     * 只追加的重放历史，按事件有界返回，供授权运维核对操作关联 ID。
     *
     * @param eventId 缓存通知投递ID
     * @return 当前事实派生的结果
     */
    List<ReplayAudit> replayAudits(String eventId) {
        return jdbc.query(
                "SELECT id, event_id, actor_user_id, actor_company_id, reason, previous_revision,"
                        + " new_revision, created_at FROM sys_cache_outbox_replay_log WHERE event_id ="
                        + " ? ORDER BY created_at DESC, id DESC LIMIT 100",
                (rs, row) ->
                        new ReplayAudit(
                                rs.getString("id"),
                                rs.getString("event_id"),
                                rs.getString("actor_user_id"),
                                rs.getString("actor_company_id"),
                                rs.getString("reason"),
                                rs.getLong("previous_revision"),
                                rs.getLong("new_revision"),
                                rs.getObject("created_at", LocalDateTime.class)),
                eventId);
    }

    /**
     * 单次聚合取得积压指标；数据库当前时间避免使用各实例时钟计算最老事件年龄。
     *
     * @return 当前事实派生的结果
     */
    OutboxSnapshot snapshot() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) AS pending, COALESCE(SUM(CASE WHEN quarantined_at IS NULL AND"
                        + " next_attempt_at <= CURRENT_TIMESTAMP THEN 1 ELSE 0 END), 0) AS due,"
                        + " COALESCE(SUM(CASE WHEN attempts > 0 THEN 1 ELSE 0 END), 0) AS failed,"
                        + " COALESCE(SUM(attempts), 0) AS retries, COALESCE(MAX(attempts), 0) AS"
                        + " max_attempts, MIN(CASE WHEN quarantined_at IS NULL THEN created_at ELSE"
                        + " NULL END) AS oldest, CURRENT_TIMESTAMP AS database_now, COALESCE(SUM(CASE"
                        + " WHEN quarantined_at IS NOT NULL THEN 1 ELSE 0 END), 0) AS quarantined FROM"
                        + " sys_cache_outbox WHERE completed_at IS NULL",
                (rs, row) ->
                        new OutboxSnapshot(
                                rs.getLong("pending"),
                                rs.getLong("due"),
                                rs.getLong("failed"),
                                rs.getLong("retries"),
                                rs.getLong("max_attempts"),
                                ageSeconds(
                                        rs.getObject("oldest", LocalDateTime.class),
                                        rs.getObject("database_now", LocalDateTime.class)),
                                rs.getLong("quarantined")));
    }

    private static long ageSeconds(LocalDateTime oldest, LocalDateTime now) {
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).getSeconds());
    }
}
