package com.robot.auth.authn.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.dto.LoginResponse;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.redis.core.RedisHelper;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 独立会话及刷新族管理。族状态与审计事件的原子Lua命令是轮换及注销的提交点。 RPC复验先于提交；竞争失败的候选令牌必须撤销，不恢复旧凭据。 */
@Slf4j
@Component
public class AuthSessionManager {

    private static final String REVOKED_STATE = "{}";
    private static final int MAX_LOGOUT_CAS_ATTEMPTS = 8;

    private final RedisHelper redisHelper;
    private final AuthAccessTokens accessTokens;
    private final ObjectMapper objectMapper;
    private final AuthAuditQueue auditQueue;
    private final AuthCleanupWorker cleanupWorker;

    /**
     * Construct authority with bounded post-commit cleanup and durable audit storage.
     *
     * @param redis shared state/index storage
     * @param tokens access-token lifecycle boundary
     * @param mapper configured family-state codec
     * @param auditQueue durable atomic state/event queue
     * @param cleanupWorker bounded nonauthority cleanup executor
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AuthSessionManager(
            RedisHelper redis,
            AuthAccessTokens tokens,
            ObjectMapper mapper,
            AuthAuditQueue auditQueue,
            AuthCleanupWorker cleanupWorker) {
        this.redisHelper = redis;
        this.accessTokens = tokens;
        this.objectMapper = mapper;
        this.auditQueue = auditQueue;
        this.cleanupWorker = cleanupWorker;
    }

    /** 包内行为测试构造；生产装配始终使用有界后台清理。 */
    AuthSessionManager(
            RemoteSystemService remote,
            RedisHelper redis,
            AuthAccessTokens tokens,
            ObjectMapper mapper) {
        this(
                redis,
                tokens,
                mapper,
                new AuthAuditQueue(redis, remote, mapper),
                new AuthCleanupWorker(true));
    }

    /**
     * 登录或票据激活建立新的独立刷新族，不覆盖同账户其他设备。
     *
     * @param loginType 固定登录或激活操作名
     * @param user 当前可信账号
     * @param context 当前可信公司成员关系
     * @param allContexts 已复验的可选成员关系
     * @return 新令牌及独立前端视图
     */
    public LoginResponse issue(
            String loginType,
            RemoteUserDTO user,
            RemoteUserContextDTO context,
            List<RemoteUserContextDTO> allContexts) {
        String familyId = AuthTokenSecrets.randomToken();
        PreparedSession prepared = prepare(familyId, user, context);
        try {
            writeIndexes(prepared);
            boolean created =
                    auditQueue.commit(
                            AuthKeys.family(familyId),
                            null,
                            prepared.encoded,
                            AuthKeys.TTL_REFRESH_TOKEN,
                            auditQueue.event(
                                    loginType,
                                    user.getId(),
                                    user.getUsername(),
                                    context.getCompanyId(),
                                    1));
            ServiceAssert.isTrue(created, ResultCode.FAILED, "会话创建冲突，请重试");
        } catch (RuntimeException e) {
            abandon(prepared);
            throw e;
        }
        return AuthSessionViews.response(
                prepared.access, prepared.refreshToken, user, context, allContexts);
    }

    /**
     * 读取仍为当前版本的刷新凭据；此步骤不消费，允许下游故障后重试。
     *
     * @param refreshToken 持有者提供的精确刷新令牌
     * @return 对应当前族的受限快照
     */
    public RefreshSession requireRefresh(String refreshToken) {
        ServiceAssert.isTrue(
                AuthTokenSecrets.validSecret(refreshToken), ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        String digest = AuthTokenSecrets.digest(refreshToken);
        String familyId =
                redisHelper
                        .getString(AuthKeys.refreshDigest(digest))
                        .orElseThrow(
                                () -> new ServiceException(ResultCode.AUTH_REFRESH_TOKEN_INVALID));
        RefreshSession session = readFamily(familyId);
        ServiceAssert.isTrue(
                digest.equals(session.state.refreshDigest()),
                ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        return session;
    }

    /**
     * 已复验账号和成员后轮换；与其他刷新/切换/注销共享同一 CAS。
     *
     * @param session 当前族的已校验快照
     * @param user RPC复验后的当前账号
     * @param context RPC复验后的当前成员
     * @param allContexts 已复验的可用上下文
     * @return 当前族获胜的新会话视图
     */
    public LoginResponse refresh(
            RefreshSession session,
            RemoteUserDTO user,
            RemoteUserContextDTO context,
            List<RemoteUserContextDTO> allContexts) {
        verifySubject(session, user, context);
        return replace("refresh", session, user, context, allContexts);
    }

    /**
     * 切换上下文即轮换当前设备的 access/refresh，同账号其他设备不受影响。
     *
     * @param user 当前可信账号
     * @param context 新选择的有效成员关系
     * @param allContexts 已复验的可用上下文
     * @return 当前族获胜的新公司会话
     */
    public LoginResponse switchCurrent(
            RemoteUserDTO user,
            RemoteUserContextDTO context,
            List<RemoteUserContextDTO> allContexts) {
        String token = accessTokens.currentToken();
        ServiceAssert.notBlank(token, ResultCode.UNAUTHORIZED);
        String familyId =
                redisHelper
                        .getString(AuthKeys.accessFamily(token))
                        .orElseThrow(() -> new ServiceException(ResultCode.UNAUTHORIZED));
        RefreshSession session = readFamily(familyId);
        ServiceAssert.isTrue(
                AuthTokenSecrets.digest(token).equals(session.state.accessDigest()),
                ResultCode.UNAUTHORIZED);
        RemoteUserContextDTO source =
                allContexts.stream()
                        .filter(candidate -> session.contextId().equals(candidate.getContextId()))
                        .findFirst()
                        .orElse(null);
        verifySubject(session, user, source);
        return replace("switch", session, user, context, allContexts);
    }

    /** 持有当前 access 原文即可注销刷新族，包含 access 已自然过期的情形。 */
    public void logoutCurrent() {
        String token = accessTokens.currentToken();
        ServiceAssert.notBlank(token, ResultCode.UNAUTHORIZED);
        Optional<String> familyId = redisHelper.getString(AuthKeys.accessFamily(token));
        if (familyId.isPresent()) {
            revokeFamily(familyId.get(), AuthTokenSecrets.digest(token));
        }
        AuthCleanupWorker.bestEffort("注销访问令牌", () -> accessTokens.revoke(token));
        AuthCleanupWorker.bestEffort(
                "注销访问索引", () -> redisHelper.delete(AuthKeys.accessFamily(token)));
    }

    /**
     * 只撤销仍匹配此快照的族；过时的失效请求不能删除另一请求已提交的切换/刷新状态。
     *
     * @param session 需要撤销的精确族版本
     */
    public void revoke(RefreshSession session) {
        if (redisHelper.compareAndSetStringExact(
                AuthKeys.family(session.familyId),
                session.encoded,
                REVOKED_STATE,
                AuthKeys.TTL_REFRESH_TOKEN)) {
            cleanupState(session.state);
        }
    }

    private LoginResponse replace(
            String loginType,
            RefreshSession previous,
            RemoteUserDTO user,
            RemoteUserContextDTO context,
            List<RemoteUserContextDTO> allContexts) {
        PreparedSession prepared = prepare(previous.familyId, user, context);
        try {
            writeIndexes(prepared);
            boolean committed =
                    auditQueue.commit(
                            AuthKeys.family(previous.familyId),
                            previous.encoded,
                            prepared.encoded,
                            AuthKeys.TTL_REFRESH_TOKEN,
                            auditQueue.event(
                                    loginType,
                                    user.getId(),
                                    user.getUsername(),
                                    context.getCompanyId(),
                                    1));
            ServiceAssert.isTrue(committed, ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        } catch (RuntimeException e) {
            abandon(prepared);
            throw e;
        }
        cleanupWorker.submit(() -> cleanupState(previous.state));
        return AuthSessionViews.response(
                prepared.access, prepared.refreshToken, user, context, allContexts);
    }

    private PreparedSession prepare(
            String familyId, RemoteUserDTO user, RemoteUserContextDTO context) {
        AuthSessionViews.requireSecurityVersion(user.getSecurityVersion());
        AuthSessionViews.requireSecurityVersion(context.getSecurityVersion());
        String fingerprint = AuthSessionViews.credentialFingerprint(user);
        String refreshToken = AuthTokenSecrets.randomToken();
        AuthAccessTokens.IssuedAccess access =
                accessTokens.issue(
                        AuthSessionViews.loginUser(context, user), familyId, fingerprint);
        FamilyState state =
                new FamilyState(
                        user.getId(),
                        context.getContextId(),
                        access.token,
                        AuthTokenSecrets.digest(access.token),
                        AuthTokenSecrets.digest(refreshToken),
                        fingerprint,
                        user.getSecurityVersion(),
                        context.getSecurityVersion(),
                        user.getUsername(),
                        context.getCompanyId());
        try {
            return new PreparedSession(
                    familyId, refreshToken, access, state, objectMapper.writeValueAsString(state));
        } catch (JsonProcessingException e) {
            AuthCleanupWorker.bestEffort("撤销未序列化访问令牌", () -> accessTokens.revoke(access.token));
            throw new ServiceException(ResultCode.FAILED, "会话状态序列化失败");
        }
    }

    private void writeIndexes(PreparedSession prepared) {
        redisHelper.setStringExact(
                AuthKeys.refreshDigest(prepared.state.refreshDigest()),
                prepared.familyId,
                AuthKeys.TTL_REFRESH_TOKEN);
        redisHelper.setStringExact(
                AuthKeys.accessDigest(prepared.state.accessDigest()),
                prepared.familyId,
                AuthKeys.TTL_REFRESH_TOKEN);
    }

    /** CAS 结果不明时，仅将自己的候选状态标记撤销，绝不恢复或覆盖其他赢家。 */
    private void abandon(PreparedSession prepared) {
        AuthCleanupWorker.bestEffort(
                "撤销候选族状态",
                () ->
                        redisHelper.compareAndSetStringExact(
                                AuthKeys.family(prepared.familyId),
                                prepared.encoded,
                                REVOKED_STATE,
                                AuthKeys.TTL_REFRESH_TOKEN));
        cleanupState(prepared.state);
    }

    private RefreshSession readFamily(String familyId) {
        ServiceAssert.isTrue(
                AuthTokenSecrets.validSecret(familyId), ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        String encoded =
                redisHelper
                        .getString(AuthKeys.family(familyId))
                        .orElseThrow(
                                () -> new ServiceException(ResultCode.AUTH_REFRESH_TOKEN_INVALID));
        return new RefreshSession(familyId, encoded, decode(encoded));
    }

    private FamilyState decode(String encoded) {
        return AuthFamilyStates.decode(encoded, objectMapper);
    }

    private void verifySubject(
            RefreshSession session, RemoteUserDTO user, RemoteUserContextDTO context) {
        if (context == null
                || !session.state.userId().equals(user.getId())
                || !session.state.userSecurityVersion().equals(user.getSecurityVersion())
                || !session.state.contextId().equals(context.getContextId())
                || !session.state.contextSecurityVersion().equals(context.getSecurityVersion())
                || !AuthSessionViews.credentialFingerprint(user)
                        .equals(session.state.credentialFingerprint())) {
            revoke(session);
            throw new ServiceException(ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        }
    }

    private void revokeFamily(String familyId, String accessDigest) {
        ServiceAssert.isTrue(AuthTokenSecrets.validSecret(familyId), ResultCode.UNAUTHORIZED);
        for (int attempt = 0; attempt < MAX_LOGOUT_CAS_ATTEMPTS; attempt++) {
            Optional<String> current = redisHelper.getString(AuthKeys.family(familyId));
            if (current.isEmpty() || REVOKED_STATE.equals(current.get())) {
                return;
            }
            FamilyState state = decode(current.get());
            if (!accessDigest.equals(state.accessDigest())) {
                return;
            }
            LogoutIdentity identity = AuthLogoutAudit.identity(state);
            if (auditQueue.commit(
                    AuthKeys.family(familyId),
                    current.get(),
                    null,
                    AuthKeys.TTL_REFRESH_TOKEN,
                    auditQueue.event(
                            "logout",
                            identity.userId(),
                            identity.username(),
                            identity.companyId(),
                            1))) {
                cleanupWorker.submit(() -> cleanupState(state));
                return;
            }
        }
        throw new ServiceException(ResultCode.DATA_CONCURRENT_CONFLICT);
    }

    /** 族状态已提交；旧索引/SaToken清理失败不把一次成功轮换伪装成失败。 */
    private void cleanupState(FamilyState state) {
        AuthCleanupWorker.bestEffort(
                "清理刷新索引", () -> redisHelper.delete(AuthKeys.refreshDigest(state.refreshDigest())));
        AuthCleanupWorker.bestEffort(
                "清理访问索引", () -> redisHelper.delete(AuthKeys.accessDigest(state.accessDigest())));
        AuthCleanupWorker.bestEffort("清理访问令牌", () -> accessTokens.revoke(state.accessToken()));
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

        /**
         * Return the account bound to this validated family snapshot.
         *
         * @return trusted account ID
         */
        public String userId() {
            return state.userId();
        }

        /**
         * Return the membership bound to this validated family snapshot.
         *
         * @return trusted membership ID
         */
        public String contextId() {
            return state.contextId();
        }
    }

    record FamilyState(
            String userId,
            String contextId,
            String accessToken,
            String accessDigest,
            String refreshDigest,
            String credentialFingerprint,
            Long userSecurityVersion,
            Long contextSecurityVersion,
            String username,
            String companyId) {

        @Override
        public String toString() {
            return "FamilyState[凭据已隐藏]";
        }
    }

    record LogoutIdentity(String userId, String username, String companyId) {
        @Override
        public String toString() {
            return "LogoutIdentity[审计身份已隐藏]";
        }
    }

    private record PreparedSession(
            String familyId,
            String refreshToken,
            AuthAccessTokens.IssuedAccess access,
            FamilyState state,
            String encoded) {

        @Override
        public String toString() {
            return "PreparedSession[凭据已隐藏]";
        }
    }
}
