package com.robot.auth.authn.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 登录响应（前端 LoginData 契约）。
 *
 * <p>两种返回形态：
 * <ul>
 *   <li>直接会话：token/refreshToken/expiresIn/user/activeContext/availableContexts 齐备；</li>
 *   <li>待激活：token 为空串、expiresIn=0，携带 loginTicket + availableContexts，
 *       前端调 /auth/context/activate 换取完整会话。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 访问令牌（未激活形态为空串）。 */
    private String token;

    /** 刷新令牌（未激活形态为空串）。 */
    @Builder.Default
    private String refreshToken = "";

    /** 访问令牌有效期（秒；未激活形态为 0）。 */
    @Builder.Default
    private long expiresIn = 0L;

    /** 登录票据：身份已验证、待选择公司的短时一次性凭据。 */
    private String loginTicket;

    /** 可进入的企业工作上下文清单（角色只在激活后授予）。 */
    private List<AuthContextVO> availableContexts;

    /** 当前激活上下文（直接会话形态返回）。 */
    private AuthContextVO activeContext;

    /** 用户基本信息。 */
    private UserVO user;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserVO implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String username;
        private String displayName;
    }
}
