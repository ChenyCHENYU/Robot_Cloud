package com.robot.common.security.interceptor;

import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.config.RobotSecurityProperties;
import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.util.AuthHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Objects;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 内部接口鉴权拦截器：/inner/** 仅允许携带正确 X-Inner-Call 密钥的服务间调用。
 *
 * <p>纵深防御与委托语义：
 *
 * <ul>
 *   <li>网关对外部请求清洗全部内部头（外部请求到不了 /inner 路由）；
 *   <li>本拦截器先做常量时间密钥比较，失败 403 且<b>不解析</b>任何委托头；
 *   <li>密钥通过后，解析委托用户/公司/用户名并绑定 {@link InnerTrustContext}（请求级 ThreadLocal，afterCompletion 清理），
 *       使租户拦截器与审计填充在服务间调用中获得与登录态一致的上下文。
 * </ul>
 */
public class InnerCallInterceptor implements AsyncHandlerInterceptor {
    private static final int MAX_USERNAME_LENGTH = 128;
    private static final int MAX_ID_LENGTH = 64;

    private final RobotSecurityProperties properties;

    /**
     * Bind the shared service secret; request trust is checked independently for every invocation.
     *
     * @param properties trusted security configuration
     */
    public InnerCallInterceptor(RobotSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        InnerTrustContext.clear();
        if (!verifiedServiceSecret(request)) {
            return reject(response);
        }
        Delegation delegation = readDelegation(request);
        if (!validDelegation(delegation) || !matchesLogin(delegation)) {
            return reject(response);
        }
        bindDelegation(delegation);
        return true;
    }

    /** 只读取服务凭据；通过常量时间比较后，才允许读取用户委托头。 */
    private boolean verifiedServiceSecret(HttpServletRequest request) {
        Header secret = readHeader(request, RobotConstants.HEADER_INNER_CALL);
        String configured = properties.getInnerSecret();
        return secret.isSingle()
                && secret.value() != null
                && configured != null
                && !configured.isBlank()
                && MessageDigest.isEqual(
                        secret.value().getBytes(StandardCharsets.UTF_8),
                        configured.getBytes(StandardCharsets.UTF_8));
    }

    private static Delegation readDelegation(HttpServletRequest request) {
        return new Delegation(
                readHeader(request, RobotConstants.HEADER_INNER_USER),
                readHeader(request, RobotConstants.HEADER_INNER_COMPANY),
                readHeader(request, RobotConstants.HEADER_INNER_USERNAME));
    }

    /** 无委托是服务身份；只要提交任一委托字段，就必须有唯一且完整的用户/公司身份。 */
    private static boolean validDelegation(Delegation delegation) {
        if (!delegation.hasHeaders()) {
            return true;
        }
        return validIdentityHeader(delegation.user())
                && validIdentityHeader(delegation.company())
                && validUsernameHeader(delegation.username());
    }

    private static boolean validIdentityHeader(Header header) {
        return header.isSingle() && validId(header.value());
    }

    private static boolean validUsernameHeader(Header header) {
        if (!header.isPresent()) {
            return true;
        }
        return header.isSingle()
                && header.value() != null
                && !header.value().isBlank()
                && header.value().length() <= MAX_USERNAME_LENGTH;
    }

    /** 请求已有登录态时，委托必须与 token Session 的账号、公司及可选用户名一致。 */
    private static boolean matchesLogin(Delegation delegation) {
        Object loginId = AuthHolder.getLoginId();
        if (loginId == null) {
            return true;
        }
        LoginUser loginUser =
                AuthHolder.getSessionAttr(AuthHolder.SESSION_LOGIN_USER, LoginUser.class);
        return delegation.hasHeaders()
                && loginUser != null
                && Objects.equals(String.valueOf(loginId), delegation.user().value())
                && Objects.equals(loginUser.getUserId(), delegation.user().value())
                && Objects.equals(loginUser.getCompanyId(), delegation.company().value())
                && matchesUsername(loginUser, delegation.username());
    }

    private static boolean matchesUsername(LoginUser loginUser, Header username) {
        return !username.isPresent() || Objects.equals(loginUser.getUsername(), username.value());
    }

    private static void bindDelegation(Delegation delegation) {
        InnerTrustContext.bindVerifiedService(
                delegation.user().value(),
                delegation.company().value(),
                delegation.username().value());
    }

    private static boolean validId(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= MAX_ID_LENGTH
                && value.equals(value.trim());
    }

    private static Header readHeader(HttpServletRequest request, String name) {
        return new Header(
                request.getHeader(name), Collections.list(request.getHeaders(name)).size());
    }

    private record Header(String value, int count) {
        boolean isPresent() {
            return count > 0;
        }

        boolean isSingle() {
            return count == 1;
        }

        @Override
        public String toString() {
            return "Header[count=" + count + "]";
        }
    }

    private record Delegation(Header user, Header company, Header username) {
        boolean hasHeaders() {
            return user.isPresent() || company.isPresent() || username.isPresent();
        }
    }

    private static boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"内部接口调用未授权\"}");
        return false;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        InnerTrustContext.clear();
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 不把请求权限隐式继承到异步工作线程。
        InnerTrustContext.clear();
    }
}
