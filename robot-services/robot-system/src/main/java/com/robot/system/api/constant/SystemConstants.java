package com.robot.system.api.constant;

/** 系统管理域常量（业务语义归属 robot-system，不进 common 模块）。 */
public final class SystemConstants {

    private SystemConstants() {}

    /** 菜单类型：目录。 */
    public static final String MENU_TYPE_DIRECTORY = "directory";

    /** 菜单类型：菜单。 */
    public static final String MENU_TYPE_MENU = "menu";

    /** 数据权限范围。 */
    public static final String DATA_SCOPE_ALL = "all";

    public static final String DATA_SCOPE_DEPARTMENT = "department";
    public static final String DATA_SCOPE_DEPARTMENT_BELOW = "department_below";
    public static final String DATA_SCOPE_SELF = "self";
    public static final String DATA_SCOPE_CUSTOM = "custom";
}
