package com.robot.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 上下文装配冒烟测试：验证自动装配链完整（MP 插件链 / 租户拦截 / Sa-Token /
 * 全局异常 / TraceId / Redis 门面）。数据源用 H2(MySQL 模式)，Flyway 与
 * Redisson 在无基础设施环境下显式排除（生产装配见 application.yml）。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = RobotSystemApplication.class)
class RobotSystemApplicationTests {

    @MockBean
    private RedissonClient redissonClient;

    @Autowired
    private com.robot.system.user.service.UserService userService;

    @Autowired
    private com.robot.system.role.service.RoleCommandService roleCommands;

    @Autowired
    private com.robot.system.role.service.RoleQueryService roleQueries;

    @Autowired
    private com.robot.system.role.service.TemporaryAuthorizationCommandService temporaryCommands;

    @Autowired
    private com.robot.system.menu.service.MenuService menuService;

    @Autowired
    private com.robot.system.menu.service.MenuQueryService menuQueryService;

    @Autowired
    private com.robot.system.permission.service.PermissionChangeRecorder changeRecorder;

    @Autowired
    private com.robot.common.mybatis.tenant.RobotTenantLineHandler tenantLineHandler;

    @Test
    @DisplayName("核心 Bean 全部装配成功")
    void contextLoadsCoreBeans() {
        assertNotNull(userService);
        assertNotNull(roleCommands);
        assertNotNull(roleQueries);
        assertNotNull(temporaryCommands);
        assertNotNull(menuService);
        assertNotNull(menuQueryService);
        assertNotNull(changeRecorder);
        assertNotNull(tenantLineHandler);
        Mockito.verifyNoInteractions(redissonClient);
    }
}
