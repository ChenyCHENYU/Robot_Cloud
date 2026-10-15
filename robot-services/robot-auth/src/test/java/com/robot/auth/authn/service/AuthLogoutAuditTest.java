package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.dto.LoginResponse;
import com.robot.common.core.domain.Result;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.util.AuthHolder;
import com.robot.common.security.util.AuthUtil;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 实际 Sa-Token 注销清除 token Session 后，审计仍须保留签发时的账号与公司。 */
class AuthLogoutAuditTest {
    private static final int IMMEDIATE_TOKEN_EXPIRY = -2;

    private StpLogic originalLogic;
    private SaTokenContext originalContext;
    private SaTokenDao originalDao;
    private SaTokenDaoDefaultImpl memoryDao;
    private AuthStateTestSupport.MemoryRedis redis;
    private RemoteSystemService remote;
    private AuthSessionManager sessions;
    private AuthAuditQueue auditQueue;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RemoteUserDTO user =
            RemoteUserDTO.builder()
                    .id("user-audit")
                    .username("audit-user")
                    .statusFlag(1)
                    .securityVersion(0L)
                    .passwordHash("test-only-password-hash")
                    .build();
    private final RemoteUserContextDTO context =
            RemoteUserContextDTO.builder()
                    .contextId("ctx-audit")
                    .companyId("company-audit")
                    .securityVersion(0L)
                    .roles(List.of())
                    .build();

    @BeforeEach
    void setUp() {
        originalLogic = StpUtil.getStpLogic();
        originalContext = SaManager.getSaTokenContext();
        originalDao = SaManager.getSaTokenDao();
        memoryDao = new SaTokenDaoDefaultImpl();
        StpLogic logic = new StpLogic("logout-audit-test");
        logic.setConfig(
                new SaTokenConfig()
                        .setTokenName("Authorization")
                        .setTokenPrefix("Bearer")
                        .setIsConcurrent(true)
                        .setIsShare(false)
                        .setIsReadCookie(false)
                        .setIsLog(false));
        SaManager.setSaTokenDao(memoryDao);
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        StpUtil.setStpLogic(logic);
        redis = new AuthStateTestSupport.MemoryRedis();
        remote = mock(RemoteSystemService.class);
        auditQueue = new AuthAuditQueue(redis, remote, mapper);
        sessions =
                new AuthSessionManager(
                        redis,
                        new SaTokenAccessTokens(),
                        mapper,
                        auditQueue,
                        new AuthCleanupWorker(true));
        when(remote.recordLogin(any())).thenReturn(Result.success());
        bind(null);
    }

    @AfterEach
    void restoreSaToken() {
        RequestContextHolder.resetRequestAttributes();
        StpUtil.setStpLogic(originalLogic);
        SaManager.setSaTokenContext(originalContext);
        SaManager.setSaTokenDao(originalDao);
        memoryDao.destroy();
    }

    @Test
    void normalLogoutLogsTrustedIdentityAfterTokenContextHasBeenCleared() {
        LoginResponse issued = login();
        bind(issued.getToken());
        assertNotNull(AuthUtil.getLoginUser());
        expectAuditAfterContextCleared();
        sessions.logoutCurrent();
        auditQueue.dispatch();
        verify(remote).recordLogin(any(RemoteLoginLogDTO.class));
        assertTrue(redis.entries.isEmpty());
    }

    @Test
    void expiredAccessStillLogsItsFamilyIdentityWithoutReadingAnExpiredSession() {
        LoginResponse issued = login();
        // Move actual DAO expiry deadlines into the past without invoking the logout lifecycle.
        memoryDao.updateObjectTimeout(
                StpUtil.getStpLogic().splicingKeyTokenValue(issued.getToken()),
                IMMEDIATE_TOKEN_EXPIRY);
        memoryDao.updateObjectTimeout(
                StpUtil.getStpLogic().splicingKeyTokenSession(issued.getToken()),
                IMMEDIATE_TOKEN_EXPIRY);
        bind(issued.getToken());
        assertNull(AuthUtil.getLoginUser());
        expectAuditAfterContextCleared();
        sessions.logoutCurrent();
        auditQueue.dispatch();
        verify(remote).recordLogin(any(RemoteLoginLogDTO.class));
        assertTrue(redis.entries.isEmpty());
    }

    @Test
    void legacyFamilyFallsBackToMatchingAuthenticatedContextBeforeCleanup() throws Exception {
        LoginResponse issued = login();
        omitFamilyAuditFields(issued);
        bind(issued.getToken());
        expectAuditAfterContextCleared();
        sessions.logoutCurrent();
        auditQueue.dispatch();
        verify(remote).recordLogin(any(RemoteLoginLogDTO.class));
    }

    @Test
    void legacyFamilyWithoutAuditFieldsCanRefreshAndCaptureTrustedAuditIdentity() throws Exception {
        LoginResponse old = login();
        omitFamilyAuditFields(old);
        bind(old.getToken());
        LoginResponse next =
                sessions.refresh(
                        sessions.requireRefresh(old.getRefreshToken()),
                        user,
                        context,
                        List.of(context));
        assertNotNull(sessions.requireRefresh(next.getRefreshToken()));
        auditQueue.dispatch();
        clearInvocations(remote);
        bind(next.getToken());
        expectAuditAfterContextCleared();
        sessions.logoutCurrent();
        auditQueue.dispatch();
        verify(remote).recordLogin(any(RemoteLoginLogDTO.class));
    }

    @Test
    void legacyFamilyDoesNotUseAnUnrelatedAuthenticatedContext() throws Exception {
        LoginResponse issued = login();
        omitFamilyAuditFields(issued);
        bind(issued.getToken());
        AuthHolder.setSessionAttr(
                AuthHolder.SESSION_LOGIN_USER,
                LoginUser.builder()
                        .userId(user.getId())
                        .contextId("different-context")
                        .username("unrelated-name")
                        .companyId("unrelated-company")
                        .build());
        doAnswer(
                        invocation -> {
                            RemoteLoginLogDTO dto = invocation.getArgument(0);
                            assertEquals(user.getId(), dto.getUserId());
                            assertNull(dto.getUsername());
                            assertNull(dto.getCompanyId());
                            return Result.success();
                        })
                .when(remote)
                .recordLogin(any());
        sessions.logoutCurrent();
        auditQueue.dispatch();
        verify(remote).recordLogin(any(RemoteLoginLogDTO.class));
    }

    @Test
    void repeatedOrRotatedOldTokenLogoutDoesNotAuditAgainOrRevokeSuccessor() {
        LoginResponse old = login();
        LoginResponse next =
                sessions.refresh(
                        sessions.requireRefresh(old.getRefreshToken()),
                        user,
                        context,
                        List.of(context));
        auditQueue.dispatch();
        clearInvocations(remote);
        bind(old.getToken());
        sessions.logoutCurrent();
        auditQueue.dispatch();
        verify(remote, never()).recordLogin(any());
        assertNotNull(sessions.requireRefresh(next.getRefreshToken()));
        bind(next.getToken());
        expectAuditAfterContextCleared();
        sessions.logoutCurrent();
        auditQueue.dispatch();
        sessions.logoutCurrent();
        auditQueue.dispatch();
        verify(remote).recordLogin(any(RemoteLoginLogDTO.class));
    }

    private LoginResponse login() {
        LoginResponse issued = sessions.issue("login", user, context, List.of(context));
        auditQueue.dispatch();
        clearInvocations(remote);
        return issued;
    }

    private void expectAuditAfterContextCleared() {
        doAnswer(
                        invocation -> {
                            RemoteLoginLogDTO dto = invocation.getArgument(0);
                            assertEquals("logout", dto.getLoginType());
                            assertEquals(user.getId(), dto.getUserId());
                            assertEquals(user.getUsername(), dto.getUsername());
                            assertEquals(context.getCompanyId(), dto.getCompanyId());
                            assertFalse(StpUtil.isLogin());
                            assertNull(AuthUtil.getLoginUser());
                            return Result.success();
                        })
                .when(remote)
                .recordLogin(any());
    }

    private void omitFamilyAuditFields(LoginResponse issued) throws Exception {
        String familyId =
                redis.getString(AuthKeys.refreshToken(issued.getRefreshToken())).orElseThrow();
        String key = AuthKeys.family(familyId);
        ObjectNode state = (ObjectNode) mapper.readTree(redis.getString(key).orElseThrow());
        state.remove(List.of("username", "companyId"));
        redis.setStringExact(key, mapper.writeValueAsString(state), AuthKeys.TTL_REFRESH_TOKEN);
    }

    private void bind(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }
}
