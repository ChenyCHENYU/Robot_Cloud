package com.robot.system.contract;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Offline entity/schema contract gate; full column/index verification is in the real MySQL upgrade profile. */
class MachineDatabaseContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void completeUserRoleAggregateContractMatchesEntityColumnsTypesAndGovernance() throws Exception {
        verify(load());
    }

    @Test
    void gateRejectsColumnAndTypeDrift() throws Exception {
        ObjectNode missing = load().deepCopy();
        ((ObjectNode) missing.path("tables").path("sys_user").path("columns")).remove("security_version");
        assertThrows(AssertionError.class, () -> verify(missing));
        ObjectNode changed = load().deepCopy();
        ((ObjectNode) changed.path("tables").path("sys_user").path("columns").path("username")).put("type", "bigint");
        assertThrows(AssertionError.class, () -> verify(changed));
    }

    private void verify(JsonNode contract) throws Exception {
        var tables = contract.path("tables").fields();
        assertTrue(tables.hasNext(), "empty schema contract is not a gate");
        while (tables.hasNext()) {
            var table = tables.next();
            Class<?> entity = Class.forName(table.getValue().path("entity").asText());
            assertEquals(table.getKey(), entity.getAnnotation(TableName.class).value());
            Map<String, Field> fields = mappedFields(entity);
            TreeSet<String> expected = new TreeSet<>();
            var columns = table.getValue().path("columns").fields();
            while (columns.hasNext()) {
                var column = columns.next();
                for (String semantic : java.util.List.of("semanticId", "sourceOfTruth", "constraintSource", "definition")) {
                    assertFalse(column.getValue().path(semantic).asText().isBlank(), "missing database semantic: " + column.getKey());
                }
                if (column.getValue().path("generated").asBoolean()) continue;
                expected.add(column.getKey());
                Field javaField = fields.get(column.getKey());
                assertNotNull(javaField, "entity missing contracted column " + table.getKey() + "." + column.getKey());
                Class<?> expectedType = switch (column.getValue().path("type").asText()) {
                    case "varchar" -> String.class;
                    case "int", "tinyint" -> Integer.class;
                    case "bigint" -> Long.class;
                    case "datetime" -> LocalDateTime.class;
                    default -> throw new IllegalStateException("unmodeled SQL type");
                };
                assertEquals(expectedType, javaField.getType(), "entity SQL type drift " + column.getKey());
            }
            assertEquals(expected, fields.keySet(), "complete mapped column set drift: " + table.getKey());
            assertTrue(fields.get("revision").isAnnotationPresent(Version.class), "revision must keep MyBatis CAS semantics");
            assertTrue(fields.get("id").isAnnotationPresent(TableId.class), "opaque String primary key required");
            assertEquals("1", table.getValue().path("columns").path("delete_flag").path("default").asText());
            assertEquals("0", table.getValue().path("columns").path("revision").path("default").asText());
        }
    }

    private Map<String, Field> mappedFields(Class<?> entity) {
        Map<String, Field> result = new TreeMap<>();
        for (Class<?> type = entity; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                TableField mapping = field.getAnnotation(TableField.class);
                if (mapping != null && !mapping.exist()) continue;
                String column = field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
                if (mapping != null && !mapping.value().isBlank()) column = mapping.value();
                result.put(column, field);
            }
        }
        return result;
    }

    private JsonNode load() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/contracts/database/user-role-schema.json")) {
            assertNotNull(stream);
            return mapper.readTree(stream);
        }
    }
}
