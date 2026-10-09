package com.robot.common.redis.core;

import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 对真实 serializer 直接往返，不打开 Redis 连接。 */
class RobotRedisSerializationTest {
    @Test
    void robotPermissionCacheAndDictionaryContainersRoundtripThroughConfiguredSerializer() {
        RedisConnectionFactory factory=mock(RedisConnectionFactory.class);
        RedisTemplate<String,Object> template=new RobotRedisAutoConfiguration().robotRedisTemplate(factory);
        RedisSerializer<Object> serializer=serializer(template);
        TestPermissionCache permissions=new TestPermissionCache();
        permissions.superAdmin=false;permissions.permissions=new ArrayList<>(List.of("sys:user:list","sys:user:save"));
        permissions.validUntil=LocalDateTime.of(2026,10,8,12,0,0);
        TestPermissionCache restored=(TestPermissionCache)serializer.deserialize(serializer.serialize(permissions));
        assertThat(restored.permissions).containsExactlyElementsOf(permissions.permissions);
        assertThat(restored.permissions).isInstanceOf(ArrayList.class);
        assertThat(restored.validUntil).isEqualTo(permissions.validUntil);
        assertThat(restored.superAdmin).isFalse();

        HashMap<String,Object> item=new HashMap<>();
        item.put("id","opaque");item.put("value","enabled");item.put("sort",1);
        ArrayList<HashMap<String,Object>> dict=new ArrayList<>();dict.add(item);
        Object dictionary=serializer.deserialize(serializer.serialize(dict));
        assertThat(dictionary).isInstanceOf(ArrayList.class).isEqualTo(dict);
        assertThat(((List<?>)dictionary).get(0)).isInstanceOf(HashMap.class);
        HashMap<String,Object> payload=new HashMap<>();payload.put("items",dict);
        assertThat(serializer.deserialize(serializer.serialize(payload))).isEqualTo(payload);
        StringRedisSerializer keys=(StringRedisSerializer)template.getKeySerializer();
        assertThat(keys.deserialize(keys.serialize("robot:test:key")))
                .isEqualTo("robot:test:key");
        verifyNoInteractions(factory);
    }

    @Test
    void localDatetimeInsideGenericDictionaryObjectRoundtrips() {
        RedisConnectionFactory factory=mock(RedisConnectionFactory.class);
        RedisSerializer<Object> serializer=serializer(new RobotRedisAutoConfiguration().robotRedisTemplate(factory));
        HashMap<String,Object> entry=new HashMap<>();
        entry.put("updatedAt",LocalDateTime.of(2026,10,8,12,0,0));
        assertThat(serializer.deserialize(serializer.serialize(entry))).isEqualTo(entry);
        verifyNoInteractions(factory);
    }

    @Test
    void thirdPartyTypeInjectedAtRootOrWithinAllowedContainerIsDenied() {
        RedisConnectionFactory factory=mock(RedisConnectionFactory.class);
        RedisSerializer<Object> serializer=serializer(new RobotRedisAutoConfiguration().robotRedisTemplate(factory));
        for(String json:List.of("{\"@class\":\"java.io.File\",\"path\":\"/tmp/should-never-open\"}",
                "{\"@class\":\"java.util.HashMap\",\"payload\":{\"@class\":\"java.io.File\",\"path\":\"/tmp/should-never-open\"}}")) {
            assertThatThrownBy(() -> serializer.deserialize(json.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(SerializationException.class).hasRootCauseInstanceOf(InvalidTypeIdException.class);
        }
        verifyNoInteractions(factory);
    }

    @SuppressWarnings("unchecked")
    private static RedisSerializer<Object> serializer(RedisTemplate<String,Object> template) {
        return (RedisSerializer<Object>)template.getValueSerializer();
    }

    public static class TestPermissionCache {
        public boolean superAdmin;
        public List<String> permissions;
        public LocalDateTime validUntil;
    }
}
