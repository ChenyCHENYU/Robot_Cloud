package com.robot.system.api.vo.cache;

import io.swagger.v3.oas.annotations.media.Schema;

/** 返回只追加审计ID和新围栏版本；受理不代表 Redis 已完成投递。 */
@Schema(description = "缓存通知重放受理结果")
public record CacheOutboxReplayResult(
        @Schema(description = "只追加重放审计ID") String auditId,
        @Schema(description = "重新排队的通知ID") String eventId,
        @Schema(description = "重排后单调围栏；受理不表示Redis已投递") long revision) {
    @Override
    public String toString() {
        return "CacheOutboxReplayResult[eventId=" + eventId + ", revision=" + revision + "]";
    }
}
