package com.robot.system.permission.service;

import com.robot.system.api.dto.cache.CacheOutboxReplayReason;

import org.springframework.jdbc.core.JdbcTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 系统域缓存版本与待发事件；控制表不承载租户业务查询。 */
@Repository
public class PermissionStateStore {

    public static final String PERMISSIONS = "permissions";
    private static final String EVENT_COLUMNS = "id, event_kind, cache_key, cache_version, attempts, "
            + "delivery_revision, created_at, next_attempt_at, completed_at, last_error, last_failure_id";
    private static final RowMapper<CacheEvent> EVENT_MAPPER = (rs, row) -> new CacheEvent(
            rs.getString("id"), rs.getString("event_kind"), rs.getString("cache_key"),
            rs.getLong("cache_version"), rs.getInt("attempts"), rs.getLong("delivery_revision"),
            localTime(rs.getTimestamp("created_at")), localTime(rs.getTimestamp("next_attempt_at")),
            localTime(rs.getTimestamp("completed_at")), rs.getString("last_error"),
            rs.getString("last_failure_id"));
    private final JdbcTemplate jdbc;

    public PermissionStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 从已提交数据库读取权威 epoch；Redis 被清空不会重复使用旧版本。 */
    public long currentVersion(String scope) {
        List<Long> values = jdbc.query("SELECT version FROM sys_cache_epoch WHERE scope = ?",
                (rs, row) -> rs.getLong(1), scope);
        if (PERMISSIONS.equals(scope) && (values.isEmpty() || values.get(0) < 1)) {
            throw new IllegalStateException("权限版本未初始化，请先完成数据库迁移");
        }
        return values.isEmpty() ? 0 : values.get(0);
    }

    /** 必须与业务写同事务调用，数据库行锁保证并发递增。 */
    public long incrementVersion(String scope) {
        jdbc.update("INSERT INTO sys_cache_epoch (scope, version) VALUES (?, 1) "
                + "ON DUPLICATE KEY UPDATE version = version + 1", scope);
        return currentVersion(scope);
    }

    /** 菜单拓扑更改在检查前锁定同一控制行，避免并发移动分别通过检查后形成环。 */
    public void lockMenuTree() {
        jdbc.queryForObject("SELECT version FROM sys_cache_epoch WHERE scope = 'menu-tree' FOR UPDATE", Long.class);
    }

    /** 登记与业务事务一起提交的通知；事件内容在重放时保持不变。 */
    public String enqueue(String kind, String cacheKey, long version) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sys_cache_outbox "
                        + "(id, event_kind, cache_key, cache_version, created_at, next_attempt_at, attempts) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                id, kind, cacheKey, version);
        return id;
    }

    /** 有界读取到期事件；并发投递允许重复，结果回写由单调 revision 围栏保护。 */
    public List<CacheEvent> pending() {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM sys_cache_outbox "
                + "WHERE completed_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP "
                + "ORDER BY created_at, id LIMIT 100", EVENT_MAPPER);
    }

    /** 回源重查到期状态，避免另一投递者已安排退避后仍使用旧扫描结果。 */
    public List<CacheEvent> findPending(String id) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM sys_cache_outbox "
                + "WHERE id = ? AND completed_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP", EVENT_MAPPER, id);
    }

    /** 完成确认只接受当前快照，过时投递者不得覆盖人工重放或其他投递结果。 */
    public boolean complete(CacheEvent event) {
        return jdbc.update("UPDATE sys_cache_outbox SET completed_at = CURRENT_TIMESTAMP, "
                        + "delivery_revision = delivery_revision + 1 "
                        + "WHERE id = ? AND completed_at IS NULL AND delivery_revision = ?",
                event.id(), event.revision()) == 1;
    }

    /** 失败只保存固定分类与不含凭据的关联 ID；同一快照最多计入一次失败。 */
    public boolean retry(CacheEvent event, CacheOutboxFailure.Category category, String failureId,
                         LocalDateTime nextAttempt) {
        return jdbc.update("UPDATE sys_cache_outbox SET attempts = attempts + 1, last_error = ?, "
                        + "last_failure_id = ?, next_attempt_at = ?, delivery_revision = delivery_revision + 1 "
                        + "WHERE id = ? AND completed_at IS NULL AND delivery_revision = ?",
                category.name(), failureId, nextAttempt, event.id(), event.revision()) == 1;
    }

    /** 治理查询返回最近记录，不因后台扫描而加载无限历史。 */
    public List<CacheEvent> recent(int limit) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM sys_cache_outbox "
                + "ORDER BY created_at DESC, id DESC LIMIT ?", EVENT_MAPPER, limit);
    }

    /** 预览读取普通快照；真正重放仍使用行锁和 revision 重查。 */
    public CacheEvent find(String id) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM sys_cache_outbox WHERE id = ?", EVENT_MAPPER, id)
                .stream().findFirst().orElse(null);
    }

    /** 重放事务中锁定目标，不在该事务执行 Redis 调用。 */
    public CacheEvent lock(String id) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM sys_cache_outbox WHERE id = ? FOR UPDATE", EVENT_MAPPER, id)
                .stream().findFirst().orElse(null);
    }

    /** 只重新安排相同通知，不修改 cache_version、epoch 或历史失败次数。 */
    public boolean reschedule(CacheEvent event) {
        return jdbc.update("UPDATE sys_cache_outbox SET completed_at = NULL, next_attempt_at = CURRENT_TIMESTAMP, "
                        + "delivery_revision = delivery_revision + 1 WHERE id = ? AND delivery_revision = ?",
                event.id(), event.revision()) == 1;
    }

    /** 审计和重排必须在同一事务，审计写失败应让重放回滚。 */
    public String auditReplay(CacheEvent event, String actorUserId, String actorCompanyId,
                              CacheOutboxReplayReason reason) {
        String id = UUID.randomUUID().toString();
        int inserted = jdbc.update("INSERT INTO sys_cache_outbox_replay_log "
                        + "(id, event_id, actor_user_id, actor_company_id, reason, previous_revision, "
                        + "new_revision, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                id, event.id(), actorUserId, actorCompanyId, reason.name(), event.revision(), event.revision() + 1);
        if (inserted != 1) {
            throw new IllegalStateException("缓存通知重放审计写入失败");
        }
        return id;
    }

    /** 只追加的重放历史，按事件有界返回，供授权运维核对操作关联 ID。 */
    public List<ReplayAudit> replayAudits(String eventId) {
        return jdbc.query("SELECT id, event_id, actor_user_id, actor_company_id, reason, "
                        + "previous_revision, new_revision, created_at FROM sys_cache_outbox_replay_log "
                        + "WHERE event_id = ? ORDER BY created_at DESC, id DESC LIMIT 100",
                (rs, row) -> new ReplayAudit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getLong(6), rs.getLong(7), localTime(rs.getTimestamp(8))), eventId);
    }

    /** 单次聚合取得积压指标；数据库当前时间避免使用各实例时钟计算最老事件年龄。 */
    public OutboxSnapshot snapshot() {
        return jdbc.queryForObject("SELECT COUNT(*), "
                        + "COALESCE(SUM(CASE WHEN next_attempt_at <= CURRENT_TIMESTAMP THEN 1 ELSE 0 END), 0), "
                        + "COALESCE(SUM(CASE WHEN attempts > 0 THEN 1 ELSE 0 END), 0), "
                        + "COALESCE(SUM(attempts), 0), COALESCE(MAX(attempts), 0), MIN(created_at), CURRENT_TIMESTAMP "
                        + "FROM sys_cache_outbox WHERE completed_at IS NULL",
                (rs, row) -> new OutboxSnapshot(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), ageSeconds(localTime(rs.getTimestamp(6)), localTime(rs.getTimestamp(7)))));
    }

    private static LocalDateTime localTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static long ageSeconds(LocalDateTime oldest, LocalDateTime now) {
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).getSeconds());
    }

    /** 不默认生成 toString，避免未来事件内容被诊断日志无意展开。 */
    public record CacheEvent(String id, String kind, String cacheKey, long version, int attempts, long revision,
                             LocalDateTime createdAt, LocalDateTime nextAttemptAt, LocalDateTime completedAt,
                             String errorCategory, String failureId) {
        @Override
        public String toString() {
            return "CacheEvent[id=" + id + ", kind=" + kind + ", revision=" + revision + "]";
        }
    }

    @Schema(description = "已提交缓存通知积压的数据库聚合快照")
    public record OutboxSnapshot(
            @Schema(description = "未完成事件数") long pending,
            @Schema(description = "已到期事件数") long due,
            @Schema(description = "至少失败一次的未完成事件数") long failed,
            @Schema(description = "未完成事件累计失败次数") long retries,
            @Schema(description = "未完成事件最大失败次数") long maxAttempts,
            @Schema(description = "最老未完成事件年龄秒，数据库时间口径") long oldestAgeSeconds) {
        public static final OutboxSnapshot EMPTY = new OutboxSnapshot(0, 0, 0, 0, 0, 0);
    }

    @Schema(description = "只追加的已提交缓存通知重放审计")
    public record ReplayAudit(
            @Schema(description = "审计关联ID") String id,
            @Schema(description = "被重放通知ID") String eventId,
            @Schema(description = "服务端当前认证操作者") String actorUserId,
            @Schema(description = "服务端当前可信公司") String actorCompanyId,
            @Schema(description = "受控运维原因") String reason,
            @Schema(description = "重放前围栏") long previousRevision,
            @Schema(description = "重放后围栏") long newRevision,
            @Schema(description = "已提交重放时间") LocalDateTime createdAt) {
        @Override
        public String toString() {
            return "ReplayAudit[id=" + id + ", eventId=" + eventId + "]";
        }
    }
}
