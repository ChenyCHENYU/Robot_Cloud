package com.robot.common.mybatis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ID 生成器测试：不透明 String ID，长度 ≤ 64，批内不重复。 */
class RobotIdGeneratorTest {
    private static final int SAFE_ID_BATCH = 100;
    private static final int BURST_ID_BATCH = 1000;
    private static final int MAX_ID_LENGTH = 64;
    private static final int WORKER_START_FROM_END = 9;
    private static final int WORKER_END_FROM_END = 5;
    private static final Object ENTITY_HINT = new Object();

    @Test
    @DisplayName("生成的 ID 为小写安全字符且长度受控")
    void shouldGenerateOpaqueSafeId() {
        RobotIdGenerator generator = new RobotIdGenerator();

        for (int i = 0; i < SAFE_ID_BATCH; i++) {
            String id = generator.nextUUID(ENTITY_HINT);
            assertTrue(id.length() <= MAX_ID_LENGTH, "ID 超长: " + id);
            assertTrue(id.matches("[a-f0-9]+(-[a-f0-9]+){2}"), "ID 格式非法: " + id);
        }
    }

    @Test
    @DisplayName("同实例并发序列不重复")
    void shouldNotDuplicateInBurst() {
        RobotIdGenerator generator = new RobotIdGenerator();
        var ids = new java.util.HashSet<String>();
        for (int i = 0; i < BURST_ID_BATCH; i++) {
            assertTrue(ids.add(generator.nextUUID(ENTITY_HINT)));
        }
    }

    @Test
    @DisplayName("数字 ID 通道被显式禁止")
    void numericIdShouldBeRejected() {
        RobotIdGenerator generator = new RobotIdGenerator();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> generator.nextId(ENTITY_HINT));
    }

    @Test
    @DisplayName("worker 段稳定且非全零（可追溯）")
    void workerSegmentShouldBeStable() {
        RobotIdGenerator generator = new RobotIdGenerator();
        String first = generator.nextUUID(ENTITY_HINT);
        String second = generator.nextUUID(ENTITY_HINT);
        String worker1 =
                first.substring(
                        first.length() - WORKER_START_FROM_END,
                        first.length() - WORKER_END_FROM_END);
        String worker2 =
                second.substring(
                        second.length() - WORKER_START_FROM_END,
                        second.length() - WORKER_END_FROM_END);
        assertEquals(worker1, worker2);
        assertNotEquals("0000", worker1 + worker2);
    }
}
