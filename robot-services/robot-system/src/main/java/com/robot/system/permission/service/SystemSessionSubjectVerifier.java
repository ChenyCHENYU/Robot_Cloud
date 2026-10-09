package com.robot.system.permission.service;

import com.robot.common.security.context.LoginUser;
import com.robot.common.core.util.Digests;
import com.robot.common.security.session.SessionSubjectVerifier;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** System 请求实时核验主体和凭据快照；改密、停用或移出公司后旧 access 不能继续写入。 */
@Component
@RequiredArgsConstructor
public class SystemSessionSubjectVerifier implements SessionSubjectVerifier {

    private final EffectiveRoleResolver roles;
    private final SysUserMapper users;
    private final SysUserCompanyMapper members;

    @Override
    public boolean verify(LoginUser user, String credentialFingerprint) {
        if (!hasRevocationSnapshot(user, credentialFingerprint)
                || !roles.isActiveSubject(user.getUserId(), user.getCompanyId())) {
            return false;
        }
        SysUser current = users.selectById(user.getUserId());
        if (!accountMatches(current, user)) {
            return false;
        }
        SysUserCompany member = members.selectById(user.getContextId());
        if (!membershipMatches(member, user)) {
            return false;
        }
        String expected = Digests.sha256Hex(current.getPasswordHash());
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                credentialFingerprint.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean hasRevocationSnapshot(LoginUser user, String fingerprint) {
        return user != null && fingerprint != null && user.getContextId() != null
                && user.getUserSecurityVersion() != null && user.getContextSecurityVersion() != null;
    }

    private static boolean accountMatches(SysUser current, LoginUser snapshot) {
        return current != null && current.getPasswordHash() != null
                && Objects.equals(current.getId(), snapshot.getUserId())
                && Integer.valueOf(1).equals(current.getDeleteFlag())
                && Integer.valueOf(1).equals(current.getStatusFlag())
                && Objects.equals(current.getSecurityVersion(), snapshot.getUserSecurityVersion());
    }

    private static boolean membershipMatches(SysUserCompany current, LoginUser snapshot) {
        return current != null && Objects.equals(current.getId(), snapshot.getContextId())
                && Objects.equals(current.getUserId(), snapshot.getUserId())
                && Objects.equals(current.getCompanyId(), snapshot.getCompanyId())
                && Integer.valueOf(1).equals(current.getDeleteFlag())
                && Integer.valueOf(1).equals(current.getStatusFlag())
                && Objects.equals(current.getSecurityVersion(), snapshot.getContextSecurityVersion());
    }
}
