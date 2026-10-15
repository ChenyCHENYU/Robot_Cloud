package com.robot.system.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Offline entity/schema contract gate; full column/index verification is in the real MySQL upgrade
 * profile.
 */
class MachineDatabaseContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void completeUserRoleAggregateContractMatchesEntityColumnsTypesAndGovernance()
            throws Exception {
        verify(load());
    }

    @Test
    void gateRejectsColumnAndTypeDrift() throws Exception {
        ObjectNode missing = load().deepCopy();
        ((ObjectNode) missing.path("tables").path("sys_user").path("columns"))
                .remove("security_version");
        assertThrows(AssertionError.class, () -> verify(missing));
        ObjectNode changed = load().deepCopy();
        ((ObjectNode) changed.path("tables").path("sys_user").path("columns").path("username"))
                .put("type", "bigint");
        assertThrows(AssertionError.class, () -> verify(changed));
    }

    private void verify(JsonNode contract) throws Exception {
        var tables = contract.path("tables").fields();
        assertTrue(tables.hasNext(), "empty schema contract is not a gate");
        while (tables.hasNext()) {
            var table = tables.next();
            verifyTable(table.getKey(), table.getValue());
        }
    }

    private void verifyTable(String tableName, JsonNode definition) throws Exception {
        Class<?> entity = Class.forName(definition.path("entity").asText());
        assertEquals(tableName, entity.getAnnotation(TableName.class).value());
        Map<String, Field> fields = mappedFields(entity);
        TreeSet<String> expected = new TreeSet<>();
        var columns = definition.path("columns").fields();
        while (columns.hasNext()) {
            var column = columns.next();
            for (String semantic :
                    java.util.List.of(
                            "semanticId", "sourceOfTruth", "constraintSource", "definition")) {
                assertFalse(
                        column.getValue().path(semantic).asText().isBlank(),
                        "missing database semantic: " + column.getKey());
            }
            if (column.getValue().path("generated").asBoolean()) {
                continue;
            }
            expected.add(column.getKey());
            Field javaField = fields.get(column.getKey());
            assertNotNull(
                    javaField,
                    "entity missing contracted column " + tableName + "." + column.getKey());
            Class<?> expectedType = sqlJavaType(column.getValue().path("type").asText());
            assertEquals(
                    expectedType, javaField.getType(), "entity SQL type drift " + column.getKey());
        }
        assertEquals(expected, fields.keySet(), "complete mapped column set drift: " + tableName);
        assertTrue(
                fields.get("revision").isAnnotationPresent(Version.class),
                "revision must keep MyBatis CAS semantics");
        assertTrue(
                fields.get("id").isAnnotationPresent(TableId.class),
                "opaque String primary key required");
        assertEquals("1", definition.path("columns").path("delete_flag").path("default").asText());
        assertEquals("0", definition.path("columns").path("revision").path("default").asText());
    }

    private static Class<?> sqlJavaType(String sqlType) {
        return switch (sqlType) {
            case "varchar" -> String.class;
            case "int", "tinyint" -> Integer.class;
            case "bigint" -> Long.class;
            case "datetime" -> LocalDateTime.class;
            default -> throw new IllegalStateException("unmodeled SQL type");
        };
    }

    private Map<String, Field> mappedFields(Class<?> entity) {
        Map<String, Field> result = new TreeMap<>();
        for (Class<?> type = entity;
                type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                String column = columnName(field);
                if (column == null) {
                    continue;
                }
                result.put(column, field);
            }
        }
        return result;
    }

    private static String columnName(Field field) {
        if (Modifier.isStatic(field.getModifiers())) {
            return null;
        }
        TableField mapping = field.getAnnotation(TableField.class);
        if (mapping != null && !mapping.exist()) {
            return null;
        }
        String column =
                field.getName()
                        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                        .toLowerCase(java.util.Locale.ROOT);
        if (mapping != null && !mapping.value().isBlank()) {
            column = mapping.value();
        }
        return column;
    }

    private JsonNode load() throws Exception {
        try (InputStream stream =
                getClass().getResourceAsStream("/contracts/database/user-role-schema.json")) {
            assertNotNull(stream);
            return mapper.readTree(stream);
        }
    }
}
