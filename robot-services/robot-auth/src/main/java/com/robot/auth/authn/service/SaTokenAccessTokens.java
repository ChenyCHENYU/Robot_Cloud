package com.robot.auth.authn.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.util.AuthHolder;
import org.springframework.stereotype.Component;

/** 将公司、角色和刷新族登记到独立 token Session，支持同账号多设备。 */
@Component
class SaTokenAccessTokens implements AuthAccessTokens {

    @Override
    public IssuedAccess issue(LoginUser user, String familyId, String credentialFingerprint) {
        StpUtil.login(user.getUserId(), new SaLoginParameter().setIsShare(false));
        String token = StpUtil.getTokenValue();
        try {
            AuthHolder.setSessionAttr(AuthHolder.SESSION_LOGIN_USER, user);
            AuthHolder.setSessionAttr(AuthHolder.SESSION_AUTH_FAMILY, familyId);
            AuthHolder.setSessionAttr(AuthHolder.SESSION_AUTH_ACCESS_DIGEST,
                    AuthTokenSecrets.digest(token));
            AuthHolder.setSessionAttr(AuthHolder.SESSION_CREDENTIAL_FINGERPRINT, credentialFingerprint);
            return new IssuedAccess(token, StpUtil.getTokenTimeout());
        } catch (RuntimeException e) {
            StpUtil.logoutByTokenValue(token);
            throw e;
        }
    }

    @Override
    public String currentToken() {
        return StpUtil.getTokenValue();
    }

    @Override
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            StpUtil.logoutByTokenValue(token);
        }
    }
}
