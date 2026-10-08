package com.robot.common.security.interceptor;

import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.util.AuthUtil;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * 内部委托上下文透传：登录后服务代表当前会话发起的 Feign 调用，
 * 自动携带委托用户/公司/用户名（与 InnerCallInterceptor 配对）。
 *
 * <p>与 api-system 的 InnerCallRelayInterceptor（服务身份密钥）分层：
 * 本拦截器只负责「代表谁」，不负责「谁在调用」；未登录调用不携带委托头。
 */
public class InnerTrustRelayInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String userId = AuthUtil.getLoginUserId();
        String companyId = AuthUtil.getLoginCompanyId();
        if (userId == null || companyId == null) {
            return;
        }
        template.header(RobotConstants.HEADER_INNER_USER, userId);
        template.header(RobotConstants.HEADER_INNER_COMPANY, companyId);
        String username = AuthUtil.getLoginUsername();
        if (username != null) {
            template.header(RobotConstants.HEADER_INNER_USERNAME, username);
        }
    }
}
