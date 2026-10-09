package com.robot.system.api.dto.cache;

import io.swagger.v3.oas.annotations.media.Schema;

/** 受控运维重放原因；不接收可能携带凭据的任意文本。 */
@Schema(description = "重放原因：DEPENDENCY_RECOVERED依赖已恢复；OPERATOR_RETRY人工核对后重试")
public enum CacheOutboxReplayReason {
    DEPENDENCY_RECOVERED,
    OPERATOR_RETRY
}
