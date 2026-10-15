package com.robot.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robot.gateway.filter.AuthGlobalFilter;
import com.robot.gateway.properties.AuthProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 网关上下文装配冒烟测试：验证配置属性注册、全局过滤器装配 （此前 AuthProperties 未注册会导致网关启动失败，本测试固化该缺陷不再回归）。 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = RobotGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RobotGatewayApplicationTests {
    private static final int EXPECTED_PUBLIC_PATH_COUNT = 6;

    @Autowired private AuthProperties authProperties;

    @Autowired private AuthGlobalFilter authGlobalFilter;

    @Test
    @DisplayName("网关核心 Bean 与白名单配置装配成功")
    void contextLoadsGatewayBeans() {
        assertNotNull(authProperties);
        assertNotNull(authGlobalFilter);
        assertFalse(authProperties.getWhitePaths().isEmpty());
        assertTrue(authProperties.getWhitePaths().contains("/auth/login"));
    }

    @Test
    @DisplayName("白名单默认值符合契约")
    void whiteListShouldMatchContract() {
        assertEquals(EXPECTED_PUBLIC_PATH_COUNT, authProperties.getWhitePaths().size());
    }

    /** 防漂移回归：匿名认证端点必须放行——loginTicket 激活链路发生在 用户拿到 Token 之前，漏配会导致多公司账号登录 401（历史缺陷固化）。 */
    @Test
    @DisplayName("匿名认证端点全部在白名单（含 context/activate）")
    void anonymousAuthEndpointsMustBeWhitelisted() {
        List<String> required =
                List.of(
                        "/auth/login",
                        "/auth/login-companies",
                        "/auth/refresh-token",
                        "/auth/context/activate");
        for (String path : required) {
            assertTrue(authProperties.getWhitePaths().contains(path), "白名单缺失匿名端点: " + path);
        }
    }
}
