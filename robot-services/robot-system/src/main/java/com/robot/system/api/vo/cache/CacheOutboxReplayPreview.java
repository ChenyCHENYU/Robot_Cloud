package com.robot.system.api.vo.cache;

import com.robot.system.api.dto.cache.CacheOutboxReplayReason;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 摘要绑定当前操作者、原因和完整事件快照；状态漂移后必须重新预览。
 *
 * @param event 通知事实快照
 * @param reason 已校验的操作原因
 * @param planHash 绑定当前事实快照的确认摘要
 */
@Schema(description = "缓存通知重放预览结果")
public record CacheOutboxReplayPreview(
        @Schema(description = "当前事件快照") CacheOutboxEventView event,
        @Schema(description = "本次确认绑定的原因") CacheOutboxReplayReason reason,
        @Schema(description = "确认时原样提交的状态摘要") String planHash) {
    @Override
    public String toString() {
        return "CacheOutboxReplayPreview";
    }
}
