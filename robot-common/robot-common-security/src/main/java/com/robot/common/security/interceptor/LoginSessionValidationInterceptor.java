package com.robot.common.security.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.robot.common.security.session.SessionStateVerifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** 公开认证流程由各服务自行验证；其余已登录请求必须仍属于当前有效刷新族。 */
public class LoginSessionValidationInterceptor implements HandlerInterceptor {

    private final SessionStateVerifier verifier;

    /**
     * Bind the required current-family and subject revalidation boundary.
     *
     * @param verifier current session authority verifier
     */
    public LoginSessionValidationInterceptor(SessionStateVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (StpUtil.isLogin()) {
            verifier.verify();
        }
        return true;
    }
}
