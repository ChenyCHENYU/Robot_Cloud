package com.robot.system.permission.service;

import com.robot.common.security.context.LoginUser;
import com.robot.common.security.session.SessionSubjectVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** System 请求实时核验主体和凭据快照；改密、停用或移出公司后旧 access 不能继续写入。 */
@Component
@RequiredArgsConstructor
public class SystemSessionSubjectVerifier implements SessionSubjectVerifier {

    private final ActiveCompanySubject subjects;

    @Override
    public boolean verify(LoginUser user, String credentialFingerprint) {
        if (!hasRevocationSnapshot(user, credentialFingerprint)) {
            return false;
        }
        ActiveCompanySubject.ActiveFacts current =
                subjects.loadActive(user.getUserId(), user.getCompanyId(), user.getContextId());
        if (current == null
                || current.credentialFingerprint() == null
                || !Objects.equals(current.accountVersion(), user.getUserSecurityVersion())
                || !Objects.equals(current.membershipVersion(), user.getContextSecurityVersion())) {
            return false;
        }
        return MessageDigest.isEqual(
                current.credentialFingerprint().getBytes(StandardCharsets.US_ASCII),
                credentialFingerprint.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean hasRevocationSnapshot(LoginUser user, String fingerprint) {
        return user != null
                && fingerprint != null
                && user.getContextId() != null
                && user.getUserSecurityVersion() != null
                && user.getContextSecurityVersion() != null;
    }
}
