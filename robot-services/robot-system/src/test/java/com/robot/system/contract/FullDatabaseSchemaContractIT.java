package com.robot.system.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * Reviews every table against the fixed, reviewed MySQL schema contract. The explicit upgrade
 * profile owns each random loopback database; expected columns, order, defaults, expressions,
 * comments and indexes are never refreshed by a test.
 */
class FullDatabaseSchemaContractIT {
    private static final int REVIEWED_TABLE_COUNT = 20;
    private static final int MAX_TCP_PORT = 65535;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TABLES =
            "SELECT"
                    + " JSON_OBJECT('name',TABLE_NAME,'engine',ENGINE,"
                    + "'collation',TABLE_COLLATION,'comment',TABLE_COMMENT)"
                    + " FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND"
                    + " TABLE_TYPE='BASE TABLE' AND TABLE_NAME<>'flyway_schema_history' ORDER BY"
                    + " TABLE_NAME";
    private static final String COLUMNS =
            "SELECT JSON_OBJECT('table',TABLE_NAME,'name',COLUMN_NAME,"
                    + "'position',ORDINAL_POSITION,'type',DATA_TYPE,'columnType',COLUMN_TYPE,"
                    + "'length',CHARACTER_MAXIMUM_LENGTH,'precision',NUMERIC_PRECISION,'scale',NUMERIC_SCALE,"
                    + "'datetimePrecision',DATETIME_PRECISION,'nullable',IS_NULLABLE='YES','default',COLUMN_DEFAULT,"
                    + "'extra',EXTRA,'generation',GENERATION_EXPRESSION,'charset',CHARACTER_SET_NAME,"
                    + "'collation',COLLATION_NAME,'comment',COLUMN_COMMENT) FROM"
                    + " information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND"
                    + " TABLE_NAME<>'flyway_schema_history' ORDER BY TABLE_NAME,ORDINAL_POSITION";
    private static final String INDEXES =
            "SELECT JSON_OBJECT('table',TABLE_NAME,'name',INDEX_NAME,"
                    + "'position',SEQ_IN_INDEX,'unique',NON_UNIQUE=0,'column',COLUMN_NAME,"
                    + "'prefix',SUB_PART,'order',COLLATION,'type',INDEX_TYPE,'visible',IS_VISIBLE,"
                    + "'expression',EXPRESSION)"
                    + " FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND"
                    + " TABLE_NAME<>'flyway_schema_history' ORDER BY"
                    + " TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX";

    @Test
    void newDatabaseMatchesEveryReviewedTableAttribute() throws Exception {
        verify(null);
    }

    @Test
    void existingV10DatabaseExpandsToTheSameReviewedSchema() throws Exception {
        verify("10");
    }

    private static void verify(String initialVersion) throws Exception {
        assertEquals("true", System.getProperty("robot.verify.mysql.enabled"));
        JsonNode expected;
        try (InputStream input = resource("/contracts/database/full-schema.json")) {
            expected = JSON.readTree(input);
        }
        assertEquals(REVIEWED_TABLE_COUNT, expected.path("tables").size());
        var hashes = expected.path("migrationHashes").fields();
        while (hashes.hasNext()) {
            var entry = hashes.next();
            try (InputStream input = resource("/db/migration/" + entry.getKey())) {
                assertEquals(
                        entry.getValue().asText(),
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(input.readAllBytes())),
                        entry.getKey());
            }
        }
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
            try {
                if (initialVersion != null) {
                    Flyway.configure()
                            .dataSource(url(database), user(), password())
                            .locations("classpath:db/migration")
                            .target(initialVersion)
                            .baselineOnMigrate(false)
                            .cleanDisabled(true)
                            .load()
                            .migrate();
                }
                Flyway.configure()
                        .dataSource(url(database), user(), password())
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(false)
                        .cleanDisabled(true)
                        .load()
                        .migrate();
                try (Connection current = connection(database)) {
                    assertEquals(expected.path("tables"), snapshot(current));
                }
            } finally {
                execute(admin, "DROP DATABASE " + database);
            }
        }
    }

    private static JsonNode snapshot(Connection connection) throws Exception {
        Map<String, ObjectNode> tables = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(TABLES)) {
            while (rows.next()) {
                ObjectNode table = (ObjectNode) JSON.readTree(rows.getString(1));
                table.putArray("columns");
                table.putArray("indexes");
                tables.put(table.path("name").asText(), table);
            }
        }
        append(connection, tables, COLUMNS, "columns");
        append(connection, tables, INDEXES, "indexes");
        return JSON.valueToTree(tables);
    }

    private static void append(
            Connection connection, Map<String, ObjectNode> tables, String sql, String attribute)
            throws Exception {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                ObjectNode item = (ObjectNode) JSON.readTree(rows.getString(1));
                String table = item.remove("table").asText();
                tables.get(table).withArray(attribute).add(item);
            }
        }
    }

    private static InputStream resource(String path) {
        InputStream input = FullDatabaseSchemaContractIT.class.getResourceAsStream(path);
        assertNotNull(input, path);
        return input;
    }

    private static Connection connection(String database) throws Exception {
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
        String address = host.contains(":") ? "[" + host + "]" : host;
        return "jdbc:mysql://"
                + address
                + ":"
                + port
                + "/"
                + (database == null ? "" : database)
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=UTF-8";
    }

    private static String user() {
        String user = System.getenv("ROBOT_VERIFY_MYSQL_USER");
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("ROBOT_VERIFY_MYSQL_USER required");
        }
        return user;
    }

    private static String password() {
        return System.getenv().getOrDefault("ROBOT_VERIFY_MYSQL_PASSWORD", "");
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
