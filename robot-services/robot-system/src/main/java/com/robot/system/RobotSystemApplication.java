package com.robot.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 系统管理服务：sys 域数据唯一属主（用户/角色/菜单/部门/字典/权限治理），
 * 并通过 /inner/** 内部接口向认证服务供给用户上下文。
 */
@EnableDiscoveryClient
@SpringBootApplication
public class RobotSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(RobotSystemApplication.class, args);
    }
}
