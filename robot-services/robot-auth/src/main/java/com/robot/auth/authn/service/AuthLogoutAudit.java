package com.robot.auth.authn.service;

import com.robot.common.security.context.LoginUser;
import com.robot.common.security.util.AuthUtil;
import lombok.extern.slf4j.Slf4j;

/** Project bounded logout audit identity before the atomic revocation command. */
@Slf4j
final class AuthLogoutAudit {
    private AuthLogoutAudit() {}

    static AuthSessionManager.LogoutIdentity identity(AuthSessionManager.FamilyState state) {
        if (hasText(state.username()) && hasText(state.companyId())) {
            return storedIdentity(state);
        }
        try {
            LoginUser current = AuthUtil.getLoginUser();
            if (current != null
                    && state.userId().equals(current.getUserId())
                    && state.contextId().equals(current.getContextId())) {
                return new AuthSessionManager.LogoutIdentity(
                        state.userId(),
                        hasText(state.username()) ? state.username() : current.getUsername(),
                        hasText(state.companyId()) ? state.companyId() : current.getCompanyId());
            }
        } catch (RuntimeException error) {
            // A missing legacy snapshot must not prevent revocation using the stored identity.
            log.warn("旧族注销审计快照不可读取，保留族中的已有身份字段");
        }
        return storedIdentity(state);
    }

    private static AuthSessionManager.LogoutIdentity storedIdentity(
            AuthSessionManager.FamilyState state) {
        return new AuthSessionManager.LogoutIdentity(
                state.userId(), state.username(), state.companyId());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
