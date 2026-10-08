package com.robot.common.security.interceptor;

import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.config.RobotSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口鉴权拦截器：/inner/** 仅允许携带正确 X-Inner-Call 密钥的服务间调用。
 *
 * <p>纵深防御：网关已对外部请求清洗 X-Inner-Call 头（外部请求到不了 /inner 路由），
 * 本拦截器防止集群内任意 Pod 无凭据直调内部接口（含 BCrypt 摘要下发）。
 */
public class InnerCallInterceptor implements HandlerInterceptor {

    private final RobotSecurityProperties properties;

    public InnerCallInterceptor(RobotSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String secret = request.getHeader(RobotConstants.HEADER_INNER_CALL);
        // 常量时间比较，防时序侧信道探测密钥
        if (secret != null && MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                properties.getInnerSecret().getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":403,\"message\":\"内部接口调用未授权\"}");
        return false;
    }
}
