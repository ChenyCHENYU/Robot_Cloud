package com.robot.auth.authn.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.dto.AuthContextVO;
import com.robot.auth.authn.dto.LoginResponse;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.util.AuthUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

/**
 * 独立会话及刷新族管理。族状态的单 key CAS 是轮换提交点，GETDEL 是注销提交点。
 * RPC 复验在提交之前进行；失去竞争的候选 token 必须撤销，不恢复已消费凭据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthSessionManager {

    private static final String REVOKED_STATE = "{}";

    private final RemoteSystemService remoteSystemService;
    private final RedisHelper redisHelper;
    private final AuthAccessTokens accessTokens;
    private final ObjectMapper objectMapper;

    /** 登录或票据激活建立新的独立刷新族，不覆盖同账户其他设备。 */
    public LoginResponse issue(String loginType, RemoteUserDTO user,
                               RemoteUserContextDTO context,
                               List<RemoteUserContextDTO> allContexts) {
        String familyId = AuthTokenSecrets.randomToken();
        PreparedSession prepared = prepare(familyId, user, context);
        try {
            writeIndexes(prepared);
            boolean created = redisHelper.setStringIfAbsentExact(AuthKeys.family(familyId),
                    prepared.encoded, AuthKeys.TTL_REFRESH_TOKEN);
            ServiceAssert.isTrue(created, ResultCode.FAILED, "会话创建冲突，请重试");
        } catch (RuntimeException e) {
            abandon(prepared);
            throw e;
        }
        return response(loginType, prepared, user, context, allContexts);
    }

    /** 读取仍为当前版本的刷新凭据；此步骤不消费，允许下游故障后重试。 */
    public RefreshSession requireRefresh(String refreshToken) {
        ServiceAssert.isTrue(AuthTokenSecrets.validSecret(refreshToken),
                ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        String digest = AuthTokenSecrets.digest(refreshToken);
        String familyId = redisHelper.getString(AuthKeys.refreshDigest(digest))
                .orElseThrow(() -> new ServiceException(ResultCode.AUTH_REFRESH_TOKEN_INVALID));
        RefreshSession session = readFamily(familyId);
        ServiceAssert.isTrue(digest.equals(session.state.refreshDigest()),
                ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        return session;
    }

    /** 已复验账号和成员后轮换；与其他刷新/切换/注销共享同一 CAS。 */
    public LoginResponse refresh(RefreshSession session, RemoteUserDTO user,
                                 RemoteUserContextDTO context,
                                 List<RemoteUserContextDTO> allContexts) {
        verifySubject(session, user, context);
        return replace("refresh", session, user, context, allContexts);
    }

    /** 切换上下文即轮换当前设备的 access/refresh，同账号其他设备不受影响。 */
    public LoginResponse switchCurrent(RemoteUserDTO user, RemoteUserContextDTO context,
                                       List<RemoteUserContextDTO> allContexts) {
        String token = accessTokens.currentToken();
        ServiceAssert.notBlank(token, ResultCode.UNAUTHORIZED);
        String familyId = redisHelper.getString(AuthKeys.accessFamily(token))
                .orElseThrow(() -> new ServiceException(ResultCode.UNAUTHORIZED));
        RefreshSession session = readFamily(familyId);
        ServiceAssert.isTrue(AuthTokenSecrets.digest(token).equals(session.state.accessDigest()),
                ResultCode.UNAUTHORIZED);
        RemoteUserContextDTO source = allContexts.stream()
                .filter(candidate -> session.contextId().equals(candidate.getContextId()))
                .findFirst().orElse(null);
        verifySubject(session, user, source);
        return replace("switch", session, user, context, allContexts);
    }

    /** 持有当前 access 原文即可注销刷新族，包含 access 已自然过期的情形。 */
    public void logoutCurrent() {
        String token = accessTokens.currentToken();
        ServiceAssert.notBlank(token, ResultCode.UNAUTHORIZED);
        Optional<String> familyId = redisHelper.getString(AuthKeys.accessFamily(token));
        if (familyId.isPresent()) {
            revokeFamily(familyId.get());
        }
        cleanup("注销访问令牌", () -> accessTokens.revoke(token));
        cleanup("注销访问索引", () -> redisHelper.delete(AuthKeys.accessFamily(token)));
    }

    /** 只撤销仍匹配此快照的族；过时的失效请求不能删除另一请求已提交的切换/刷新状态。 */
    public void revoke(RefreshSession session) {
        if (redisHelper.compareAndSetStringExact(AuthKeys.family(session.familyId),
                session.encoded, REVOKED_STATE, AuthKeys.TTL_REFRESH_TOKEN)) {
            cleanupState(session.state);
        }
    }

    private LoginResponse replace(String loginType, RefreshSession previous, RemoteUserDTO user,
                                   RemoteUserContextDTO context,
                                   List<RemoteUserContextDTO> allContexts) {
        PreparedSession prepared = prepare(previous.familyId, user, context);
        try {
            writeIndexes(prepared);
            boolean committed = redisHelper.compareAndSetStringExact(
                    AuthKeys.family(previous.familyId), previous.encoded, prepared.encoded,
                    AuthKeys.TTL_REFRESH_TOKEN);
            ServiceAssert.isTrue(committed, ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        } catch (RuntimeException e) {
            abandon(prepared);
            throw e;
        }
        cleanupState(previous.state);
        return response(loginType, prepared, user, context, allContexts);
    }

    private PreparedSession prepare(String familyId, RemoteUserDTO user,
                                     RemoteUserContextDTO context) {
        requireSecurityVersion(user.getSecurityVersion());
        requireSecurityVersion(context.getSecurityVersion());
        String fingerprint = credentialFingerprint(user);
        String refreshToken = AuthTokenSecrets.randomToken();
        AuthAccessTokens.IssuedAccess access = accessTokens.issue(
                buildLoginUser(context, user), familyId, fingerprint);
        FamilyState state = new FamilyState(user.getId(), context.getContextId(), access.token,
                AuthTokenSecrets.digest(access.token), AuthTokenSecrets.digest(refreshToken), fingerprint,
                user.getSecurityVersion(), context.getSecurityVersion(), user.getUsername(),
                context.getCompanyId());
        try {
            return new PreparedSession(familyId, refreshToken, access, state,
                    objectMapper.writeValueAsString(state));
        } catch (JsonProcessingException e) {
            cleanup("撤销未序列化访问令牌", () -> accessTokens.revoke(access.token));
            throw new ServiceException(ResultCode.FAILED, "会话状态序列化失败");
        }
    }

    private void writeIndexes(PreparedSession prepared) {
        redisHelper.setStringExact(AuthKeys.refreshDigest(prepared.state.refreshDigest()),
                prepared.familyId, AuthKeys.TTL_REFRESH_TOKEN);
        redisHelper.setStringExact(AuthKeys.accessDigest(prepared.state.accessDigest()),
                prepared.familyId, AuthKeys.TTL_REFRESH_TOKEN);
    }

    /** CAS 结果不明时，仅将自己的候选状态标记撤销，绝不恢复或覆盖其他赢家。 */
    private void abandon(PreparedSession prepared) {
        cleanup("撤销候选族状态", () -> redisHelper.compareAndSetStringExact(
                AuthKeys.family(prepared.familyId), prepared.encoded, REVOKED_STATE,
                AuthKeys.TTL_REFRESH_TOKEN));
        cleanupState(prepared.state);
    }

    private RefreshSession readFamily(String familyId) {
        ServiceAssert.isTrue(AuthTokenSecrets.validSecret(familyId),
                ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        String encoded = redisHelper.getString(AuthKeys.family(familyId))
                .orElseThrow(() -> new ServiceException(ResultCode.AUTH_REFRESH_TOKEN_INVALID));
        return new RefreshSession(familyId, encoded, decode(encoded));
    }

    private FamilyState decode(String encoded) {
        try {
            FamilyState state = objectMapper.readValue(encoded, FamilyState.class);
            ServiceAssert.isTrue(state != null && state.userId() != null && state.contextId() != null
                            && state.accessToken() != null
                            && AuthTokenSecrets.validSecret(state.accessDigest())
                            && AuthTokenSecrets.validSecret(state.refreshDigest())
                            && AuthTokenSecrets.validSecret(state.credentialFingerprint())
                            && validSecurityVersion(state.userSecurityVersion())
                            && validSecurityVersion(state.contextSecurityVersion()),
                    ResultCode.AUTH_REFRESH_TOKEN_INVALID);
            return state;
        } catch (JsonProcessingException e) {
            throw new ServiceException(ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        }
    }

    private void verifySubject(RefreshSession session, RemoteUserDTO user,
                               RemoteUserContextDTO context) {
        if (context == null || !session.state.userId().equals(user.getId())
                || !session.state.userSecurityVersion().equals(user.getSecurityVersion())
                || !session.state.contextId().equals(context.getContextId())
                || !session.state.contextSecurityVersion().equals(context.getSecurityVersion())
                || !credentialFingerprint(user).equals(session.state.credentialFingerprint())) {
            revoke(session);
            throw new ServiceException(ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        }
    }

    private static boolean validSecurityVersion(Long version) {
        return version != null && version >= 0;
    }

    private static void requireSecurityVersion(Long version) {
        ServiceAssert.isTrue(validSecurityVersion(version), ResultCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    private String credentialFingerprint(RemoteUserDTO user) {
        ServiceAssert.notBlank(user.getPasswordHash(), ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        return AuthTokenSecrets.digest(user.getPasswordHash());
    }

    private void revokeFamily(String familyId) {
        ServiceAssert.isTrue(AuthTokenSecrets.validSecret(familyId), ResultCode.UNAUTHORIZED);
        Optional<String> consumed = redisHelper.consumeString(AuthKeys.family(familyId));
        if (consumed.isEmpty() || REVOKED_STATE.equals(consumed.get())) {
            return;
        }
        FamilyState state = decode(consumed.get());
        LogoutIdentity identity = snapshotLogoutIdentity(state);
        cleanupState(state);
        recordLoginLog("logout", identity.userId(), identity.username(), identity.companyId(), 1);
    }

    /** 先固定签发时的可信审计身份；旧族仅从仍可读取且精确匹配的 token Session 补缺失字段。 */
    private LogoutIdentity snapshotLogoutIdentity(FamilyState state) {
        if (hasText(state.username()) && hasText(state.companyId())) {
            return new LogoutIdentity(state.userId(), state.username(), state.companyId());
        }
        try {
            LoginUser current = AuthUtil.getLoginUser();
            if (current != null && state.userId().equals(current.getUserId())
                    && state.contextId().equals(current.getContextId())) {
                return new LogoutIdentity(state.userId(),
                        hasText(state.username()) ? state.username() : current.getUsername(),
                        hasText(state.companyId()) ? state.companyId() : current.getCompanyId());
            }
        } catch (RuntimeException error) {
            // 注销已原子提交；兼容旧族的审计补全故障不能阻断 token 清理。
            log.warn("旧族注销审计快照不可读取，保留族中的已有身份字段");
        }
        return new LogoutIdentity(state.userId(), state.username(), state.companyId());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 族状态已提交；旧索引/SaToken清理失败不把一次成功轮换伪装成失败。 */
    private void cleanupState(FamilyState state) {
        cleanup("清理刷新索引", () -> redisHelper.delete(AuthKeys.refreshDigest(state.refreshDigest())));
        cleanup("清理访问索引", () -> redisHelper.delete(AuthKeys.accessDigest(state.accessDigest())));
        cleanup("清理访问令牌", () -> accessTokens.revoke(state.accessToken()));
    }

    private void cleanup(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("{}失败，族状态仍为鉴权事实源；索引按 TTL 过期", operation);
        }
    }

    private LoginResponse response(String loginType, PreparedSession prepared, RemoteUserDTO user,
                                    RemoteUserContextDTO context,
                                    List<RemoteUserContextDTO> allContexts) {
        recordLoginLog(loginType, user.getId(), user.getUsername(), context.getCompanyId(), 1);
        return LoginResponse.builder()
                .token(prepared.access.token).refreshToken(prepared.refreshToken)
                .expiresIn(prepared.access.expiresIn)
                .availableContexts(allContexts.stream().map(c -> AuthContextVO.fromRemote(c, true)).toList())
                .activeContext(AuthContextVO.fromRemote(context, true))
                .user(LoginResponse.UserVO.builder().id(user.getId()).username(user.getUsername())
                        .displayName(user.getNickname()).build()).build();
    }

    private LoginUser buildLoginUser(RemoteUserContextDTO context, RemoteUserDTO user) {
        return LoginUser.builder().userId(user.getId()).username(user.getUsername())
                .displayName(user.getNickname()).tenantId(context.getTenantId())
                .companyId(context.getCompanyId()).contextId(context.getContextId())
                .userSecurityVersion(user.getSecurityVersion())
                .contextSecurityVersion(context.getSecurityVersion())
                .roleCodes(context.getRoles() == null ? List.of() : context.getRoles().stream()
                        .map(RemoteUserContextDTO.RoleItem::getCode).toList())
                .superAdmin(context.getRoles() != null && context.getRoles().stream()
                        .anyMatch(r -> RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(r.getCode())))
                .build();
    }

    private void recordLoginLog(String loginType, String userId, String username,
                                String companyId, int status) {
        try {
            String ip = null;
            String userAgent = null;
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                ip = attributes.getRequest().getRemoteAddr();
                userAgent = attributes.getRequest().getHeader("User-Agent");
            }
            remoteSystemService.recordLogin(RemoteLoginLogDTO.builder().userId(userId).username(username)
                    .companyId(companyId).loginType(loginType).status(status).ip(ip).userAgent(userAgent).build());
        } catch (RuntimeException e) {
            log.warn("登录审计投递失败，认证结果不变");
        }
    }

    /** 不输出凭据的受限快照，供编排层在无副作用 RPC 后提交。 */
    public static final class RefreshSession {

        private final String familyId;
        private final String encoded;
        private final FamilyState state;

        private RefreshSession(String familyId, String encoded, FamilyState state) {
            this.familyId = familyId;
            this.encoded = encoded;
            this.state = state;
        }

        public String userId() {
            return state.userId();
        }

        public String contextId() {
            return state.contextId();
        }
    }

    private record FamilyState(String userId, String contextId, String accessToken,
                               String accessDigest, String refreshDigest, String credentialFingerprint,
                               Long userSecurityVersion, Long contextSecurityVersion,
                               String username, String companyId) {

        @Override
        public String toString() {
            return "FamilyState[凭据已隐藏]";
        }
    }

    private record LogoutIdentity(String userId, String username, String companyId) {
        @Override
        public String toString() {
            return "LogoutIdentity[审计身份已隐藏]";
        }
    }

    private record PreparedSession(String familyId, String refreshToken,
                                   AuthAccessTokens.IssuedAccess access, FamilyState state, String encoded) {

        @Override
        public String toString() {
            return "PreparedSession[凭据已隐藏]";
        }
    }
}
