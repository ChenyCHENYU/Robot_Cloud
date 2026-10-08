package com.robot.common.security.interceptor;

import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.config.RobotSecurityProperties;
import com.robot.common.security.context.InnerTrustContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口鉴权拦截器：/inner/** 仅允许携带正确 X-Inner-Call 密钥的服务间调用。
 *
 * <p>纵深防御与委托语义：
 * <ul>
 *   <li>网关对外部请求清洗全部内部头（外部请求到不了 /inner 路由）；</li>
 *   <li>本拦截器先做常量时间密钥比较，失败 403 且<b>不解析</b>任何委托头；</li>
 *   <li>密钥通过后，解析委托用户/公司/用户名并绑定
 *       {@link InnerTrustContext}（请求级 ThreadLocal，afterCompletion 清理），
 *       使租户拦截器与审计填充在服务间调用中获得与登录态一致的上下文。</li>
 * </ul>
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
        if (secret == null || !MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                properties.getInnerSecret().getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":403,\"message\":\"内部接口调用未授权\"}");
            return false;
        }
        // 密钥通过后绑定委托上下文（登录后用户委托调用；登录前供给接口不带这些头）
        String userId = request.getHeader(RobotConstants.HEADER_INNER_USER);
        String companyId = request.getHeader(RobotConstants.HEADER_INNER_COMPANY);
        String username = request.getHeader(RobotConstants.HEADER_INNER_USERNAME);
        if (userId != null && !userId.isBlank() && companyId != null && !companyId.isBlank()) {
            InnerTrustContext.bind(userId, companyId, username);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        InnerTrustContext.clear();
    }
}
