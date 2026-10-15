package com.robot.system.contract;

import static com.robot.system.contract.DatabaseUpgradeAssertions.execute;
import static com.robot.system.contract.DatabaseUpgradeAssertions.scalar;
import static com.robot.system.contract.DatabaseUpgradeAssertions.selectedRow;
import static com.robot.system.contract.DatabaseUpgradeAssertions.text;
import static com.robot.system.contract.ExistingDatabaseUpgradeIT.flyway;
import static com.robot.system.contract.ExistingDatabaseUpgradeIT.inIsolatedDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

/** 实际MySQL新建/升级/路径属主/授权上界和DML失败事务状态；只运行于隔离升级profile。 */
class NavigationSeedMigrationIT {
    private static final long MIGRATED_NAVIGATION_EPOCH = 2;
    private static final int GUARD_SUBQUERY_ERROR = 1242;
    private static final int BINDING_CHECK_ERROR = 3819;

    @Test
    void newDatabasePublishesTheRegisteredNullPermissionPageAndStableBinding() throws Exception {
        inIsolatedDatabase(
                (database, connection) -> {
                    Flyway current = flyway(database, null);
                    current.migrate();
                    verifyPage(connection, "menu-sys-permission");
                    assertEquals(
                            1, bindingCount(connection, "role-sys-admin", "menu-sys-permission"));
                    assertEpoch(connection, MIGRATED_NAVIGATION_EPOCH);
                    assertEquals(0, current.migrate().migrationsExecuted);
                    assertEquals(
                            1, bindingCount(connection, "role-sys-admin", "menu-sys-permission"));
                });
    }

    @Test
    void v10UpgradeInheritsOnlyExistingRoleListNavigationAndLeavesApiPermissionsUnchanged()
            throws Exception {
        inIsolatedDatabase(
                (database, connection) -> {
                    flyway(database, "10").migrate();
                    NavigationSeedFixtures.roles(connection);
                    Map<String, TreeSet<String>> codes =
                            NavigationSeedFixtures.permissionCodes(connection);
                    long buttons = scalar(connection, "SELECT COUNT(*) FROM sys_role_button");
                    flyway(database, null).migrate();
                    verifyPage(connection, "menu-sys-permission");
                    assertEquals(
                            1, bindingCount(connection, "nav-qualified", "menu-sys-permission"));
                    assertEquals(
                            0, bindingCount(connection, "nav-unqualified", "menu-sys-permission"));
                    assertEquals(
                            0, bindingCount(connection, "nav-disabled", "menu-sys-permission"));
                    assertEquals(codes, NavigationSeedFixtures.permissionCodes(connection));
                    assertEquals(
                            buttons, scalar(connection, "SELECT COUNT(*) FROM sys_role_button"));
                    assertEpoch(connection, MIGRATED_NAVIGATION_EPOCH);
                });
    }

    @Test
    void validExistingRelativeRouteOwnerAndEveryFieldArePreserved() throws Exception {
        inIsolatedDatabase(
                (database, connection) -> {
                    flyway(database, "12").migrate();
                    NavigationSeedFixtures.owner(
                            connection, "customer-owner", "permission-manage", null);
                    Map<String, Object> before =
                            selectedRow(
                                    connection, "SELECT * FROM sys_menu WHERE id='customer-owner'");
                    flyway(database, null).migrate();
                    assertEquals(
                            before,
                            selectedRow(
                                    connection,
                                    "SELECT * FROM sys_menu WHERE id='customer-owner'"));
                    assertEquals(
                            0,
                            scalar(
                                    connection,
                                    "SELECT COUNT(*) FROM sys_menu WHERE"
                                            + " id='menu-sys-permission'"));
                    assertEquals(1, bindingCount(connection, "role-sys-admin", "customer-owner"));
                });
    }

    @Test
    void existingExactRoleListCodeIsPreservedWithoutAddingARecipientApiPermission()
            throws Exception {
        inIsolatedDatabase(
                (database, connection) -> {
                    flyway(database, "12").migrate();
                    NavigationSeedFixtures.owner(
                            connection,
                            "customer-owner",
                            "/sys-manage/permission-manage",
                            "sys:role:list");
                    Map<String, Object> before =
                            selectedRow(
                                    connection, "SELECT * FROM sys_menu WHERE id='customer-owner'");
                    Map<String, TreeSet<String>> codes =
                            NavigationSeedFixtures.permissionCodes(connection);
                    flyway(database, null).migrate();
                    assertEquals(
                            before,
                            selectedRow(
                                    connection,
                                    "SELECT * FROM sys_menu WHERE id='customer-owner'"));
                    assertEquals(codes, NavigationSeedFixtures.permissionCodes(connection));
                    assertEquals(1, bindingCount(connection, "role-sys-admin", "customer-owner"));
                });
    }

    @Test
    void incompatibleCanonicalOwnerFailsBeforeWritesAndDoesNotElevateRoleOrOverwriteOwner()
            throws Exception {
        inIsolatedDatabase(
                (database, connection) -> {
                    flyway(database, "12").migrate();
                    NavigationSeedFixtures.owner(
                            connection,
                            "customer-owner",
                            "/SYS-MANAGE//PERMISSION-MANAGE/",
                            "sys:permission:list");
                    Map<String, Object> before =
                            selectedRow(
                                    connection, "SELECT * FROM sys_menu WHERE id='customer-owner'");
                    Map<String, TreeSet<String>> codes =
                            NavigationSeedFixtures.permissionCodes(connection);
                    assertSqlFailure(
                            assertThrows(
                                    FlywayException.class, () -> flyway(database, null).migrate()),
                            GUARD_SUBQUERY_ERROR,
                            "21000");
                    assertEquals(
                            before,
                            selectedRow(
                                    connection,
                                    "SELECT * FROM sys_menu WHERE id='customer-owner'"));
                    assertEquals(codes, NavigationSeedFixtures.permissionCodes(connection));
                    assertEquals(0, bindingCount(connection, "role-sys-admin", "customer-owner"));
                    assertEpoch(connection, 1);
                    assertNoSuccessfulV13(connection, database);
                });
    }

    @Test
    void duplicateEffectiveCanonicalPathsFailWithoutChangingEitherOwner() throws Exception {
        inIsolatedDatabase(
                (database, connection) -> {
                    flyway(database, "12").migrate();
                    NavigationSeedFixtures.owner(
                            connection, "first-owner", "permission-manage", null);
                    NavigationSeedFixtures.owner(
                            connection, "second-owner", "/SYS-MANAGE/PERMISSION-MANAGE/", null);
                    assertSqlFailure(
                            assertThrows(
                                    FlywayException.class, () -> flyway(database, null).migrate()),
                            GUARD_SUBQUERY_ERROR,
                            "21000");
                    assertEquals(
                            2,
                            scalar(
                                    connection,
                                    "SELECT COUNT(*) FROM sys_menu WHERE id IN"
                                            + " ('first-owner','second-owner') AND revision=7"));
                    assertEpoch(connection, 1);
                    assertNoSuccessfulV13(connection, database);
                });
    }

    @Test
    void pureDmlFailureAfterPageInsertRollsBackPageBindingAndEpochWithFailedHistoryObservable()
            throws Exception {
        inIsolatedDatabase(
                (database, connection) -> {
                    flyway(database, "12").migrate();
                    long bindings = scalar(connection, "SELECT COUNT(*) FROM sys_role_menu");
                    execute(
                            connection,
                            "ALTER TABLE sys_role_menu ADD CONSTRAINT reject_governance_binding"
                                    + " CHECK (menu_id <> 'menu-sys-permission')");
                    assertSqlFailure(
                            assertThrows(
                                    FlywayException.class, () -> flyway(database, null).migrate()),
                            BINDING_CHECK_ERROR,
                            "HY000");
                    assertEquals(
                            0,
                            scalar(
                                    connection,
                                    "SELECT COUNT(*) FROM sys_menu WHERE"
                                            + " id='menu-sys-permission'"));
                    assertEquals(
                            bindings, scalar(connection, "SELECT COUNT(*) FROM sys_role_menu"));
                    assertEpoch(connection, 1);
                    assertNoSuccessfulV13(connection, database);
                });
    }

    private static void assertSqlFailure(FlywayException failure, int code, String state) {
        SQLException deepest = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                deepest = sql;
            }
        }
        assertNotNull(deepest, "The migration must fail at the intended MySQL statement");
        assertEquals(code, deepest.getErrorCode());
        assertEquals(state, deepest.getSQLState());
    }

    private static void verifyPage(Connection connection, String id) throws Exception {
        JsonNode contract = loadNavigationContract();
        Map<String, Object> menu =
                selectedRow(connection, "SELECT * FROM sys_menu WHERE id='" + id + "'");
        assertEquals(contract.path("parentId").asText(), menu.get("parent_id"));
        assertEquals(contract.path("path").asText(), menu.get("path"));
        assertEquals(contract.path("component").asText(), menu.get("component"));
        assertEquals(contract.path("menuType").asText(), menu.get("menu_type"));
        assertEquals(null, menu.get("perm_code"));
        assertEquals(
                "13",
                text(
                        connection,
                        "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY"
                                + " installed_rank DESC LIMIT 1"));
        assertEquals(
                contract.path("tableCount").asInt(),
                scalar(
                        connection,
                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                + " table_schema=DATABASE() AND table_name<>'flyway_schema_history'"));
    }

    private static JsonNode loadNavigationContract() throws Exception {
        try (var input =
                NavigationSeedMigrationIT.class.getResourceAsStream(
                        "/contracts/system/navigation-seed.json")) {
            assertNotNull(input);
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
        }
    }

    private static long bindingCount(Connection connection, String role, String menu)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "SELECT COUNT(*) FROM sys_role_menu WHERE role_id=? AND menu_id=? AND"
                                + " delete_flag=1")) {
            statement.setString(1, role);
            statement.setString(2, menu);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static void assertEpoch(Connection connection, long expected) throws SQLException {
        assertEquals(
                expected,
                scalar(
                        connection,
                        "SELECT version FROM sys_cache_epoch WHERE scope='permissions'"));
        assertEquals(
                expected,
                scalar(connection, "SELECT version FROM sys_cache_epoch WHERE scope='menu-tree'"));
    }

    private static void assertNoSuccessfulV13(Connection connection, String database)
            throws SQLException {
        assertEquals(
                0,
                scalar(
                        connection,
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version='13' AND"
                                + " success=1"));
        var info = flyway(database, null).info().all();
        var failed =
                java.util.Arrays.stream(info)
                        .filter(
                                item ->
                                        item.getVersion() != null
                                                && "13".equals(item.getVersion().toString()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                MigrationState.FAILED,
                failed.getState(),
                "MySQL records failed history even when this pure DML transaction rolls back");
        assertEquals(
                1,
                scalar(
                        connection,
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version='13' AND"
                                + " success=0"));
    }
}
