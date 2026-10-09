package com.robot.auth.authn.service;

import com.robot.common.redis.core.RedisHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 Spring 序列化与命令边界，连接为内存替身；不执行 Lua、不连接 Redis。 */
@SuppressWarnings("deprecation") // Spring Data Redis 3.2 的默认脚本执行器使用 RedisConnection.evalSha。
class AuthRedisTransportTest {

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
        String key = "robot:test:auth:family:" + "a".repeat(64);
        String before = "{\"userId\":\"成员甲\",\"version\":0}";
        String after = "{\"userId\":\"成员甲\",\"version\":1}";
        Duration ttl = Duration.ofMillis(1234);
        when(connection.evalSha(anyString(), eq(ReturnType.INTEGER), eq(1), any(byte[][].class)))
                .thenAnswer(invocation -> {
                    byte[][] actual = (byte[][]) invocation.getRawArguments()[3];
                    assertEquals(4, actual.length);
                    assertArrayEquals(bytes(key), actual[0]);
                    assertArrayEquals(bytes(before), actual[1]);
                    assertArrayEquals(bytes(after), actual[2]);
                    assertArrayEquals(bytes("1234"), actual[3]);
                    return 1L;
                });
        assertTrue(redis.compareAndSetStringExact(key, before, after, ttl));
        when(connection.evalSha(anyString(), eq(ReturnType.INTEGER), eq(1), any(byte[][].class)))
                .thenReturn(0L);
        assertFalse(redis.compareAndSetStringExact(key, before, after, ttl));
    }

    @Test
    void consumeUsesOneKeyWithoutArgsAndDecodesTheRedisBulkValueAsUtf8() {
        String key = "robot:test:auth:login-ticket:" + "b".repeat(64);
        String encoded = "{\"userId\":\"成员乙\"}";
        when(connection.evalSha(anyString(), eq(ReturnType.VALUE), eq(1), any(byte[][].class)))
                .thenAnswer(invocation -> {
                    byte[][] actual = (byte[][]) invocation.getRawArguments()[3];
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
