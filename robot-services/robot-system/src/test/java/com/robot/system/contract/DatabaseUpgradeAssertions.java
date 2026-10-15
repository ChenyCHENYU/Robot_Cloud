package com.robot.system.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 真实MySQL升级后的结构、历史、唯一性和回滚断言，与临时数据库生命周期分离。 */
final class DatabaseUpgradeAssertions {
    private static final int DEFAULT_VALUE_COLUMN = 3;
    private static final int MYSQL_DUPLICATE_KEY = 1062;
    private static final ObjectMapper JSON = new ObjectMapper();

    private DatabaseUpgradeAssertions() {}

    static void verifyLiveSchema(Connection connection, String database) throws Exception {
        var tables = load("user-role-schema.json").path("tables").fields();
        while (tables.hasNext()) {
            var table = tables.next();
            assertEquals(
                    JSON.valueToTree(expectedColumns(table.getValue())),
                    JSON.valueToTree(actualColumns(connection, database, table.getKey())),
                    "live MySQL column drift: " + table.getKey());
            assertEquals(
                    table.getValue().path("uniqueIndexes"),
                    JSON.valueToTree(actualUniqueIndexes(connection, database, table.getKey())),
                    "active-only unique index drift: " + table.getKey());
        }
    }

    private static Map<String, Map<String, Object>> actualColumns(
            Connection connection, String database, String table) throws SQLException {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT"
                                + " column_name,data_type,character_maximum_length,is_nullable,column_default,extra"
                                + " FROM information_schema.columns WHERE table_schema=? AND"
                                + " table_name=? ORDER BY ordinal_position")) {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put(rows.getString("column_name"), columnDefinition(rows));
                }
            }
        }
        return result;
    }

    private static Map<String, Object> columnDefinition(ResultSet rows) throws SQLException {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", rows.getString("data_type"));
        definition.put("nullable", "YES".equals(rows.getString("is_nullable")));
        definition.put("default", rows.getString("column_default"));
        definition.put("generated", rows.getString("extra").contains("GENERATED"));
        if ("varchar".equals(rows.getString("data_type"))) {
            definition.put("length", rows.getInt("character_maximum_length"));
        }
        return definition;
    }

    private static Map<String, JsonNode> expectedColumns(JsonNode table) {
        Map<String, JsonNode> expected = new TreeMap<>();
        table.path("columns")
                .fields()
                .forEachRemaining(
                        column -> {
                            var definition = JSON.createObjectNode();
                            for (String key :
                                    List.of("type", "nullable", "default", "generated", "length")) {
                                if (column.getValue().has(key)) {
                                    definition.set(key, column.getValue().get(key));
                                }
                            }
                            expected.put(column.getKey(), definition);
                        });
        return expected;
    }

    private static Map<String, List<String>> actualUniqueIndexes(
            Connection connection, String database, String table) throws SQLException {
        Map<String, List<String>> indexes = new TreeMap<>();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT index_name,column_name FROM information_schema.statistics WHERE"
                                + " table_schema=? AND table_name=? AND non_unique=0 ORDER BY"
                                + " index_name,seq_in_index")) {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    appendIndexColumn(indexes, rows.getString(1), rows.getString(2));
                }
            }
        }
        return indexes;
    }

    private static void appendIndexColumn(
            Map<String, List<String>> indexes, String index, String column) {
        indexes.computeIfAbsent(index, ignored -> new ArrayList<>()).add(column);
    }

    static void verifyOutboxUpgrade(
            Connection connection, String database, Map<String, Object> pendingBefore)
            throws Exception {
        Map<String, Object> after =
                selectedRow(
                        connection, "SELECT * FROM sys_cache_outbox WHERE id='existing-pending'");
        for (var column : pendingBefore.entrySet()) {
            assertEquals(
                    column.getValue(),
                    after.get(column.getKey()),
                    "old pending outbox column changed: " + column.getKey());
        }
        assertEquals(0L, ((Number) after.get("delivery_revision")).longValue());
        assertNull(after.get("last_failure_id"));
        assertNull(
                after.get("completed_at"), "upgrade must not silently complete pending delivery");
        assertEquals(1L, tableCount(connection, database, "sys_cache_outbox_replay_log"));
        java.util.Set<String> replayColumns = new java.util.TreeSet<>();
        try (var statement =
                connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema=?"
                                + " AND table_name='sys_cache_outbox_replay_log'")) {
            statement.setString(1, database);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    replayColumns.add(rows.getString(1));
                }
            }
        }
        assertEquals(
                java.util.Set.of(
                        "id",
                        "event_id",
                        "actor_user_id",
                        "actor_company_id",
                        "reason",
                        "previous_revision",
                        "new_revision",
                        "created_at"),
                replayColumns);
        try (var statement =
                connection.prepareStatement(
                        "SELECT data_type,is_nullable,column_default FROM"
                                + " information_schema.columns WHERE table_schema=? AND"
                                + " table_name='sys_cache_outbox' AND"
                                + " column_name='delivery_revision'")) {
            statement.setString(1, database);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("bigint", rows.getString(1));
                assertEquals("NO", rows.getString(2));
                assertEquals("0", rows.getString(DEFAULT_VALUE_COLUMN));
            }
        }
        assertEquals(
                0L,
                scalar(connection, "SELECT COUNT(*) FROM sys_cache_outbox_replay_log"),
                "migration must not invent replay audits");
    }

    static Map<String, Object> selectedRow(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return row(rows);
        }
    }

    static void verifyActiveUniquenessAndRepeatDeletedRows(Connection connection) throws Exception {
        Map<String, String> seed =
                Map.of(
                        "sys_user",
                        "usr-upgrade",
                        "sys_user_company",
                        "ctx-upgrade",
                        "sys_role",
                        "role-upgrade",
                        "sys_user_role",
                        "ur-upgrade",
                        "sys_role_menu",
                        "rm-1",
                        "sys_role_button",
                        "rb-sa-1");
        for (var entry : seed.entrySet()) {
            SQLException conflict =
                    assertThrows(
                            SQLException.class,
                            () ->
                                    cloneRow(
                                            connection,
                                            entry.getKey(),
                                            entry.getValue(),
                                            "active-duplicate-" + entry.getKey(),
                                            1));
            assertEquals(
                    MYSQL_DUPLICATE_KEY,
                    conflict.getErrorCode(),
                    "must reject business duplicate, not some other SQL failure");
            cloneRow(
                    connection,
                    entry.getKey(),
                    entry.getValue(),
                    "deleted-one-" + entry.getKey(),
                    0);
            cloneRow(
                    connection,
                    entry.getKey(),
                    entry.getValue(),
                    "deleted-two-" + entry.getKey(),
                    0);
            assertEquals(
                    2L,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM "
                                    + entry.getKey()
                                    + " WHERE id IN ('deleted-one-"
                                    + entry.getKey()
                                    + "','deleted-two-"
                                    + entry.getKey()
                                    + "') AND delete_flag=0"));
        }
    }

    static void verifyRollback(Connection connection) throws Exception {
        long revision = scalar(connection, "SELECT revision FROM sys_user WHERE id='usr-upgrade'");
        long member =
                scalar(connection, "SELECT revision FROM sys_user_company WHERE id='ctx-upgrade'");
        long epoch =
                scalar(connection, "SELECT version FROM sys_cache_epoch WHERE scope='permissions'");
        connection.setAutoCommit(false);
        try {
            execute(
                    connection,
                    "UPDATE sys_user SET nickname='must rollback',revision=revision+1 WHERE"
                            + " id='usr-upgrade'");
            execute(
                    connection,
                    "UPDATE sys_user_company SET revision=revision+1 WHERE id='ctx-upgrade'");
            execute(
                    connection,
                    "UPDATE sys_cache_epoch SET version=version+1 WHERE scope='permissions'");
            execute(
                    connection,
                    "INSERT INTO sys_cache_outbox"
                            + " (id,event_kind,cache_key,cache_version,created_at,next_attempt_at)"
                            + " VALUES"
                            + " ('rollback-fixture','permissions','offline-test',99,NOW(6),NOW(6))");
            connection.rollback();
        } finally {
            connection.setAutoCommit(true);
        }
        assertEquals(
                "existing nickname",
                text(connection, "SELECT nickname FROM sys_user WHERE id='usr-upgrade'"));
        assertEquals(
                revision,
                scalar(connection, "SELECT revision FROM sys_user WHERE id='usr-upgrade'"));
        assertEquals(
                member,
                scalar(connection, "SELECT revision FROM sys_user_company WHERE id='ctx-upgrade'"));
        assertEquals(
                epoch,
                scalar(
                        connection,
                        "SELECT version FROM sys_cache_epoch WHERE scope='permissions'"));
        assertEquals(
                0L,
                scalar(
                        connection,
                        "SELECT COUNT(*) FROM sys_cache_outbox WHERE id='rollback-fixture'"));
    }

    static void cloneRow(
            Connection connection, String table, String originalId, String newId, int deleteFlag)
            throws Exception {
        List<String> columns = new ArrayList<>();
        var definitions = load("user-role-schema.json").path("tables").path(table).path("columns");
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("table outside reviewed user/role contract");
        }
        // Query live V5/Vlatest columns; generated columns must never be explicitly inserted.
        try (var statement =
                connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns WHERE"
                                + " table_schema=DATABASE() AND table_name=? AND extra NOT LIKE"
                                + " '%GENERATED%' ORDER BY ordinal_position")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        String projection =
                String.join(
                        ",",
                        columns.stream()
                                .map(
                                        column ->
                                                "id".equals(column) || "delete_flag".equals(column)
                                                        ? "?"
                                                        : column)
                                .toList());
        try (var statement =
                connection.prepareStatement(
                        "INSERT INTO "
                                + table
                                + " ("
                                + String.join(",", columns)
                                + ") SELECT "
                                + projection
                                + " FROM "
                                + table
                                + " WHERE id=?")) {
            int index = 1;
            for (String column : columns) {
                if ("id".equals(column)) {
                    statement.setString(index++, newId);
                } else {
                    if ("delete_flag".equals(column)) {
                        statement.setInt(index++, deleteFlag);
                    }
                }
            }
            statement.setString(index, originalId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    static Map<String, Object> row(ResultSet rows) throws SQLException {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
            value.put(rows.getMetaData().getColumnLabel(column), rows.getObject(column));
        }
        return value;
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    static long scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    static String text(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    static long tableCount(Connection connection, String database, String table)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND"
                                + " table_name=?")) {
            statement.setString(1, database);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    static JsonNode load(String resource) throws Exception {
        try (InputStream stream =
                ExistingDatabaseUpgradeIT.class.getResourceAsStream(
                        "/contracts/database/" + resource)) {
            assertNotNull(stream);
            return JSON.readTree(stream);
        }
    }
}
