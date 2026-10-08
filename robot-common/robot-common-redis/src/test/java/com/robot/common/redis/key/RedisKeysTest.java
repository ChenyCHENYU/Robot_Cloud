package com.robot.common.redis.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis Key 通用构建约定测试：命名空间 / 环境段 / 动态段安全收敛。
 * （业务键 loginTicket/dictCache 等已迁移至各服务 AuthKeys/SystemKeys，
 * 本测试只覆盖 common 通用能力。）
 */
class RedisKeysTest {

    @Test
    @DisplayName("命名空间结构：robot:{env}:{service}:...")
    void keyShouldFollowPrefixContract() {
        System.setProperty("robot.env", "dev");
        try {
            assertEquals("robot:dev:system:lock:import:order-9",
                    RedisKeys.lock("system", "import", "order-9"));
            assertEquals("robot:dev:order:cache:1001",
                    RedisKeys.businessCache("order", "cache", "1001"));
            assertEquals("robot:dev:system", RedisKeys.base("system"));
        } finally {
            System.clearProperty("robot.env");
        }
    }

    @Test
    @DisplayName("环境段：属性优先于环境变量，缺失回落 local，非法字符清洗")
    void envSegmentShouldFollowPrecedence() {
        System.setProperty("robot.env", "Prod X");
        try {
            assertEquals("robot:prod-x:system", RedisKeys.base("system"));
        } finally {
            System.clearProperty("robot.env");
        }
        // 属性缺失 → 环境变量/默认值路径（此处仅验证回落逻辑不抛异常）
        assertTrue(RedisKeys.base("svc").startsWith("robot:"));
    }

    @Test
    @DisplayName("动态段安全收敛：空值/非法字符/超长全部拒绝")
    void dynamicSegmentShouldRejectUnsafeInput() {
        assertThrows(IllegalArgumentException.class, () -> RedisKeys.base(" "));
        assertThrows(IllegalArgumentException.class, () -> RedisKeys.lock("s", "a b", "1"));
        assertThrows(IllegalArgumentException.class, () -> RedisKeys.businessCache("s", "d", "x".repeat(65)));
        assertEquals("robot:local:system:lock:ok_1.0:2",
                RedisKeys.lock("system", "ok_1.0", "2"));
    }

    @Test
    @DisplayName("TTL 常量符合规范：抖动 ≤ 60s、空值占位 2 分钟")
    void ttlConstantsShouldMatchStandards() {
        assertTrue(RedisKeys.TTL_JITTER_BOUND.getSeconds() <= 60);
        assertEquals(Duration.ofMinutes(2), RedisKeys.TTL_NULL_PLACEHOLDER);
    }
}
