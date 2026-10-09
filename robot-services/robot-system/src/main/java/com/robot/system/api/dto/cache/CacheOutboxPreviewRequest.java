package com.robot.system.api.dto.cache;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/** 预览原因采用枚举，禁止提交任意异常文本或凭据。 */
@Schema(description = "缓存通知重放预览请求")
public record CacheOutboxPreviewRequest(
        @Schema(description = "受控运维原因") @NotNull CacheOutboxReplayReason reason) {
    @Override
    public String toString() {
        return "CacheOutboxPreviewRequest";
    }
}
