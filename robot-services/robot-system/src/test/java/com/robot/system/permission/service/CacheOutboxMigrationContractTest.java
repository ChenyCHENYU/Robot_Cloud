package com.robot.system.permission.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 执行实际迁移中的按钮登记与实际种子菜单，防止将治理动作挂到目录或无效页面。 */
class CacheOutboxMigrationContractTest {
    @Test
    void governanceButtonBelongsToAnExistingActivePageAndIsNotGrantedToOrdinaryRoles() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE sys_menu(id VARCHAR(64) PRIMARY KEY, parent_id VARCHAR(64), menu_type VARCHAR(20), "
                + "menu_name VARCHAR(100), path VARCHAR(255), component VARCHAR(255), icon VARCHAR(100), sort_no INT, "
                + "visible_flag INT, keep_alive_flag INT, status_flag INT, perm_code VARCHAR(100), "
                + "create_user_no VARCHAR(64), create_date_time TIMESTAMP, delete_flag INT, revision INT)");
        jdbc.execute("CREATE TABLE sys_menu_button(id VARCHAR(64), menu_id VARCHAR(64), button_name VARCHAR(100), "
                + "perm_code VARCHAR(100), sort_no INT, status_flag INT, create_user_no VARCHAR(64), "
                + "create_date_time TIMESTAMP, delete_flag INT, revision INT)");
        String seed = resource("db/migration/V2__seed_data.sql");
        jdbc.execute(statement(seed, "INSERT INTO sys_menu ("));
        String migration = resource("db/migration/V10__cache_outbox_governance.sql");
        jdbc.execute(statement(migration, "INSERT INTO sys_menu_button ("));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu_button b JOIN sys_menu m ON m.id=b.menu_id "
                + "WHERE b.perm_code='sys:cache-outbox:manage' AND b.delete_flag=1 AND b.status_flag=1 "
                + "AND m.menu_type='menu' AND m.delete_flag=1 AND m.status_flag=1", Integer.class));
        assertEquals(false, migration.contains("INSERT INTO sys_role_button"));
    }

    private String resource(String path) throws Exception {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String statement(String source, String prefix) {
        int start = source.indexOf(prefix);
        return source.substring(start, source.indexOf(';', start) + 1);
    }
}
