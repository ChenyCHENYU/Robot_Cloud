package com.robot.auth.authn.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;

/** Decode the stored family contract without accepting partial identities or credentials. */
final class AuthFamilyStates {
    private AuthFamilyStates() {}

    static AuthSessionManager.FamilyState decode(String encoded, ObjectMapper mapper) {
        try {
            AuthSessionManager.FamilyState state =
                    mapper.readValue(encoded, AuthSessionManager.FamilyState.class);
            if (state == null) {
                throw invalidState();
            }
            if (!validIdentity(state) || !validCredentials(state) || !validVersions(state)) {
                throw invalidState();
            }
            return state;
        } catch (JsonProcessingException invalidEncoding) {
            throw invalidState();
        }
    }

    private static boolean validIdentity(AuthSessionManager.FamilyState state) {
        return state.userId() != null && state.contextId() != null && state.accessToken() != null;
    }

    private static boolean validCredentials(AuthSessionManager.FamilyState state) {
        return AuthTokenSecrets.validSecret(state.accessDigest())
                && AuthTokenSecrets.validSecret(state.refreshDigest())
                && AuthTokenSecrets.validSecret(state.credentialFingerprint());
    }

    private static boolean validVersions(AuthSessionManager.FamilyState state) {
        return AuthSessionViews.validSecurityVersion(state.userSecurityVersion())
                && AuthSessionViews.validSecurityVersion(state.contextSecurityVersion());
    }

    private static ServiceException invalidState() {
        return new ServiceException(ResultCode.AUTH_REFRESH_TOKEN_INVALID);
    }
}
