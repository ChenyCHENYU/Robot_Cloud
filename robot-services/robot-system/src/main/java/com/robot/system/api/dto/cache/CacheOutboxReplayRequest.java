package com.robot.system.api.dto.cache;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 确认预览后提交相同原因与状态摘要；操作者和事件内容只从服务端读取。 */
@Schema(description = "缓存通知重放确认请求")
public record CacheOutboxReplayRequest(
        @Schema(description = "必须与预览相同的受控原因") @NotNull CacheOutboxReplayReason reason,
        @Schema(description = "绑定操作者及事件快照的SHA-256确认摘要；漂移必须重新预览")
        @NotNull @Pattern(regexp = "[a-f0-9]{64}") String planHash) {
    @Override
    public String toString() {
        return "CacheOutboxReplayRequest";
    }
}
