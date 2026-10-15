package com.robot.auth.authn.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应（前端 LoginData 契约）。
 *
 * <p>两种返回形态：
 *
 * <ul>
 *   <li>直接会话：token/refreshToken/expiresIn/user/activeContext/availableContexts 齐备；
 *   <li>待激活：token 为空串、expiresIn=0，携带 loginTicket + availableContexts， 前端调 /auth/context/activate
 *       换取完整会话。
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LoginResponse implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    /** 访问令牌（未激活形态为空串）。 */
    @lombok.ToString.Exclude private String token;

    /** 刷新令牌（未激活形态为空串）。 */
    @Builder.Default @lombok.ToString.Exclude private String refreshToken = "";

    /** 访问令牌有效期（秒；未激活形态为 0）。 */
    @Builder.Default private long expiresIn = 0L;

    /** 登录票据：身份已验证、待选择公司的短时一次性凭据。 */
    @lombok.ToString.Exclude private String loginTicket;

    /** 可进入的企业工作上下文清单（角色只在激活后授予）。 */
    private List<AuthContextVO> availableContexts;

    /** 当前激活上下文（直接会话形态返回）。 */
    private AuthContextVO activeContext;

    /** 用户基本信息。 */
    private UserVO user;

    /**
     * Return independent available-context and role views.
     *
     * @return detached contexts, or null when unavailable
     */
    public List<AuthContextVO> getAvailableContexts() {
        return copyContexts(availableContexts);
    }

    /**
     * Preserve JSON binding with independent context views.
     *
     * @param values available contexts
     */
    public void setAvailableContexts(List<AuthContextVO> values) {
        availableContexts = copyContexts(values);
    }

    /**
     * Return an independent active-context view.
     *
     * @return active context, or null before activation
     */
    public AuthContextVO getActiveContext() {
        return copyContext(activeContext);
    }

    /**
     * Store an independent active context.
     *
     * @param value active context
     */
    public void setActiveContext(AuthContextVO value) {
        activeContext = copyContext(value);
    }

    /**
     * Return an independent public account view.
     *
     * @return public account, or null before activation
     */
    public UserVO getUser() {
        return copyUser(user);
    }

    /**
     * Store an independent public account view.
     *
     * @param value public account
     */
    public void setUser(UserVO value) {
        user = copyUser(value);
    }

    private static List<AuthContextVO> copyContexts(List<AuthContextVO> values) {
        return values == null ? null : values.stream().map(LoginResponse::copyContext).toList();
    }

    private static AuthContextVO copyContext(AuthContextVO value) {
        if (value == null) {
            return null;
        }
        return AuthContextVO.builder()
                .id(value.getId())
                .primary(value.getPrimary())
                .tenantId(value.getTenantId())
                .tenantName(value.getTenantName())
                .companyId(value.getCompanyId())
                .companyName(value.getCompanyName())
                .roles(value.getRoles())
                .build();
    }

    private static UserVO copyUser(UserVO value) {
        return value == null
                ? null
                : UserVO.builder()
                        .id(value.getId())
                        .username(value.getUsername())
                        .displayName(value.getDisplayName())
                        .build();
    }

    /** Builder detaches public nested models before the private generated constructor. */
    public static class LoginResponseBuilder {
        /**
         * Store independent available contexts.
         *
         * @param values available contexts
         * @return this builder
         */
        public LoginResponseBuilder availableContexts(List<AuthContextVO> values) {
            availableContexts = copyContexts(values);
            return this;
        }
        /**
         * Store an independent active context.
         *
         * @param value active context
         * @return this builder
         */
        public LoginResponseBuilder activeContext(AuthContextVO value) {
            activeContext = copyContext(value);
            return this;
        }
        /**
         * Store an independent public account.
         *
         * @param value public account
         * @return this builder
         */
        public LoginResponseBuilder user(UserVO value) {
            user = copyUser(value);
            return this;
        }
    }

    /** Public account identity and display fields, with no credential or security epoch. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserVO implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        private String id;
        private String username;
        private String displayName;
    }
}
