package com.robot.common.security.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全配置项。
 *
 * <pre>
 * robot:
 *   security:
 *     exclude-paths:
 *       - /actuator/**
 *       - /v3/api-docs/**
 *       - /doc.html
 *       - /inner/**   # 内部接口走网关清洗 + 内部调用头校验，不走登录态
 *     inner-secret: ${ROBOT_INNER_SECRET:robot-inner-dev-secret}   # /inner/** 服务间凭据
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "robot.security")
public class RobotSecurityProperties {

    /** 免登录路径（Ant 风格）。 */
    private List<String> excludePaths = new ArrayList<>();

    /** 内部接口共享密钥：调用方（Feign 拦截器）与提供方（/inner 拦截器）必须一致；生产走环境变量覆盖。 */
    private String innerSecret = "robot-inner-dev-secret";
}
