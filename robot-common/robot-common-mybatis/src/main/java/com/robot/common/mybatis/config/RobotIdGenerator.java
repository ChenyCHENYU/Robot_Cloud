package com.robot.common.mybatis.config;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 不透明 String ID 生成器：32 位无中划线 UUID 主体 + 短机器段 + 序列段， 满足「VARCHAR(64) 不透明 ID」约定，同时保证同一毫秒内有序性可读性优于裸
 * UUID。
 */
public class RobotIdGenerator implements IdentifierGenerator {

    private static final String WORKER_ID = resolveWorkerId();
    private static final long SEQUENCE_MASK = 0xFFFFL;
    private static final int WORKER_MASK = 0xFFF;

    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public Number nextId(Object entity) {
        throw new UnsupportedOperationException("本工程主键统一 String，禁止数字 ID");
    }

    @Override
    public String nextUUID(Object entity) {
        return IdUtil.fastSimpleUUID() + "-" + WORKER_ID + "-" + nextSequence();
    }

    private String nextSequence() {
        long value = sequence.incrementAndGet() & SEQUENCE_MASK;
        return String.format("%04x", value);
    }

    private static String resolveWorkerId() {
        try {
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            // JVM 标识包含进程与主机，用于构建短机器段。
            String name = runtime.getName();
            String host = InetAddress.getLocalHost().getHostName();
            int hash = (name + host).hashCode() & WORKER_MASK;
            return String.format("%03x", hash);
        } catch (UnknownHostException | SecurityException e) {
            return "000";
        }
    }
}
