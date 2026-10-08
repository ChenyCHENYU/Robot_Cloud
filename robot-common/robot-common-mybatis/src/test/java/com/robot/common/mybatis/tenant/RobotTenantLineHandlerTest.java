package com.robot.common.mybatis.tenant;

import com.robot.common.core.constant.RobotConstants;
import net.sf.jsqlparser.expression.NullValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户拦截器测试：未登录返回 NULL（恒假），全局表白名单跳过。
 */
class RobotTenantLineHandlerTest {

    @Test
    @DisplayName("租户列名与全局表白名单行为")
    void shouldUseConfiguredColumnAndIgnoreGlobalTables() {
        RobotTenantLineHandler handler = new RobotTenantLineHandler(
                List.of("sys_user", "sys_menu"));

        assertEquals(RobotConstants.TENANT_COLUMN, handler.getTenantIdColumn());
        assertTrue(handler.ignoreTable("SYS_USER"));
        assertTrue(handler.ignoreTable("sys_menu"));
        assertFalse(handler.ignoreTable("sys_role"));
        assertFalse(handler.ignoreTable("sys_user_role"));
    }

    @Test
    @DisplayName("未登录（无 Web 上下文）时租户值为 NULL 字面量（查询恒空，防泄露）")
    void tenantIdShouldBeNullLiteralWithoutLogin() {
        RobotTenantLineHandler handler = new RobotTenantLineHandler(List.of());
        assertInstanceOf(NullValue.class, handler.getTenantId());
    }
}
