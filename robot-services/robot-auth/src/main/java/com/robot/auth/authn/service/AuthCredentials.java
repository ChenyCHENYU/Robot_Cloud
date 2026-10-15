package com.robot.auth.authn.service;

import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.core.util.Digests;
import com.robot.common.core.util.PasswordPolicy;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Bounded credential verification and durable failure audit; context selection remains in
 * AuthService.
 */
@Component
public final class AuthCredentials {
    private static final int AUDIT_NAME_DIGEST_LENGTH = 16;
    private final RemoteSystemService remote;
    private final BCryptPasswordEncoder encoder;
    private final AuthAttemptGate attempts;
    private final AuthAuditQueue audit;
    private final String unknownUserHash;

    /**
     * Create the credential boundary with the configured bcrypt cost and one private dummy digest.
     *
     * @param remote trusted account supply
     * @param encoder configured bcrypt encoder
     * @param attempts shared bounded admission gate
     * @param audit reliable failure delivery queue
     */
    public AuthCredentials(
            RemoteSystemService remote,
            BCryptPasswordEncoder encoder,
            AuthAttemptGate attempts,
            AuthAuditQueue audit) {
        this.remote = remote;
        this.encoder = encoder;
        this.attempts = attempts;
        this.audit = audit;
        this.unknownUserHash = encoder.encode(AuthTokenSecrets.randomToken());
    }

    /**
     * Admit and verify credentials before revealing disabled-account state.
     *
     * @param username bounded username supplied by the validated controller command
     * @param password exact password, without truncation or normalization
     * @return trusted active account
     */
    public RemoteUserDTO verify(String username, String password) {
        attempts.check(username);
        RemoteUserDTO user = null;
        try {
            user = requireAccountResult(remote.getUserByUsername(username));
            if (user != null) {
                attempts.checkResolvedAccount(user.getId());
            }
            requireCredentials(user, password);
            return user;
        } catch (ServiceException error) {
            recordFailure(user, username, error);
            throw error;
        }
    }

    private static RemoteUserDTO requireAccountResult(Result<RemoteUserDTO> result) {
        if (result == null || !result.successValue()) {
            throw new ServiceException(ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        }
        return result.getData();
    }

    private void requireCredentials(RemoteUserDTO user, String password) {
        boolean matched =
                passwordMatches(password, user == null ? unknownUserHash : user.getPasswordHash());
        if (user == null || !matched) {
            throw new ServiceException(ResultCode.AUTH_LOGIN_FAILED);
        }
        ServiceAssert.isTrue(
                user.getStatusFlag() != null && user.getStatusFlag() == 1,
                ResultCode.AUTH_ACCOUNT_DISABLED);
    }

    /** Apply global/source admission to unauthenticated ticket and refresh commands. */
    public void checkAnonymous() {
        attempts.checkAnonymous();
    }

    private void recordFailure(RemoteUserDTO user, String username, ServiceException error) {
        if (error.getCode() == ResultCode.AUTH_LOGIN_FAILED.getCode()
                || error.getCode() == ResultCode.AUTH_ACCOUNT_DISABLED.getCode()) {
            audit.append(
                    audit.event(
                            "login",
                            user == null ? null : user.getId(),
                            "attempt-"
                                    + Digests.sha256Hex(username)
                                            .substring(0, AUDIT_NAME_DIGEST_LENGTH),
                            null,
                            0));
        }
    }

    private boolean passwordMatches(String raw, String hash) {
        if (raw == null
                || !StandardCharsets.UTF_8.newEncoder().canEncode(raw)
                || raw.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_UTF8_BYTES) {
            return false;
        }
        try {
            return encoder.matches(raw, hash);
        } catch (IllegalArgumentException invalidLegacyLength) {
            // No truncation fallback: historical overlong passwords require controlled reset.
            return false;
        }
    }
}
