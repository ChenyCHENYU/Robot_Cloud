package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.robot.common.redis.core.RedisHelper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 真实 Spring 序列化与命令边界，连接为内存替身；不执行 Lua、不连接 Redis。 */
@SuppressWarnings("deprecation") // Spring Data Redis 3.2 的默认脚本执行器使用 RedisConnection.evalSha。
class AuthRedisTransportTest {
    private static final int SECRET_HEX_LENGTH = 64;
    private static final int STATE_TTL_MILLIS = 1234;
    private static final int SCRIPT_ARGUMENT_ARRAY_INDEX = 3;
    private static final int CAS_WIRE_ARGUMENT_COUNT = 4;

    private RedisConnection connection;
    private RedisHelper redis;

    @BeforeEach
    void setUp() {
        connection = mock(RedisConnection.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        redis = new RedisHelper(new StringRedisTemplate(factory), null);
    }

    @Test
    void casUsesOneKeyAndExactUtf8StateAndMillisecondTtlArguments() {
        String key = "robot:test:auth:family:" + "a".repeat(SECRET_HEX_LENGTH);
        String before = "{\"userId\":\"成员甲\",\"version\":0}";
        String after = "{\"userId\":\"成员甲\",\"version\":1}";
        Duration ttl = Duration.ofMillis(STATE_TTL_MILLIS);
        when(connection.evalSha(anyString(), eq(ReturnType.INTEGER), eq(1), any(byte[][].class)))
                .thenAnswer(
                        invocation -> {
                            byte[][] actual =
                                    (byte[][])
                                            invocation
                                                    .getRawArguments()[SCRIPT_ARGUMENT_ARRAY_INDEX];
                            assertEquals(CAS_WIRE_ARGUMENT_COUNT, actual.length);
                            assertArrayEquals(bytes(key), actual[0]);
                            assertArrayEquals(bytes(before), actual[1]);
                            assertArrayEquals(bytes(after), actual[2]);
                            assertArrayEquals(bytes("1234"), actual[SCRIPT_ARGUMENT_ARRAY_INDEX]);
                            return 1L;
                        });
        assertTrue(redis.compareAndSetStringExact(key, before, after, ttl));
        when(connection.evalSha(anyString(), eq(ReturnType.INTEGER), eq(1), any(byte[][].class)))
                .thenReturn(0L);
        assertFalse(redis.compareAndSetStringExact(key, before, after, ttl));
    }

    @Test
    void consumeUsesOneKeyWithoutArgsAndDecodesTheRedisBulkValueAsUtf8() {
        String key = "robot:test:auth:login-ticket:" + "b".repeat(SECRET_HEX_LENGTH);
        String encoded = "{\"userId\":\"成员乙\"}";
        when(connection.evalSha(anyString(), eq(ReturnType.VALUE), eq(1), any(byte[][].class)))
                .thenAnswer(
                        invocation -> {
                            byte[][] actual =
                                    (byte[][])
                                            invocation
                                                    .getRawArguments()[SCRIPT_ARGUMENT_ARRAY_INDEX];
                            assertEquals(1, actual.length);
                            assertArrayEquals(bytes(key), actual[0]);
                            return bytes(encoded);
                        });
        assertEquals(encoded, redis.consumeString(key).orElseThrow());
        when(connection.evalSha(anyString(), eq(ReturnType.VALUE), eq(1), any(byte[][].class)))
                .thenReturn(null);
        assertTrue(redis.consumeString(key).isEmpty());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
