package com.robot.system.contract;

import static com.robot.system.contract.DatabaseUpgradeAssertions.cloneRow;
import static com.robot.system.contract.DatabaseUpgradeAssertions.execute;
import static com.robot.system.contract.DatabaseUpgradeAssertions.load;
import static com.robot.system.contract.DatabaseUpgradeAssertions.row;
import static com.robot.system.contract.DatabaseUpgradeAssertions.scalar;
import static com.robot.system.contract.DatabaseUpgradeAssertions.selectedRow;
import static com.robot.system.contract.DatabaseUpgradeAssertions.tableCount;
import static com.robot.system.contract.DatabaseUpgradeAssertions.text;
import static com.robot.system.contract.DatabaseUpgradeAssertions.verifyActiveUniquenessAndRepeatDeletedRows;
import static com.robot.system.contract.DatabaseUpgradeAssertions.verifyLiveSchema;
import static com.robot.system.contract.DatabaseUpgradeAssertions.verifyOutboxUpgrade;
import static com.robot.system.contract.DatabaseUpgradeAssertions.verifyRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

/**
 * Explicit mysql-upgrade profile only; every test creates and drops its own loopback-only database.
 */
class ExistingDatabaseUpgradeIT {
    private static final long UNREVIEWED_EXISTING_ID = 7L;
    private static final int LEGACY_ACCOUNT_REVISION = 7;
    private static final int LEGACY_MEMBER_REVISION = 3;
    private static final int MAX_TCP_PORT = 65535;
    private static final List<String> SNAPSHOT_TABLES =
            List.of(
                    "sys_user",
                    "sys_user_company",
                    "sys_user_role",
                    "sys_role",
                    "sys_role_menu",
                    "sys_role_button",
                    "sys_menu",
                    "sys_menu_button",
                    "sys_dict_type",
                    "sys_dict_item");

    @Test
    void populatedV5DatabaseUpgradesToEveryCurrentMigrationWithoutLosingHistoryDataOrConstraints()
            throws Exception {
        inIsolatedDatabase(
                (database, connection) -> {
                    Flyway old = flyway(database, "5");
                    old.migrate();
                    assertEquals("5", old.info().current().getVersion().toString());
                    seedExistingV5Aggregate(connection);
                    Map<String, List<Map<String, Object>>> before = snapshot(connection);
                    assertTrue(
                            precheck(connection).isEmpty(),
                            "valid V5 fixture must pass read-only prechecks");
                    // Model the previous deployment having queued a real pending V6 event before
                    // upgrading V7+.
                    flyway(database, "6").migrate();
                    execute(
                            connection,
                            "INSERT INTO sys_cache_outbox"
                                    + (" (id,event_kind,cache_key,cache_version,created_at,"
                                            + "next_attempt_at,attempts,last_error)")
                                    + " VALUES"
                                    + " ('existing-pending','permissions','existing-notification',37,'2020-01-02"
                                    + " 03:04:05.123456','2020-01-03"
                                    + " 03:04:05.123456',2,'RedisConnectionFailureException')");
                    Map<String, Object> pendingBefore =
                            selectedRow(
                                    connection,
                                    "SELECT * FROM sys_cache_outbox WHERE id='existing-pending'");
                    Flyway current = upgradeSafely(database, connection);
                    current.validate();
                    JsonNode manifest = load("migrations.json");
                    List<String> expectedVersions = new ArrayList<>();
                    for (JsonNode migration : manifest.path("migrations")) {
                        expectedVersions.add(migration.path("version").asText());
                    }
                    List<String> appliedVersions = new ArrayList<>();
                    for (var info : current.info().applied()) {
                        assertEquals(MigrationState.SUCCESS, info.getState());
                        assertNotNull(
                                info.getChecksum(),
                                "all versioned SQL migrations have a persisted checksum");
                        appliedVersions.add(info.getVersion().toString());
                    }
                    assertEquals(
                            expectedVersions,
                            appliedVersions,
                            "latest migrations must be discovered dynamically, no fixed V9/V10"
                                    + " count");
                    assertPreserved(connection, before);
                    assertEquals(
                            0L,
                            scalar(
                                    connection,
                                    "SELECT security_version FROM sys_user WHERE"
                                            + " id='usr-upgrade'"));
                    assertEquals(
                            0L,
                            scalar(
                                    connection,
                                    "SELECT security_version FROM sys_user_company WHERE"
                                            + " id='ctx-upgrade'"));
                    assertEquals(
                            "1",
                            text(
                                    connection,
                                    "SELECT item_code FROM sys_dict_item WHERE"
                                            + " id='di-user-status-1'"));
                    verifyLiveSchema(connection, database);
                    verifyOutboxUpgrade(connection, database, pendingBefore);
                    verifyActiveUniquenessAndRepeatDeletedRows(connection);
                    verifyRollback(connection);
                    assertEquals(
                            0, current.migrate().migrationsExecuted, "second migrate is a no-op");
                });
    }

    /** A populated prior deployment, including nonzero account/member/role CAS versions. */
    private static void seedExistingV5Aggregate(Connection connection) throws SQLException {
        // A real pre-existing account, membership, role and permanent binding with
        // nonzero CAS versions.
        execute(
                connection,
                "INSERT INTO sys_user"
                        + (" (id,username,password_hash,nickname,status_flag,create_user_no,"
                                + "create_date_time,delete_flag,revision)")
                        + " VALUES"
                        + " ('usr-upgrade','upgrade_existing','fixture-bcrypt-only','existing"
                        + " nickname',1,'upgrade-fixture','2020-01-02 03:04:05',1,7)");
        execute(
                connection,
                "INSERT INTO sys_user_company"
                        + (" (id,user_id,company_id,primary_flag,status_flag,create_user_no,"
                                + "create_date_time,delete_flag,revision)")
                        + " VALUES"
                        + " ('ctx-upgrade','usr-upgrade','cmp-robot',1,1,'upgrade-fixture','2020-01-02"
                        + " 03:04:05',1,3)");
        execute(
                connection,
                "INSERT INTO sys_role"
                        + (" (id,company_id,role_name,role_code,data_scope,sort_no,status_flag,remark,"
                                + "create_user_no,create_date_time,delete_flag,revision)")
                        + " VALUES ('role-upgrade','cmp-robot','Existing"
                        + " role','UPGRADE_EXISTING','self',7,1,'existing"
                        + " remark','upgrade-fixture','2020-01-02 03:04:05',1,4)");
        execute(
                connection,
                "INSERT INTO sys_user_role"
                        + " (id,user_id,role_id,company_id,create_user_no,create_date_time,delete_flag,revision)"
                        + " VALUES"
                        + " ('ur-upgrade','usr-upgrade','role-upgrade','cmp-robot','upgrade-fixture','2020-01-02"
                        + " 03:04:05',1,2)");
    }

    @Test
    void activeDuplicatePrecheckStopsBeforeAnyNewDdlAndLeavesExistingDataUntouched()
            throws Exception {
        inIsolatedDatabase(
                (database, connection) -> {
                    Flyway old = flyway(database, "5");
                    old.migrate();
                    cloneRow(
                            connection,
                            "sys_role",
                            "role-super-admin",
                            "duplicate-before-upgrade",
                            1);
                    assertTrue(precheck(connection).contains("sys_role.company_id.role_code"));
                    assertThrows(
                            IllegalStateException.class, () -> upgradeSafely(database, connection));
                    assertEquals("5", old.info().current().getVersion().toString());
                    assertEquals(
                            0L,
                            tableCount(connection, database, "sys_cache_epoch"),
                            "no V6 DDL before approved duplicate repair");
                    assertEquals(
                            1L,
                            scalar(
                                    connection,
                                    "SELECT COUNT(*) FROM sys_role WHERE"
                                            + " id='duplicate-before-upgrade'"));
                });
    }

    @Test
    void populatedDatabaseWithoutHistoryCannotBeSilentlyBaselined() throws Exception {
        inIsolatedDatabase(
                (database, connection) -> {
                    execute(connection, "CREATE TABLE unreviewed_existing (id INT PRIMARY KEY)");
                    execute(connection, "INSERT INTO unreviewed_existing VALUES (7)");
                    assertThrows(FlywayException.class, () -> flyway(database, null).migrate());
                    assertEquals(
                            UNREVIEWED_EXISTING_ID,
                            scalar(connection, "SELECT id FROM unreviewed_existing"));
                    assertEquals(0L, tableCount(connection, database, "sys_user"));
                });
    }

    @Test
    void membershipDepartmentExpandBackfillsOnlyTheMatchingCompanyAndKeepsAccountHistory()
            throws Exception {
        inIsolatedDatabase(
                (database, connection) -> {
                    flyway(database, "10").migrate();
                    execute(
                            connection,
                            "INSERT INTO"
                                    + " sys_company(id,tenant_id,company_name,status_flag,"
                                    + "create_user_no,create_date_time)"
                                    + " VALUES('cmp-other','tnt-default','Other"
                                    + " company',1,'fixture',CURRENT_TIMESTAMP)");
                    execute(
                            connection,
                            "INSERT INTO"
                                    + (" sys_user(id,username,password_hash,nickname,department_id,status_flag,"
                                            + "create_user_no,create_date_time,revision)")
                                    + (" VALUES('shared-dept','shared_department','fixture-hash','Shared','dept-dev',1,"
                                            + "'fixture',CURRENT_TIMESTAMP,7)"));
                    execute(
                            connection,
                            "INSERT INTO"
                                    + ((" sys_user_company(id,user_id,company_id,primary_flag,"
                                                    + "status_flag,create_user_no,")
                                            + "create_date_time,revision)")
                                    + " VALUES('shared-a','shared-dept','cmp-robot',1,1,'fixture',CURRENT_TIMESTAMP,3),"
                                    + "('shared-b','shared-dept','cmp-other',0,1,'fixture',CURRENT_TIMESTAMP,4)");
                    flyway(database, null).migrate();
                    assertEquals(
                            "dept-dev",
                            text(
                                    connection,
                                    "SELECT department_id FROM sys_user_company WHERE"
                                            + " id='shared-a'"));
                    assertNull(
                            text(
                                    connection,
                                    "SELECT department_id FROM sys_user_company WHERE"
                                            + " id='shared-b'"));
                    assertEquals(
                            "dept-dev",
                            text(
                                    connection,
                                    "SELECT department_id FROM sys_user WHERE id='shared-dept'"));
                    assertEquals(
                            LEGACY_ACCOUNT_REVISION,
                            scalar(
                                    connection,
                                    "SELECT revision FROM sys_user WHERE id='shared-dept'"));
                    assertEquals(
                            LEGACY_MEMBER_REVISION,
                            scalar(
                                    connection,
                                    "SELECT revision FROM sys_user_company WHERE id='shared-a'"));
                    assertEquals(
                            0,
                            scalar(
                                    connection,
                                    "SELECT platform_admin_flag FROM sys_user WHERE"
                                            + " id='shared-dept'"));
                });
    }

    private static Flyway upgradeSafely(String database, Connection connection) throws Exception {
        List<String> violations = precheck(connection);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "existing active duplicates require manual repair: " + violations);
        }
        Flyway current = flyway(database, null);
        current.migrate();
        return current;
    }

    private static List<String> precheck(Connection connection) throws Exception {
        List<String> violations = new ArrayList<>();
        for (JsonNode check : load("upgrade-prechecks.json").path("checks")) {
            String sql = check.path("sql").asText();
            assertTrue(
                    sql.startsWith("SELECT ") && !sql.contains(";"),
                    "prechecks must remain read-only single SELECT");
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery(sql)) {
                if (rows.next()) {
                    violations.add(check.path("id").asText());
                }
            }
        }
        return violations;
    }

    private static Map<String, List<Map<String, Object>>> snapshot(Connection connection)
            throws Exception {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : SNAPSHOT_TABLES) {
            snapshot.put(table, tableRows(connection, table));
        }
        return snapshot;
    }

    private static List<Map<String, Object>> tableRows(Connection connection, String table)
            throws SQLException {
        List<Map<String, Object>> values = new ArrayList<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
            while (rows.next()) {
                values.add(row(rows));
            }
        }
        return values;
    }

    private static void assertPreserved(
            Connection connection, Map<String, List<Map<String, Object>>> snapshot)
            throws Exception {
        for (var table : snapshot.entrySet()) {
            for (var previous : table.getValue()) {
                try (var statement =
                        connection.prepareStatement(
                                "SELECT * FROM " + table.getKey() + " WHERE id=?")) {
                    statement.setObject(1, previous.get("id"));
                    try (var rows = statement.executeQuery()) {
                        assertTrue(
                                rows.next(),
                                "existing row lost: " + table.getKey() + "/" + previous.get("id"));
                        Map<String, Object> after = row(rows);
                        for (var column : previous.entrySet()) {
                            assertEquals(
                                    column.getValue(),
                                    after.get(column.getKey()),
                                    table.getKey()
                                            + "/"
                                            + previous.get("id")
                                            + "/"
                                            + column.getKey());
                        }
                    }
                }
            }
        }
    }

    static void inIsolatedDatabase(DatabaseCheck check) throws Exception {
        assertEquals(
                "true",
                System.getProperty("robot.verify.mysql.enabled"),
                "only the explicit mysql-upgrade profile enables this test");
        String database = "robot_verify_" + UUID.randomUUID().toString().replace("-", "");
        if (!database.matches("robot_verify_[0-9a-f]{32}")) {
            throw new IllegalStateException("invalid generated isolation name");
        }
        try (Connection admin = connection(null)) {
            execute(
                    admin,
                    "CREATE DATABASE "
                            + database
                            + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            try (Connection isolated = connection(database)) {
                check.run(database, isolated);
            } finally {
                execute(admin, "DROP DATABASE " + database);
            }
        }
    }

    static Flyway flyway(String database, String target) {
        var configuration =
                Flyway.configure()
                        .dataSource(url(database), user(), password())
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(false)
                        .cleanDisabled(true)
                        .validateOnMigrate(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection connection(String database) throws SQLException {
        return DriverManager.getConnection(url(database), user(), password());
    }

    private static String url(String database) {
        String host = System.getenv().getOrDefault("ROBOT_VERIFY_MYSQL_HOST", "127.0.0.1");
        if (!List.of("127.0.0.1", "localhost", "::1").contains(host)) {
            throw new IllegalArgumentException("loopback MySQL host required");
        }
        int port =
                Integer.parseInt(System.getenv().getOrDefault("ROBOT_VERIFY_MYSQL_PORT", "3306"));
        if (port < 1 || port > MAX_TCP_PORT) {
            throw new IllegalArgumentException("invalid MySQL port");
        }
        return "jdbc:mysql://"
                + (host.contains(":") ? "[" + host + "]" : host)
                + ":"
                + port
                + "/"
                + (database == null ? "" : database)
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=UTF-8";
    }

    private static String user() {
        String username = System.getenv("ROBOT_VERIFY_MYSQL_USER");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("ROBOT_VERIFY_MYSQL_USER required");
        }
        return username;
    }

    private static String password() {
        return System.getenv().getOrDefault("ROBOT_VERIFY_MYSQL_PASSWORD", "");
    }

    @FunctionalInterface
    interface DatabaseCheck {
        void run(String database, Connection connection) throws Exception;
    }
}
