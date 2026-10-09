package com.robot.system.api.vo.cache;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** 治理输出只包含状态与安全诊断字段，不向客户端开放任意 Redis key 重放。 */
@Schema(description = "缓存通知安全状态视图")
public record CacheOutboxEventView(
        @Schema(description = "通知标识") String eventId,
        @Schema(description = "通知种类：permissions/dictionary") String kind,
        @Schema(description = "事件对应的数据库版本") long cacheVersion,
        @Schema(description = "PENDING/COMPLETED，不表示授权业务状态") String status,
        @Schema(description = "单调投递围栏") long revision,
        @Schema(description = "已登记失败次数，重放不清零") int attempts,
        @Schema(description = "通知入队时间") LocalDateTime createdAt,
        @Schema(description = "下次扫描可投递时间") LocalDateTime nextAttemptAt,
        @Schema(description = "Redis通知已确认时间；未完成为空") LocalDateTime completedAt,
        @Schema(description = "固定安全错误分类，旧记录统一LEGACY_FAILURE") String errorCategory,
        @Schema(description = "安全诊断关联ID") String failureId) {
    @Override
    public String toString() {
        return "CacheOutboxEventView[eventId=" + eventId + ", revision=" + revision + "]";
    }
}
