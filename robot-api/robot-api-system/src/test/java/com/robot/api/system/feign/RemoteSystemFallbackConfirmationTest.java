package com.robot.api.system.feign;

import static org.assertj.core.api.Assertions.assertThat;

import com.robot.api.system.dto.RemoteLoginLogDTO;
import org.junit.jupiter.api.Test;

/** 可靠审计仅在真实持久化确认后 ACK，熔断降级不能返回成功。 */
class RemoteSystemFallbackConfirmationTest {
    @Test
    void unavailableSystemMustNotAcknowledgeAuditPersistence() {
        var fallback =
                new RemoteSystemServiceFallbackFactory()
                        .create(new IllegalStateException("synthetic-private"));
        var result = fallback.recordLogin(new RemoteLoginLogDTO());
        assertThat(result.successValue()).isFalse();
        assertThat(result.getMessage()).doesNotContain("synthetic-private");
    }
}
