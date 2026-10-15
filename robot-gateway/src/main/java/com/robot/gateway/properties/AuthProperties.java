package com.robot.gateway.properties;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关鉴权白名单（Ant 风格）。新增白名单必须在代码评审登记理由， 禁止放行宽泛通配（如 /**、/sys/**）。 */
@Data
@ConfigurationProperties(prefix = "robot.gateway.auth")
public class AuthProperties {

    /** 免登录路径（与 robot-auth 的 robot.security.exclude-paths 保持一致，防配置漂移）。 */
    private List<String> whitePaths =
            List.of(
                    "/auth/login",
                    "/auth/login-companies",
                    "/auth/refresh-token",
                    "/auth/context/activate",
                    "/actuator/health",
                    "/actuator/prometheus");

    /**
     * Return an immutable snapshot of the configured public paths.
     *
     * @return public path snapshot
     */
    public List<String> getWhitePaths() {
        return List.copyOf(whitePaths);
    }

    /**
     * Store a detached public path snapshot while preserving configuration binding.
     *
     * @param paths reviewed public path list
     */
    public void setWhitePaths(List<String> paths) {
        whitePaths = List.copyOf(paths);
    }
}
