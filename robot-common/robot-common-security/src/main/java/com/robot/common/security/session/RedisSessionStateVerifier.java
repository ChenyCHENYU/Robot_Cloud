package com.robot.common.security.session;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.util.Digests;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.redis.key.RedisKeys;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.util.AuthHolder;
import org.springframework.beans.factory.ObjectProvider;

/** 单一刷新族状态是请求态事实源：不存在、已撤销或当前 access 摘要不匹配均拒绝。 即使 Sa-Token 映射清理失败，也不会接受族已失效的旧访问令牌。 */
public class RedisSessionStateVerifier implements SessionStateVerifier {

    private final RedisHelper redisHelper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<SessionSubjectVerifier> subjectVerifiers;

    /**
     * Construct the isolated family verifier for explicit storage-boundary tests.
     *
     * @param redisHelper shared state storage
     * @param objectMapper configured state codec
     */
    public RedisSessionStateVerifier(RedisHelper redisHelper, ObjectMapper objectMapper) {
        this(redisHelper, objectMapper, null);
    }

    /**
     * Bind current family authority and every registered subject policy.
     *
     * @param redisHelper shared state storage
     * @param objectMapper configured state codec
     * @param subjectVerifiers required service-owned current subject policies
     */
    public RedisSessionStateVerifier(
            RedisHelper redisHelper,
            ObjectMapper objectMapper,
            ObjectProvider<SessionSubjectVerifier> subjectVerifiers) {
        this.redisHelper = redisHelper;
        this.objectMapper = objectMapper;
        this.subjectVerifiers = subjectVerifiers;
    }

    @Override
    public void verify() {
        verify(
                AuthHolder.getSessionAttr(AuthHolder.SESSION_AUTH_FAMILY, String.class),
                StpUtil.getTokenValue(),
                AuthHolder.getSessionAttr(AuthHolder.SESSION_LOGIN_USER, LoginUser.class));
    }

    /** 显式参数缝仅用于验证真实 Redis 读取决策，不信任请求中的族标识。 */
    void verify(String familyId, String token) {
        verify(familyId, token, null);
    }

    void verify(String familyId, String token, LoginUser user) {
        if (familyId == null || token == null || user == null) {
            throw invalidSession();
        }
        requireValid(familyId.matches("[a-f0-9]{64}") && !token.isBlank());
        JsonNode state = readState(familyId);
        String currentDigest = state.path("accessDigest").textValue();
        requireValid(currentDigest != null && Digests.sha256Hex(token).equals(currentDigest));
        verifySnapshot(state, user);
        if (subjectVerifiers != null) {
            String fingerprint = state.path("credentialFingerprint").textValue();
            subjectVerifiers
                    .orderedStream()
                    .forEach(verifier -> requireValid(verifier.verify(user, fingerprint)));
        }
    }

    private JsonNode readState(String familyId) {
        String encoded =
                redisHelper
                        .getString(RedisKeys.base("auth") + ":{sessions}:v2:family:" + familyId)
                        .orElseThrow(this::invalidSession);
        try {
            JsonNode state = objectMapper.readTree(encoded);
            if (state == null) {
                throw invalidSession();
            }
            return state;
        } catch (JsonProcessingException e) {
            throw invalidSession();
        }
    }

    private void verifySnapshot(JsonNode state, LoginUser user) {
        if (state == null || user == null) {
            throw invalidSession();
        }
        requireValid(
                user.getUserId() != null
                        && user.getContextId() != null
                        && user.getUserId().equals(state.path("userId").textValue())
                        && user.getContextId().equals(state.path("contextId").textValue()));
        requireValid(
                versionMatches(state.path("userSecurityVersion"), user.getUserSecurityVersion())
                        && versionMatches(
                                state.path("contextSecurityVersion"),
                                user.getContextSecurityVersion()));
        String fingerprint = state.path("credentialFingerprint").textValue();
        requireValid(fingerprint != null && fingerprint.matches("[a-f0-9]{64}"));
    }

    private static boolean versionMatches(JsonNode value, Long expected) {
        return expected != null
                && expected >= 0
                && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() == expected;
    }

    private void requireValid(boolean valid) {
        if (!valid) {
            throw invalidSession();
        }
    }

    private NotLoginException invalidSession() {
        return new NotLoginException("登录状态已失效", StpUtil.TYPE, NotLoginException.INVALID_TOKEN);
    }
}
