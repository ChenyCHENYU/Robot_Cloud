package com.robot.common.security.interceptor;

import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.util.AuthUtil;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 内部委托上下文透传：登录后服务代表当前会话发起的 Feign 调用， 自动携带委托用户/公司/用户名（与 InnerCallInterceptor 配对）。
 *
 * <p>与 api-system 的 InnerCallRelayInterceptor（服务身份密钥）分层： 本拦截器只负责「代表谁」，不负责「谁在调用」；未登录调用不携带委托头。
 */
public class InnerTrustRelayInterceptor implements RequestInterceptor {

    private final Class<?> audience;
    private final Predicate<String> delegationPath;

    /**
     * Bind delegation exclusively to the intended trusted Feign client type.
     *
     * @param audience exact intended Feign service interface
     */
    public InnerTrustRelayInterceptor(Class<?> audience) {
        this(audience, path -> true);
    }

    /**
     * Apply delegation only to reviewed paths on the intended service.
     *
     * @param audience exact intended Feign service interface
     * @param delegationPath reviewed path predicate
     */
    public InnerTrustRelayInterceptor(Class<?> audience, Predicate<String> delegationPath) {
        this.audience = Objects.requireNonNull(audience);
        this.delegationPath = Objects.requireNonNull(delegationPath);
    }

    @Override
    public void apply(RequestTemplate template) {
        clearDelegation(template);
        if (!acceptsDelegation(template)) {
            return;
        }
        LoginUser user = AuthUtil.getLoginUser();
        if (hasCompleteIdentity(user)) {
            writeDelegation(template, user);
        }
    }

    /** 清理调用方预先提交的委托，后续仅从当前可信上下文重新构建。 */
    private static void clearDelegation(RequestTemplate template) {
        template.removeHeader(RobotConstants.HEADER_INNER_USER);
        template.removeHeader(RobotConstants.HEADER_INNER_COMPANY);
        template.removeHeader(RobotConstants.HEADER_INNER_USERNAME);
    }

    private boolean acceptsDelegation(RequestTemplate template) {
        return template.feignTarget() != null
                && template.feignTarget().type() == audience
                && template.path().startsWith("/inner/")
                && delegationPath.test(template.path());
    }

    private static boolean hasCompleteIdentity(LoginUser user) {
        return user != null
                && user.getUserId() != null
                && !user.getUserId().isBlank()
                && user.getCompanyId() != null
                && !user.getCompanyId().isBlank();
    }

    private static void writeDelegation(RequestTemplate template, LoginUser user) {
        template.header(RobotConstants.HEADER_INNER_USER, user.getUserId());
        template.header(RobotConstants.HEADER_INNER_COMPANY, user.getCompanyId());
        String username = user.getUsername();
        if (username != null) {
            template.header(RobotConstants.HEADER_INNER_USERNAME, username);
        }
    }
}
