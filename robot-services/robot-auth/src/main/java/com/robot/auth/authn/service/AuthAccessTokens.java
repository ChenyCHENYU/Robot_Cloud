package com.robot.auth.authn.service;

import com.robot.common.security.context.LoginUser;

/** Sa-Token 生命周期边界：认证编排不直接操作全局静态组件。 */
interface AuthAccessTokens {

    IssuedAccess issue(LoginUser user, String familyId, String credentialFingerprint);

    String currentToken();

    void revoke(String token);

    /** 签发结果不实现自动 toString，避免日志无意打印访问令牌。 */
    final class IssuedAccess {

        final String token;
        final long expiresIn;

        IssuedAccess(String token, long expiresIn) {
            this.token = token;
            this.expiresIn = expiresIn;
        }
    }
}
