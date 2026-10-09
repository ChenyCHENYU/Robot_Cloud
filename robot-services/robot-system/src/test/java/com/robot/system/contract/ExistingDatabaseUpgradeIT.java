package com.robot.system.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit mysql-upgrade profile only; every test creates and drops its own loopback-only database. */
class ExistingDatabaseUpgradeIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SNAPSHOT_TABLES = List.of("sys_user", "sys_user_company", "sys_user_role",
            "sys_role", "sys_role_menu", "sys_role_button", "sys_menu", "sys_menu_button", "sys_dict_type", "sys_dict_item");

    @Test
    void populatedV5DatabaseUpgradesToEveryCurrentMigrationWithoutLosingHistoryDataOrConstraints() throws Exception {
        inIsolatedDatabase((database, connection) -> {
            Flyway old = flyway(database, "5");
            old.migrate();
            assertEquals("5", old.info().current().getVersion().toString());
            // A real pre-existing account, membership, role and permanent binding with nonzero CAS versions.
            execute(connection, "INSERT INTO sys_user (id,username,password_hash,nickname,status_flag,create_user_no,create_date_time,delete_flag,revision) VALUES ('usr-upgrade','upgrade_existing','fixture-bcrypt-only','existing nickname',1,'upgrade-fixture','2020-01-02 03:04:05',1,7)");
            execute(connection, "INSERT INTO sys_user_company (id,user_id,company_id,primary_flag,status_flag,create_user_no,create_date_time,delete_flag,revision) VALUES ('ctx-upgrade','usr-upgrade','cmp-robot',1,1,'upgrade-fixture','2020-01-02 03:04:05',1,3)");
            execute(connection, "INSERT INTO sys_role (id,company_id,role_name,role_code,data_scope,sort_no,status_flag,remark,create_user_no,create_date_time,delete_flag,revision) VALUES ('role-upgrade','cmp-robot','Existing role','UPGRADE_EXISTING','self',7,1,'existing remark','upgrade-fixture','2020-01-02 03:04:05',1,4)");
            execute(connection, "INSERT INTO sys_user_role (id,user_id,role_id,company_id,create_user_no,create_date_time,delete_flag,revision) VALUES ('ur-upgrade','usr-upgrade','role-upgrade','cmp-robot','upgrade-fixture','2020-01-02 03:04:05',1,2)");
            Map<String, List<Map<String, Object>>> before = snapshot(connection);
            assertTrue(precheck(connection).isEmpty(), "valid V5 fixture must pass read-only prechecks");
            // Model the previous deployment having queued a real pending V6 event before upgrading V7+.
            flyway(database, "6").migrate();
            execute(connection, "INSERT INTO sys_cache_outbox (id,event_kind,cache_key,cache_version,created_at,next_attempt_at,attempts,last_error) VALUES ('existing-pending','permissions','existing-notification',37,'2020-01-02 03:04:05.123456','2020-01-03 03:04:05.123456',2,'RedisConnectionFailureException')");
            Map<String, Object> pendingBefore = selectedRow(connection, "SELECT * FROM sys_cache_outbox WHERE id='existing-pending'");
            Flyway current = upgradeSafely(database, connection);
            current.validate();
            JsonNode manifest = load("migrations.json");
            List<String> expectedVersions = new ArrayList<>();
            for (JsonNode migration : manifest.path("migrations")) expectedVersions.add(migration.path("version").asText());
            List<String> appliedVersions = new ArrayList<>();
            for (var info : current.info().applied()) {
                assertEquals(MigrationState.SUCCESS, info.getState());
                assertNotNull(info.getChecksum(), "all versioned SQL migrations have a persisted checksum");
                appliedVersions.add(info.getVersion().toString());
            }
            assertEquals(expectedVersions, appliedVersions, "latest migrations must be discovered dynamically, no fixed V9/V10 count");
            assertPreserved(connection, before);
            assertEquals(0L, scalar(connection, "SELECT security_version FROM sys_user WHERE id='usr-upgrade'"));
            assertEquals(0L, scalar(connection, "SELECT security_version FROM sys_user_company WHERE id='ctx-upgrade'"));
            assertEquals("1", text(connection, "SELECT item_code FROM sys_dict_item WHERE id='di-user-status-1'"));
            verifyLiveSchema(connection, database);
            verifyOutboxUpgrade(connection, database, pendingBefore);
            verifyActiveUniquenessAndRepeatDeletedRows(connection);
            verifyRollback(connection);
            assertEquals(0, current.migrate().migrationsExecuted, "second migrate is a no-op");
        });
    }

    @Test
    void activeDuplicatePrecheckStopsBeforeAnyNewDdlAndLeavesExistingDataUntouched() throws Exception {
        inIsolatedDatabase((database, connection) -> {
            Flyway old = flyway(database, "5");
            old.migrate();
            cloneRow(connection, "sys_role", "role-super-admin", "duplicate-before-upgrade", 1);
            assertTrue(precheck(connection).contains("sys_role.company_id.role_code"));
            assertThrows(IllegalStateException.class, () -> upgradeSafely(database, connection));
            assertEquals("5", old.info().current().getVersion().toString());
            assertEquals(0L, tableCount(connection, database, "sys_cache_epoch"), "no V6 DDL before approved duplicate repair");
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM sys_role WHERE id='duplicate-before-upgrade'"));
        });
    }

    @Test
    void populatedDatabaseWithoutHistoryCannotBeSilentlyBaselined() throws Exception {
        inIsolatedDatabase((database, connection) -> {
            execute(connection, "CREATE TABLE unreviewed_existing (id INT PRIMARY KEY)");
            execute(connection, "INSERT INTO unreviewed_existing VALUES (7)");
            assertThrows(FlywayException.class, () -> flyway(database, null).migrate());
            assertEquals(7L, scalar(connection, "SELECT id FROM unreviewed_existing"));
            assertEquals(0L, tableCount(connection, database, "sys_user"));
        });
    }

    private static Flyway upgradeSafely(String database, Connection connection) throws Exception {
        List<String> violations = precheck(connection);
        if (!violations.isEmpty()) throw new IllegalStateException("existing active duplicates require manual repair: " + violations);
        Flyway current = flyway(database, null);
        current.migrate();
        return current;
    }

    private static List<String> precheck(Connection connection) throws Exception {
        List<String> violations = new ArrayList<>();
        for (JsonNode check : load("upgrade-prechecks.json").path("checks")) {
            String sql = check.path("sql").asText();
            assertTrue(sql.startsWith("SELECT ") && !sql.contains(";"), "prechecks must remain read-only single SELECT");
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                if (rows.next()) violations.add(check.path("id").asText());
            }
        }
        return violations;
    }

    private static void verifyLiveSchema(Connection connection, String database) throws Exception {
        var tables = load("user-role-schema.json").path("tables").fields();
        while (tables.hasNext()) {
            var table = tables.next();
            Map<String, Map<String, Object>> actual = new TreeMap<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT column_name,data_type,character_maximum_length,is_nullable,column_default,extra FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ordinal_position")) {
                statement.setString(1, database); statement.setString(2, table.getKey());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> definition = new LinkedHashMap<>();
                        definition.put("type", rows.getString("data_type"));
                        definition.put("nullable", "YES".equals(rows.getString("is_nullable")));
                        definition.put("default", rows.getString("column_default"));
                        definition.put("generated", rows.getString("extra").contains("GENERATED"));
                        if ("varchar".equals(rows.getString("data_type"))) definition.put("length", rows.getInt("character_maximum_length"));
                        actual.put(rows.getString("column_name"), definition);
                    }
                }
            }
            Map<String, JsonNode> expected = new TreeMap<>();
            table.getValue().path("columns").fields().forEachRemaining(column -> {
                var definition = JSON.createObjectNode();
                for (String key : List.of("type", "nullable", "default", "generated", "length")) {
                    if (column.getValue().has(key)) definition.set(key, column.getValue().get(key));
                }
                expected.put(column.getKey(), definition);
            });
            assertEquals(JSON.valueToTree(expected), JSON.valueToTree(actual), "live MySQL column drift: " + table.getKey());
            Map<String, List<String>> indexes = new TreeMap<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT index_name,column_name FROM information_schema.statistics WHERE table_schema=? AND table_name=? AND non_unique=0 ORDER BY index_name,seq_in_index")) {
                statement.setString(1, database); statement.setString(2, table.getKey());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) indexes.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>()).add(rows.getString(2));
                }
            }
            assertEquals(table.getValue().path("uniqueIndexes"), JSON.valueToTree(indexes), "active-only unique index drift: " + table.getKey());
        }
    }

    private static void verifyOutboxUpgrade(Connection connection, String database, Map<String, Object> pendingBefore) throws Exception {
        Map<String, Object> after = selectedRow(connection, "SELECT * FROM sys_cache_outbox WHERE id='existing-pending'");
        for (var column : pendingBefore.entrySet()) assertEquals(column.getValue(), after.get(column.getKey()), "old pending outbox column changed: " + column.getKey());
        assertEquals(0L, ((Number) after.get("delivery_revision")).longValue());
        assertNull(after.get("last_failure_id"));
        assertNull(after.get("completed_at"), "upgrade must not silently complete pending delivery");
        assertEquals(1L, tableCount(connection, database, "sys_cache_outbox_replay_log"));
        java.util.Set<String> replayColumns = new java.util.TreeSet<>();
        try (var statement = connection.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name='sys_cache_outbox_replay_log'")) {
            statement.setString(1, database);
            try (var rows = statement.executeQuery()) { while (rows.next()) replayColumns.add(rows.getString(1)); }
        }
        assertEquals(java.util.Set.of("id", "event_id", "actor_user_id", "actor_company_id", "reason", "previous_revision", "new_revision", "created_at"), replayColumns);
        try (var statement = connection.prepareStatement("SELECT data_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema=? AND table_name='sys_cache_outbox' AND column_name='delivery_revision'")) {
            statement.setString(1, database);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next()); assertEquals("bigint", rows.getString(1)); assertEquals("NO", rows.getString(2)); assertEquals("0", rows.getString(3));
            }
        }
        assertEquals(0L, scalar(connection, "SELECT COUNT(*) FROM sys_cache_outbox_replay_log"), "migration must not invent replay audits");
    }

    private static Map<String, Object> selectedRow(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return row(rows); }
    }

    private static void verifyActiveUniquenessAndRepeatDeletedRows(Connection connection) throws Exception {
        Map<String, String> seed = Map.of("sys_user", "usr-upgrade", "sys_user_company", "ctx-upgrade",
                "sys_role", "role-upgrade", "sys_user_role", "ur-upgrade", "sys_role_menu", "rm-1", "sys_role_button", "rb-sa-1");
        for (var entry : seed.entrySet()) {
            SQLException conflict = assertThrows(SQLException.class, () -> cloneRow(connection, entry.getKey(), entry.getValue(), "active-duplicate-" + entry.getKey(), 1));
            assertEquals(1062, conflict.getErrorCode(), "must reject business duplicate, not some other SQL failure");
            cloneRow(connection, entry.getKey(), entry.getValue(), "deleted-one-" + entry.getKey(), 0);
            cloneRow(connection, entry.getKey(), entry.getValue(), "deleted-two-" + entry.getKey(), 0);
            assertEquals(2L, scalar(connection, "SELECT COUNT(*) FROM " + entry.getKey() + " WHERE id IN ('deleted-one-" + entry.getKey() + "','deleted-two-" + entry.getKey() + "') AND delete_flag=0"));
        }
    }

    private static void verifyRollback(Connection connection) throws Exception {
        long revision = scalar(connection, "SELECT revision FROM sys_user WHERE id='usr-upgrade'");
        long member = scalar(connection, "SELECT revision FROM sys_user_company WHERE id='ctx-upgrade'");
        long epoch = scalar(connection, "SELECT version FROM sys_cache_epoch WHERE scope='permissions'");
        connection.setAutoCommit(false);
        try {
            execute(connection, "UPDATE sys_user SET nickname='must rollback',revision=revision+1 WHERE id='usr-upgrade'");
            execute(connection, "UPDATE sys_user_company SET revision=revision+1 WHERE id='ctx-upgrade'");
            execute(connection, "UPDATE sys_cache_epoch SET version=version+1 WHERE scope='permissions'");
            execute(connection, "INSERT INTO sys_cache_outbox (id,event_kind,cache_key,cache_version,created_at,next_attempt_at) VALUES ('rollback-fixture','permissions','offline-test',99,NOW(6),NOW(6))");
            connection.rollback();
        } finally {
            connection.setAutoCommit(true);
        }
        assertEquals("existing nickname", text(connection, "SELECT nickname FROM sys_user WHERE id='usr-upgrade'"));
        assertEquals(revision, scalar(connection, "SELECT revision FROM sys_user WHERE id='usr-upgrade'"));
        assertEquals(member, scalar(connection, "SELECT revision FROM sys_user_company WHERE id='ctx-upgrade'"));
        assertEquals(epoch, scalar(connection, "SELECT version FROM sys_cache_epoch WHERE scope='permissions'"));
        assertEquals(0L, scalar(connection, "SELECT COUNT(*) FROM sys_cache_outbox WHERE id='rollback-fixture'"));
    }

    private static void cloneRow(Connection connection, String table, String originalId, String newId, int deleteFlag) throws Exception {
        List<String> columns = new ArrayList<>();
        var definitions = load("user-role-schema.json").path("tables").path(table).path("columns");
        if (definitions.isEmpty()) throw new IllegalArgumentException("table outside reviewed user/role contract");
        // Query live V5/Vlatest columns; generated columns must never be explicitly inserted.
        try (var statement = connection.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND extra NOT LIKE '%GENERATED%' ORDER BY ordinal_position")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) { while (rows.next()) columns.add(rows.getString(1)); }
        }
        String projection = String.join(",", columns.stream().map(column -> "id".equals(column) || "delete_flag".equals(column) ? "?" : column).toList());
        try (var statement = connection.prepareStatement("INSERT INTO " + table + " (" + String.join(",", columns) + ") SELECT " + projection + " FROM " + table + " WHERE id=?")) {
            int index = 1;
            for (String column : columns) {
                if ("id".equals(column)) statement.setString(index++, newId);
                else if ("delete_flag".equals(column)) statement.setInt(index++, deleteFlag);
            }
            statement.setString(index, originalId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static Map<String, List<Map<String, Object>>> snapshot(Connection connection) throws Exception {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : SNAPSHOT_TABLES) {
            List<Map<String, Object>> values = new ArrayList<>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
                while (rows.next()) values.add(row(rows));
            }
            snapshot.put(table, values);
        }
        return snapshot;
    }

    private static void assertPreserved(Connection connection, Map<String, List<Map<String, Object>>> snapshot) throws Exception {
        for (var table : snapshot.entrySet()) {
            for (var previous : table.getValue()) {
                try (var statement = connection.prepareStatement("SELECT * FROM " + table.getKey() + " WHERE id=?")) {
                    statement.setObject(1, previous.get("id"));
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next(), "existing row lost: " + table.getKey() + "/" + previous.get("id"));
                        Map<String, Object> after = row(rows);
                        for (var column : previous.entrySet()) assertEquals(column.getValue(), after.get(column.getKey()), table.getKey() + "/" + previous.get("id") + "/" + column.getKey());
                    }
                }
            }
        }
    }

    private static Map<String, Object> row(ResultSet rows) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) value.put(rows.getMetaData().getColumnLabel(column), rows.getObject(column));
        return value;
    }

    private static void inIsolatedDatabase(DatabaseCheck check) throws Exception {
        assertEquals("true", System.getProperty("robot.verify.mysql.enabled"), "only the explicit mysql-upgrade profile enables this test");
        String database = "robot_verify_" + UUID.randomUUID().toString().replace("-", "");
        if (!database.matches("robot_verify_[0-9a-f]{32}")) throw new IllegalStateException("invalid generated isolation name");
        try (Connection admin = connection(null)) {
            execute(admin, "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            try (Connection isolated = connection(database)) {
                check.run(database, isolated);
            } finally {
                execute(admin, "DROP DATABASE " + database);
            }
        }
    }

    private static Flyway flyway(String database, String target) {
        var configuration = Flyway.configure().dataSource(url(database), user(), password())
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static Connection connection(String database) throws SQLException {
        return DriverManager.getConnection(url(database), user(), password());
    }

    private static String url(String database) {
        String host = System.getenv().getOrDefault("ROBOT_VERIFY_MYSQL_HOST", "127.0.0.1");
        if (!List.of("127.0.0.1", "localhost", "::1").contains(host)) throw new IllegalArgumentException("loopback MySQL host required");
        int port = Integer.parseInt(System.getenv().getOrDefault("ROBOT_VERIFY_MYSQL_PORT", "3306"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid MySQL port");
        return "jdbc:mysql://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port + "/" + (database == null ? "" : database)
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=UTF-8";
    }

    private static String user() {
        String username = System.getenv("ROBOT_VERIFY_MYSQL_USER");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("ROBOT_VERIFY_MYSQL_USER required");
        return username;
    }

    private static String password() { return System.getenv().getOrDefault("ROBOT_VERIFY_MYSQL_PASSWORD", ""); }
    private static void execute(Connection connection, String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.executeUpdate(sql); } }
    private static long scalar(Connection connection, String sql) throws SQLException { try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getLong(1); } }
    private static String text(Connection connection, String sql) throws SQLException { try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getString(1); } }
    private static long tableCount(Connection connection, String database, String table) throws SQLException { try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?")) { statement.setString(1, database); statement.setString(2, table); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); } } }
    private static JsonNode load(String resource) throws Exception { try (InputStream stream = ExistingDatabaseUpgradeIT.class.getResourceAsStream("/contracts/database/" + resource)) { assertNotNull(stream); return JSON.readTree(stream); } }
    @FunctionalInterface private interface DatabaseCheck { void run(String database, Connection connection) throws Exception; }
}
