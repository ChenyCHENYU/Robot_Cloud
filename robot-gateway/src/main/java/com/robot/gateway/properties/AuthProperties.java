package com.robot.gateway.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * 网关鉴权白名单（Ant 风格）。新增白名单必须在代码评审登记理由，
 * 禁止放行宽泛通配（如 /**、/sys/**）。
 */
@Data
@ConfigurationProperties(prefix = "robot.gateway.auth")
public class AuthProperties {

    /** 免登录路径（与 robot-auth 的 robot.security.exclude-paths 保持一致，防配置漂移）。 */
    private List<String> whitePaths = Arrays.asList(
            "/auth/login",
            "/auth/login-companies",
            "/auth/refresh-token",
            "/auth/context/activate",
            "/actuator/health",
            "/actuator/prometheus"
    );
}
