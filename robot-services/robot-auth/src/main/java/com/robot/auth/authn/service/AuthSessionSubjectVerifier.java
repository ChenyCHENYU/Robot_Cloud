package com.robot.auth.authn.service;

import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.session.SessionSubjectVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Auth 受保护请求也实时复验主体；用户供给 RPC 使用服务身份，不携带旧会话委托。 */
@Component
@RequiredArgsConstructor
public class AuthSessionSubjectVerifier implements SessionSubjectVerifier {

    private final RemoteSystemService remoteSystemService;

    /**
     * 用 System 当前账号与精确成员事实复验 token 快照，不修改族或消费任何凭据。
     *
     * @param user token Session 中由认证服务签发的主体与安全版本快照
     * @param credentialFingerprint 族状态保存的 BCrypt 摘要 SHA-256 指纹
     * @return 账号、成员、公司/租户、安全版本及凭据全部匹配时为 true；确定失效为 false
     * @throws com.robot.common.core.exception.ServiceException 系统供给不可用；不把临时故障当成已撤销身份
     */
    @Override
    public boolean verify(LoginUser user, String credentialFingerprint) {
        if (!validSnapshot(user) || !AuthTokenSecrets.validSecret(credentialFingerprint)) {
            return false;
        }
        RemoteUserDTO current = requireAvailable(remoteSystemService.getUserById(user.getUserId()));
        if (!sameAccount(user, current, credentialFingerprint)) {
            return false;
        }
        List<RemoteUserContextDTO> contexts = requireAvailable(
                remoteSystemService.getUserContexts(user.getUserId()));
        return contexts != null && contexts.stream().anyMatch(context -> sameContext(user, context));
    }

    private static boolean validSnapshot(LoginUser user) {
        return user != null && user.getUserId() != null && user.getContextId() != null
                && user.getCompanyId() != null && validVersion(user.getUserSecurityVersion())
                && validVersion(user.getContextSecurityVersion());
    }

    private static boolean validVersion(Long version) {
        return version != null && version >= 0;
    }

    private static boolean sameAccount(LoginUser snapshot, RemoteUserDTO current, String fingerprint) {
        if (current == null || !snapshot.getUserId().equals(current.getId())
                || !Integer.valueOf(1).equals(current.getStatusFlag())
                || !snapshot.getUserSecurityVersion().equals(current.getSecurityVersion())
                || current.getPasswordHash() == null || current.getPasswordHash().isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(AuthTokenSecrets.digest(current.getPasswordHash())
                .getBytes(StandardCharsets.US_ASCII), fingerprint.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean sameContext(LoginUser snapshot, RemoteUserContextDTO context) {
        return context != null && snapshot.getContextId().equals(context.getContextId())
                && snapshot.getCompanyId().equals(context.getCompanyId())
                && Objects.equals(snapshot.getTenantId(), context.getTenantId())
                && snapshot.getContextSecurityVersion().equals(context.getSecurityVersion());
    }

    private static <T> T requireAvailable(Result<T> result) {
        // 临时失败阻断本次读取，不消费 family，也不把系统故障报告为已撤销登录。
        ServiceAssert.isTrue(result != null && result.successValue(),
                ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        return result.getData();
    }
}
