package com.robot.system.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic public-model values and Jackson's actual readable/writable property types. */
final class ContractModelFixtures {
    private static final int SAMPLE_NUMBER = 3;
    private static final long SAMPLE_LONG = 7L;
    private static final Map<Class<?>, Object> SAMPLE_VALUES =
            Map.ofEntries(
                    Map.entry(String.class, "9007199254740993"),
                    Map.entry(Integer.class, SAMPLE_NUMBER),
                    Map.entry(int.class, SAMPLE_NUMBER),
                    Map.entry(Long.class, SAMPLE_LONG),
                    Map.entry(long.class, SAMPLE_LONG),
                    Map.entry(Boolean.class, true),
                    Map.entry(boolean.class, true),
                    Map.entry(LocalDateTime.class, LocalDateTime.parse("2026-10-09T12:34:56")),
                    Map.entry(List.class, List.of("resource-id")));
    private final ObjectMapper mapper;

    ContractModelFixtures(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Map<String, String> propertyTypes(Class<?> type, boolean serialize) {
        var javaType = mapper.constructType(type);
        var description =
                serialize
                        ? mapper.getSerializationConfig().introspect(javaType)
                        : mapper.getDeserializationConfig().introspect(javaType);
        Map<String, String> properties = new TreeMap<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            if (serialize ? property.couldSerialize() : property.couldDeserialize()) {
                properties.put(property.getName(), simpleJavaType(property.getPrimaryType()));
            }
        }
        return properties;
    }

    Object sampleBean(Class<?> type) throws Exception {
        if (type.isRecord()) {
            var components = type.getRecordComponents();
            Constructor<?> constructor =
                    type.getDeclaredConstructor(
                            Arrays.stream(components)
                                    .map(java.lang.reflect.RecordComponent::getType)
                                    .toArray(Class[]::new));
            return constructor.newInstance(
                    Arrays.stream(components).map(c -> sample(c.getType())).toArray());
        }
        Object bean;
        try {
            bean = type.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException absentNoArgs) {
            Constructor<?> constructor =
                    Arrays.stream(type.getDeclaredConstructors())
                            .max(java.util.Comparator.comparingInt(Constructor::getParameterCount))
                            .orElseThrow();
            constructor.setAccessible(true);
            bean =
                    constructor.newInstance(
                            Arrays.stream(constructor.getParameterTypes())
                                    .map(ContractModelFixtures::sample)
                                    .toArray());
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            field.set(bean, sample(field.getType()));
        }
        return bean;
    }

    private static Object sample(Class<?> type) {
        Object value = SAMPLE_VALUES.get(type);
        if (value == null) {
            throw new IllegalArgumentException("unmodeled public type: " + type);
        }
        return value;
    }

    private String simpleJavaType(com.fasterxml.jackson.databind.JavaType type) {
        if (type.containedTypeCount() == 0) {
            return type.getRawClass().getSimpleName();
        }
        List<String> arguments = new ArrayList<>();
        for (int index = 0; index < type.containedTypeCount(); index++) {
            arguments.add(simpleJavaType(type.containedType(index)));
        }
        return type.getRawClass().getSimpleName() + "<" + String.join(",", arguments) + ">";
    }
}
