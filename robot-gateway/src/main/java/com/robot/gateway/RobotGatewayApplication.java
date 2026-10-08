package com.robot.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Robot Cloud API 网关。
 *
 * <p>职责边界：路由转发、登录态校验（白名单放行）、TraceId 注入、
 * Sentinel 网关限流、内部接口（/inner/**）外网屏蔽。
 * 业务鉴权（权限码）由各服务 Sa-Token 注解完成，网关不做业务判断。
 */
@EnableDiscoveryClient
@SpringBootApplication
@ConfigurationPropertiesScan
public class RobotGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(RobotGatewayApplication.class, args);
    }
}
