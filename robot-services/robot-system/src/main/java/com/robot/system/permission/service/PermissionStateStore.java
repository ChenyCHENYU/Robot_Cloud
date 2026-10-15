package com.robot.system.permission.service;

import com.robot.system.api.dto.cache.CacheOutboxReplayReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 系统域缓存版本与待发事件；控制表不承载租户业务查询。 */
@Repository
public class PermissionStateStore {

    public static final String PERMISSIONS = "permissions";
    private final JdbcTemplate jdbc;
    private final CacheOutboxStore outbox;

    /**
     * 初始化当前用例的明确协作依赖或兼容值元组。
     *
     * @param jdbc 受控数据库访问入口
     */
    public PermissionStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.outbox = new CacheOutboxStore(jdbc);
    }

    /**
     * 从已提交数据库读取权威 epoch；Redis 被清空不会重复使用旧版本。
     *
     * @param scope 权威版本或数据范围键
     * @return 当前事实派生的结果
     * @throws IllegalStateException 当前身份/状态不满足用例约束或受控操作失败
     */
    public long currentVersion(String scope) {
        List<Long> values =
                jdbc.query(
                        "SELECT version FROM sys_cache_epoch WHERE scope = ?",
                        (rs, row) -> rs.getLong(1),
                        scope);
        if (PERMISSIONS.equals(scope) && (values.isEmpty() || values.get(0) < 1)) {
            throw new IllegalStateException("权限版本未初始化，请先完成数据库迁移");
        }
        return values.isEmpty() ? 0 : values.get(0);
    }

    /**
     * 必须与业务写同事务调用，数据库行锁保证并发递增。
     *
     * @param scope 权威版本或数据范围键
     * @return 当前事实派生的结果
     */
    public long incrementVersion(String scope) {
        jdbc.update(
                "INSERT INTO sys_cache_epoch (scope, version) VALUES (?, 1) "
                        + "ON DUPLICATE KEY UPDATE version = version + 1",
                scope);
        return currentVersion(scope);
    }

    /** 菜单拓扑更改在检查前锁定同一控制行，避免并发移动分别通过检查后形成环。 */
    public void lockMenuTree() {
        jdbc.queryForObject(
                "SELECT version FROM sys_cache_epoch WHERE scope = 'menu-tree' FOR UPDATE",
                Long.class);
    }

    /**
     * 公司聚合协调行先于普通读取加锁，所有角色/成员/临时关联命令共享此协议。 角色目录绑定先锁全局菜单树，再锁公司；成员和临时命令只锁公司，不反向请求树锁。
     *
     * @param companyId 服务端确认的公司ID
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockCompanyWrites(String companyId) {
        com.robot.common.core.exception.ServiceAssert.notBlank(
                companyId, com.robot.common.core.domain.ResultCode.FORBIDDEN);
        String scope = "company-write:" + companyId;
        jdbc.update(
                "INSERT INTO sys_cache_epoch (scope, version) VALUES (?, 1) "
                        + "ON DUPLICATE KEY UPDATE scope = VALUES(scope)",
                scope);
        jdbc.queryForObject(
                "SELECT version FROM sys_cache_epoch WHERE scope = ? FOR UPDATE",
                Long.class,
                scope);
    }

    /**
     * 登记与业务事务一起提交的通知；事件内容在重放时保持不变。
     *
     * @param kind 通知类型
     * @param cacheKey 目标缓存键
     * @param version 权威缓存版本
     * @return 当前事实派生的结果
     */
    public String enqueue(String kind, String cacheKey, long version) {
        return outbox.enqueue(kind, cacheKey, version);
    }

    /**
     * 有界读取到期事件；并发投递允许重复，结果回写由单调 revision 围栏保护。
     *
     * @return 当前事实派生的结果
     */
    public List<CacheEvent> pending() {
        return outbox.pending();
    }

    /**
     * 回源重查到期状态，避免另一投递者已安排退避后仍使用旧扫描结果。
     *
     * @param id 记录ID
     * @return 当前事实派生的结果
     */
    public List<CacheEvent> findPending(String id) {
        return outbox.findPending(id);
    }

    /**
     * 完成确认只接受当前快照，过时投递者不得覆盖人工重放或其他投递结果。
     *
     * @param event 通知事实快照
     * @return 是否成功更新当前围栏事实
     */
    public boolean complete(CacheEvent event) {
        return outbox.complete(event);
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
    public boolean retry(
            CacheEvent event,
            CacheOutboxFailure.Category category,
            String failureId,
            LocalDateTime nextAttempt) {
        return outbox.retry(event, category, failureId, nextAttempt);
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
    public boolean retryAfter(
            CacheEvent event,
            CacheOutboxFailure.Category category,
            String failureId,
            long seconds) {
        return outbox.retryAfter(event, category, failureId, seconds);
    }

    /**
     * 永久非法事件不无限挤占到期批次，仍保留原载荷/围栏供人工审查。
     *
     * @param event 通知事实快照
     * @param failureId 无敏感信息的诊断关联ID
     * @return 是否成功更新当前围栏事实
     */
    public boolean quarantine(CacheEvent event, String failureId) {
        return outbox.quarantine(event, failureId);
    }

    /**
     * 只选择到期已完成历史，未发/隔离事件不进入归档计划。
     *
     * @param retentionDays 完成记录保留天数
     * @param limit 本次查询/操作的数量上限
     * @return 当前事实派生的结果
     */
    public List<CacheEvent> archiveCandidates(int retentionDays, int limit) {
        return outbox.archiveCandidates(retentionDays, limit);
    }

    /**
     * 逻辑归档保留核对和重放能力，版本围栏防止并发重排的事件被归档。
     *
     * @param event 通知事实快照
     * @return 是否成功更新当前围栏事实
     */
    public boolean archive(CacheEvent event) {
        return outbox.archive(event);
    }

    /**
     * 治理查询返回最近记录，不因后台扫描而加载无限历史。
     *
     * @param limit 本次查询/操作的数量上限
     * @return 当前事实派生的结果
     */
    public List<CacheEvent> recent(int limit) {
        return outbox.recent(limit);
    }

    /**
     * 预览读取普通快照；真正重放仍使用行锁和 revision 重查。
     *
     * @param id 记录ID
     * @return 当前事实派生的结果
     */
    public CacheEvent find(String id) {
        return outbox.find(id);
    }

    /**
     * 重放事务中锁定目标，不在该事务执行 Redis 调用。
     *
     * @param id 记录ID
     * @return 当前事实派生的结果
     */
    public CacheEvent lock(String id) {
        return outbox.lock(id);
    }

    /**
     * 只重新安排相同通知，不修改 cache_version、epoch 或历史失败次数。
     *
     * @param event 通知事实快照
     * @return 是否成功更新当前围栏事实
     */
    public boolean reschedule(CacheEvent event) {
        return outbox.reschedule(event);
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
    public String auditReplay(
            CacheEvent event,
            String actorUserId,
            String actorCompanyId,
            CacheOutboxReplayReason reason) {
        return outbox.auditReplay(event, actorUserId, actorCompanyId, reason);
    }

    /**
     * 追加已完成记录的逻辑留存审计，保留原通知载荷与围栏。
     *
     * @param event 通知事实快照
     * @param actorUserId 可信操作人账号ID
     * @param actorCompanyId 可信操作人公司ID
     * @return 当前事实派生的结果
     */
    public String auditArchive(CacheEvent event, String actorUserId, String actorCompanyId) {
        return outbox.auditArchive(event, actorUserId, actorCompanyId);
    }

    /**
     * 只追加的重放历史，按事件有界返回，供授权运维核对操作关联 ID。
     *
     * @param eventId 缓存通知投递ID
     * @return 当前事实派生的结果
     */
    public List<ReplayAudit> replayAudits(String eventId) {
        return outbox.replayAudits(eventId);
    }

    /**
     * 单次聚合取得积压指标；数据库当前时间避免使用各实例时钟计算最老事件年龄。
     *
     * @return 当前事实派生的结果
     */
    public OutboxSnapshot snapshot() {
        return outbox.snapshot();
    }

    /**
     * 不默认生成 toString，避免未来事件内容被诊断日志无意展开。
     *
     * @param id 记录ID
     * @param kind 通知类型
     * @param cacheKey 目标缓存键
     * @param version 权威缓存版本
     * @param attempts 已记录失败次数
     * @param revision 当前聚合版本
     * @param createdAt 创建时间
     * @param nextAttemptAt 数据库计划重试时间
     * @param completedAt 完成时间
     * @param errorCategory 受控错误类别
     * @param failureId 无敏感信息的诊断关联ID
     * @param quarantinedAt 隔离时间
     * @param archivedAt 逻辑归档时间
     */
    public record CacheEvent(
            String id,
            String kind,
            String cacheKey,
            long version,
            int attempts,
            long revision,
            LocalDateTime createdAt,
            LocalDateTime nextAttemptAt,
            LocalDateTime completedAt,
            String errorCategory,
            String failureId,
            LocalDateTime quarantinedAt,
            LocalDateTime archivedAt) {
        /**
         * 初始化当前用例的明确协作依赖或兼容值元组。
         *
         * @param id 记录ID
         * @param kind 通知类型
         * @param cacheKey 目标缓存键
         * @param version 权威缓存版本
         * @param attempts 已记录失败次数
         * @param revision 当前聚合版本
         * @param createdAt 创建时间
         * @param nextAttemptAt 数据库计划重试时间
         * @param completedAt 完成时间
         * @param errorCategory 受控错误类别
         * @param failureId 无敏感信息的诊断关联ID
         */
        public CacheEvent(
                String id,
                String kind,
                String cacheKey,
                long version,
                int attempts,
                long revision,
                LocalDateTime createdAt,
                LocalDateTime nextAttemptAt,
                LocalDateTime completedAt,
                String errorCategory,
                String failureId) {
            this(
                    id,
                    kind,
                    cacheKey,
                    version,
                    attempts,
                    revision,
                    createdAt,
                    nextAttemptAt,
                    completedAt,
                    errorCategory,
                    failureId,
                    null,
                    null);
        }

        @Override
        public String toString() {
            return "CacheEvent[id=" + id + ", kind=" + kind + ", revision=" + revision + "]";
        }
    }

    /**
     * 已提交Outbox积压的数据库聚合快照，年龄采用数据库时钟。
     *
     * @param pending 未完成通知数
     * @param due 已到期可重试数
     * @param failed 至少失败一次的待发数
     * @param retries 未完成通知累计失败次数
     * @param maxAttempts 单条未完成通知最大失败次数
     * @param oldestAgeSeconds 按数据库时钟计算的最老待发年龄秒数
     * @param quarantined 永久非法已隔离通知数
     */
    @Schema(description = "已提交缓存通知积压的数据库聚合快照")
    public record OutboxSnapshot(
            @Schema(description = "未完成事件数") long pending,
            @Schema(description = "已到期事件数") long due,
            @Schema(description = "至少失败一次的未完成事件数") long failed,
            @Schema(description = "未完成事件累计失败次数") long retries,
            @Schema(description = "未完成事件最大失败次数") long maxAttempts,
            @Schema(description = "最老未完成事件年龄秒，数据库时间口径") long oldestAgeSeconds,
            @Schema(description = "已隔离的永久非法事件数") long quarantined) {
        /**
         * 初始化当前用例的明确协作依赖或兼容值元组。
         *
         * @param pending 未完成通知数
         * @param due 已到期可重试数
         * @param failed 至少失败一次的待发数
         * @param retries 未完成通知累计失败次数
         * @param maxAttempts 单条未完成通知最大失败次数
         * @param oldestAgeSeconds 按数据库时钟计算的最老待发年龄秒数
         */
        public OutboxSnapshot(
                long pending,
                long due,
                long failed,
                long retries,
                long maxAttempts,
                long oldestAgeSeconds) {
            this(pending, due, failed, retries, maxAttempts, oldestAgeSeconds, 0);
        }

        public static final OutboxSnapshot EMPTY = new OutboxSnapshot(0, 0, 0, 0, 0, 0);
    }

    /**
     * 只追加的缓存通知重放/归档操作审计快照。
     *
     * @param id 记录ID
     * @param eventId 缓存通知投递ID
     * @param actorUserId 可信操作人账号ID
     * @param actorCompanyId 可信操作人公司ID
     * @param reason 已校验的操作原因
     * @param previousRevision 操作前围栏版本
     * @param newRevision 操作后围栏版本
     * @param createdAt 创建时间
     */
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
