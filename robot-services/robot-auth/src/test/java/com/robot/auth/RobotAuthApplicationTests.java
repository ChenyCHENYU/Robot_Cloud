package com.robot.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 认证服务上下文装配冒烟测试：Sa-Token / Feign 契约 / Redis 门面 /
 * BCrypt 摘要器全部可注入（Redisson 在无基础设施环境下 Mock）。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = RobotAuthApplication.class)
class RobotAuthApplicationTests {

    @MockBean
    private RedissonClient redissonClient;

    @Autowired
    private com.robot.auth.authn.service.AuthService authService;

    @Autowired
    private com.robot.common.redis.core.RedisHelper redisHelper;

    @Autowired
    private org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwordEncoder;

    @Test
    @DisplayName("核心 Bean 全部装配成功")
    void contextLoadsCoreBeans() {
        assertNotNull(authService);
        assertNotNull(redisHelper);
        assertNotNull(passwordEncoder);
    }

    @Autowired
    private com.robot.common.security.config.RobotSecurityProperties securityProperties;

    /**
     * 防漂移回归：匿名认证端点必须免登录——loginTicket 激活发生在用户拿到
     * Token 之前，漏配会被 Sa-Token 拦截（与网关白名单同步固化）。
     */
    @Test
    @DisplayName("匿名认证端点全部免登录（含 context/activate）")
    void anonymousAuthEndpointsMustBeExcluded() {
        java.util.List<String> required = java.util.List.of(
                "/auth/login",
                "/auth/login-companies",
                "/auth/refresh-token",
                "/auth/context/activate");
        for (String path : required) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    securityProperties.getExcludePaths().contains(path),
                    "服务免登录路径缺失: " + path);
        }
    }

    @Test
    @DisplayName("BCrypt 摘要与种子账号哈希算法一致（admin123 可验证）")
    void bcryptShouldVerifySeedPassword() {
        String hash = "$2a$10$hFoJ4.tqHIl5h2iy5iz3POElWGTYS2mXr8SScH9IE5wHNpIxPUdtu";
        org.junit.jupiter.api.Assertions.assertTrue(passwordEncoder.matches("admin123", hash));
        org.junit.jupiter.api.Assertions.assertFalse(passwordEncoder.matches("wrong", hash));
    }
}
