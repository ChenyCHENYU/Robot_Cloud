package com.robot.system.contract;

import static com.robot.system.contract.DatabaseUpgradeAssertions.execute;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/** 明确的隔离导航/授权配置；既有数据快照保持独立，不由测试重建期望迁移。 */
final class NavigationSeedFixtures {
    private static final int PERMISSION_PARAMETER = 3;
    private static final Function<String, TreeSet<String>> EMPTY_ROLE_CODES =
            unused -> new TreeSet<>();

    private NavigationSeedFixtures() {}

    static void roles(Connection connection) throws SQLException {
        execute(
                connection,
                "INSERT INTO"
                        + (" sys_role(id,company_id,role_name,role_code,data_scope,status_flag,"
                                + "create_user_no,create_date_time)")
                        + " VALUES"
                        + (" ('nav-qualified','cmp-robot','Qualified','NAV_QUALIFIED','self',1,"
                                + "'fixture',UTC_TIMESTAMP()),")
                        + (" ('nav-unqualified','cmp-robot','Unqualified','NAV_UNQUALIFIED','self',1,"
                                + "'fixture',UTC_TIMESTAMP()),")
                        + (" ('nav-disabled','cmp-robot','Disabled','NAV_DISABLED','self',0,"
                                + "'fixture',UTC_TIMESTAMP())"));
        execute(
                connection,
                "INSERT INTO sys_role_menu(id,role_id,menu_id,create_user_no,create_date_time)"
                        + " VALUES"
                        + " ('nav-q-role','nav-qualified','menu-sys-role','fixture',UTC_TIMESTAMP()),"
                        + " ('nav-u-home','nav-unqualified','menu-home','fixture',UTC_TIMESTAMP()),"
                        + " ('nav-d-role','nav-disabled','menu-sys-role','fixture',UTC_TIMESTAMP())");
    }

    static void owner(Connection connection, String id, String path, String permission)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "INSERT INTO"
                                + " sys_menu(id,parent_id,menu_type,menu_name,path,component,sort_no,"
                                + "visible_flag,status_flag,perm_code,create_user_no,create_date_time,revision)"
                                + " VALUES(?,'menu-sys','menu','Customer"
                                + " governance',?,'sys-manage/permission-manage/index',9,1,1,?,'customer','2020-01-01"
                                + " 00:00:00',7)")) {
            statement.setString(1, id);
            statement.setString(2, path);
            statement.setString(PERMISSION_PARAMETER, permission);
            statement.executeUpdate();
        }
    }

    static Map<String, TreeSet<String>> permissionCodes(Connection connection) throws SQLException {
        Map<String, TreeSet<String>> codes = new TreeMap<>();
        String sql =
                "SELECT r.id,m.perm_code FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.id"
                        + " JOIN sys_menu m ON m.id=rm.menu_id WHERE r.delete_flag=1 AND"
                        + " r.status_flag=1 AND rm.delete_flag=1 AND m.delete_flag=1 AND"
                        + " m.status_flag=1 AND m.perm_code IS NOT NULL AND TRIM(m.perm_code)<>'' UNION"
                        + " SELECT r.id,b.perm_code FROM sys_role r JOIN sys_role_button rb ON"
                        + " rb.role_id=r.id JOIN sys_menu_button b ON b.id=rb.button_id JOIN sys_menu m"
                        + " ON m.id=b.menu_id WHERE r.delete_flag=1 AND r.status_flag=1 AND"
                        + " rb.delete_flag=1 AND b.delete_flag=1 AND b.status_flag=1 AND"
                        + " m.delete_flag=1 AND m.status_flag=1";
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                codes.computeIfAbsent(rows.getString(1), EMPTY_ROLE_CODES).add(rows.getString(2));
            }
        }
        return codes;
    }
}
