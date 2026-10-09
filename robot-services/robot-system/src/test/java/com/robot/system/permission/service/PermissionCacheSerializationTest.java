package com.robot.system.permission.service;

import com.robot.common.redis.core.RobotRedisAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 真实 system 缓存类型，而不是手写等价模型。 */
class PermissionCacheSerializationTest {
    @Test
    @SuppressWarnings("unchecked")
    void actualCachedPermissionsRoundtripsWithConcreteListAndExpiry() {
        RedisConnectionFactory factory=mock(RedisConnectionFactory.class);
        RedisSerializer<Object> serializer=(RedisSerializer<Object>)new RobotRedisAutoConfiguration()
                .robotRedisTemplate(factory).getValueSerializer();
        RobotStpInterface.CachedPermissions source=new RobotStpInterface.CachedPermissions(false,List.of("sys:user:list"));
        source.validUntil=LocalDateTime.of(2026,10,8,12,0,0);
        Object decoded=serializer.deserialize(serializer.serialize(source));
        assertThat(decoded).isInstanceOf(RobotStpInterface.CachedPermissions.class);
        RobotStpInterface.CachedPermissions cache=(RobotStpInterface.CachedPermissions)decoded;
        assertThat(cache.permissions).isInstanceOf(ArrayList.class).containsExactly("sys:user:list");
        assertThat(cache.validUntil).isEqualTo(source.validUntil);
        assertThat(cache.superAdmin).isFalse();
        verifyNoInteractions(factory);
    }
}
