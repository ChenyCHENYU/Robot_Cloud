package com.robot.common.mybatis.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ID 生成器测试：不透明 String ID，长度 ≤ 64，批内不重复。
 */
class RobotIdGeneratorTest {

    @Test
    @DisplayName("生成的 ID 为小写安全字符且长度受控")
    void shouldGenerateOpaqueSafeId() {
        RobotIdGenerator generator = new RobotIdGenerator();

        for (int i = 0; i < 100; i++) {
            String id = generator.nextUUID(new Object());
            assertTrue(id.length() <= 64, "ID 超长: " + id);
            assertTrue(id.matches("[a-f0-9]+(-[a-f0-9]+){2}"), "ID 格式非法: " + id);
        }
    }

    @Test
    @DisplayName("同实例并发序列不重复")
    void shouldNotDuplicateInBurst() {
        RobotIdGenerator generator = new RobotIdGenerator();
        var ids = new java.util.HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(ids.add(generator.nextUUID(new Object())));
        }
    }

    @Test
    @DisplayName("数字 ID 通道被显式禁止")
    void numericIdShouldBeRejected() {
        RobotIdGenerator generator = new RobotIdGenerator();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> generator.nextId(new Object()));
    }

    @Test
    @DisplayName("worker 段稳定且非全零（可追溯）")
    void workerSegmentShouldBeStable() {
        RobotIdGenerator generator = new RobotIdGenerator();
        String first = generator.nextUUID(new Object());
        String second = generator.nextUUID(new Object());
        String worker1 = first.substring(first.length() - 9, first.length() - 5);
        String worker2 = second.substring(second.length() - 9, second.length() - 5);
        assertEquals(worker1, worker2);
        assertNotEquals("0000", worker1 + worker2);
    }
}
