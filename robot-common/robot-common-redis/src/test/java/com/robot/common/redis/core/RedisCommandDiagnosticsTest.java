package com.robot.common.redis.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 状态迁移与事件提交的嵌套诊断不能暴露 key、凭据、身份和完整载荷。 */
class RedisCommandDiagnosticsTest {
    @Test
    void nestedStateAndEventDiagnosticsHideSensitiveMaterial() {
        var state =
                new RedisHelper.StateTransition(
                        "private-key", "old-secret", "new-secret", Duration.ofSeconds(1));
        var queue = new RedisHelper.EventQueue("private-hash", "private-due", 1);
        var event = new RedisHelper.EventPayload("private-id", "private-payload", 1L);
        var command = new RedisHelper.EventAppend(queue, event);
        String diagnostic = Map.of("commands", List.of(state, command)).toString();
        assertThat(diagnostic)
                .doesNotContain(
                        "private-key",
                        "old-secret",
                        "new-secret",
                        "private-hash",
                        "private-due",
                        "private-id",
                        "private-payload");
    }
}
