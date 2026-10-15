package com.robot.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 认证授权服务。
 *
 * <p>无本地数据库：用户/组织数据经 OpenFeign 调用 robot-system 内部接口获取； 会话态全部落在 Redis（Sa-Token 会话 + 登录票据 + 刷新令牌）。
 */
@EnableFeignClients(basePackages = "com.robot.api")
@EnableDiscoveryClient
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class RobotAuthApplication {

    /**
     * Start the authentication service with normal Spring command-line configuration.
     *
     * @param args application arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(RobotAuthApplication.class, args);
    }
}
