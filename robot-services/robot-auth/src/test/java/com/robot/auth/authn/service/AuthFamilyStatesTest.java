package com.robot.auth.authn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Persisted state is an authority boundary: partial or corrupt JSON never supplies a session. */
class AuthFamilyStatesTest {
    private static final int SECRET_HEX_LENGTH = 64;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void completeStoredContractDecodesWithoutChangingItsCredentialOrIdentity() throws Exception {
        var state = AuthFamilyStates.decode(storedState().toString(), mapper);
        assertThat(state.userId()).isEqualTo("account");
        assertThat(state.contextId()).isEqualTo("membership");
        assertThat(state.accessToken()).isEqualTo("fixture-access");
        assertThat(state.accessDigest()).isEqualTo("a".repeat(SECRET_HEX_LENGTH));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "userId",
                "contextId",
                "accessToken",
                "accessDigest",
                "refreshDigest",
                "credentialFingerprint",
                "userSecurityVersion",
                "contextSecurityVersion"
            })
    void missingRequiredStoredFieldCannotBecomeAnAuthenticatedSession(String field)
            throws Exception {
        var state = storedState();
        state.remove(field);
        assertInvalid(state.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[", "not-json"})
    void malformedOrNullPersistenceFailsWithFixedInvalidCredentialResult(String payload) {
        assertInvalid(payload);
    }

    @Test
    void invalidDigestOrNegativeRevocationVersionCannotPassStorageBoundary() throws Exception {
        var badDigest = storedState();
        badDigest.put("accessDigest", "A".repeat(SECRET_HEX_LENGTH));
        assertInvalid(badDigest.toString());
        var badVersion = storedState();
        badVersion.put("contextSecurityVersion", -1);
        assertInvalid(badVersion.toString());
    }

    private ObjectNode storedState() {
        var state = mapper.createObjectNode();
        state.put("userId", "account");
        state.put("contextId", "membership");
        state.put("accessToken", "fixture-access");
        state.put("accessDigest", "a".repeat(SECRET_HEX_LENGTH));
        state.put("refreshDigest", "b".repeat(SECRET_HEX_LENGTH));
        state.put("credentialFingerprint", "c".repeat(SECRET_HEX_LENGTH));
        state.put("userSecurityVersion", 0);
        state.put("contextSecurityVersion", 0);
        return state;
    }

    private void assertInvalid(String payload) {
        var failure =
                assertThrows(
                        ServiceException.class, () -> AuthFamilyStates.decode(payload, mapper));
        assertThat(failure.getCode()).isEqualTo(ResultCode.AUTH_REFRESH_TOKEN_INVALID.getCode());
        assertThat(failure.getMessage()).doesNotContain(payload);
    }
}
