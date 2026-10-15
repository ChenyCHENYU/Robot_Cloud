package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.handler.NotLoginExceptionHandler;
import com.robot.common.security.util.AuthHolder;
import com.robot.common.web.handler.GlobalExceptionHandler;
import com.robot.system.api.dto.cache.CacheOutboxReplayReason;
import com.robot.system.api.entity.SysRole;
import com.robot.system.permission.controller.CacheOutboxGovernanceController;
import com.robot.system.support.SystemKeys;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 真 Spring 事务代理/H2 行锁与回滚 + 真 Sa-Token 内存 DAO/MVC 注解授权；Redis 仅替换外部 IO。 */
class CacheOutboxGovernanceIntegrationTest {
    private static final int BARRIER_TIMEOUT_SECONDS = 5;
    private static final int OLD_EVENT_AGE_DAYS = 60;
    private static final int RETENTION_DAYS = 30;
    private static final int ARCHIVE_BATCH_SIZE = 100;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private PermissionStateStore store;
    private CacheOutboxGovernanceService service;
    private EffectiveRoleResolver resolver;
    private RedisHelper redis;
    private TransactionTemplate transactions;
    private MockMvc mvc;
    private String token;
    private final AtomicBoolean hasPermission = new AtomicBoolean(true);
    private StpLogic originalLogic;
    private SaTokenContext originalContext;
    private SaTokenDao originalDao;
    private StpInterface originalPermissions;
    private SaTokenDaoDefaultImpl memoryDao;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(CacheOutboxTestConfiguration.class);
        jdbc = context.getBean(JdbcTemplate.class);
        store = context.getBean(PermissionStateStore.class);
        service = context.getBean(CacheOutboxGovernanceService.class);
        resolver = context.getBean(EffectiveRoleResolver.class);
        redis = context.getBean(RedisHelper.class);
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        when(resolver.resolvePermanent(anyString(), anyString())).thenReturn(superRoles());
        setupSaToken();
        token = login("operator", "company");
        mvc =
                MockMvcBuilders.standaloneSetup(new CacheOutboxGovernanceController(service))
                        .addInterceptors(new SaInterceptor(handler -> StpUtil.checkLogin()))
                        .setControllerAdvice(
                                new NotLoginExceptionHandler(), new GlobalExceptionHandler())
                        .build();
    }

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        StpUtil.setStpLogic(originalLogic);
        SaManager.setSaTokenContext(originalContext);
        SaManager.setSaTokenDao(originalDao);
        SaManager.setStpInterface(originalPermissions);
        memoryDao.destroy();
        context.close();
    }

    @Test
    void matchingPreviewQueuesAndAuditsWithoutRedisOrChangingEpoch() {
        String id = enqueue();
        var preview = service.preview(id, CacheOutboxReplayReason.OPERATOR_RETRY);
        assertEquals(0, auditCount());
        assertEquals(0, store.find(id).revision());
        var result = service.replay(id, preview.reason(), preview.planHash());
        assertEquals(1, result.revision());
        assertEquals(1, auditCount());
        assertEquals("operator", store.replayAudits(id).get(0).actorUserId());
        assertEquals("company", store.replayAudits(id).get(0).actorCompanyId());
        assertEquals(1, store.currentVersion(PermissionStateStore.PERMISSIONS));
        verifyNoInteractions(redis);
        verify(resolver, times(2)).resolvePermanent("operator", "company");
        verify(resolver, never()).resolve(anyString(), anyString());
    }

    @Test
    void driftOrReasonChangeRejectsWithoutChangingScheduleOrWritingAudit() {
        String id = enqueue();
        var preview = service.preview(id, CacheOutboxReplayReason.OPERATOR_RETRY);
        assertConflict(
                () ->
                        service.replay(
                                id,
                                CacheOutboxReplayReason.DEPENDENCY_RECOVERED,
                                preview.planHash()));
        transactions.executeWithoutResult(
                status ->
                        store.retry(
                                store.find(id),
                                CacheOutboxFailure.Category.REDIS_FAILURE,
                                UUID.randomUUID().toString(),
                                LocalDateTime.now().plusHours(1)));
        var scheduled = store.find(id);
        assertConflict(() -> service.replay(id, preview.reason(), preview.planHash()));
        assertEquals(scheduled.nextAttemptAt(), store.find(id).nextAttemptAt());
        assertEquals(1, store.find(id).revision());
        assertEquals(0, auditCount());
        verifyNoInteractions(redis);
    }

    @Test
    void previewCannotBeTransferredToAnotherAuthenticatedActor() {
        String id = enqueue();
        var preview = service.preview(id, CacheOutboxReplayReason.OPERATOR_RETRY);
        bind(login("another-operator", "company"));
        assertConflict(() -> service.replay(id, preview.reason(), preview.planHash()));
        assertEquals(0, auditCount());
    }

    @Test
    void auditInsertFailureRollsBackRescheduleWithSafeFailure() {
        String id = enqueue();
        transactions.executeWithoutResult(status -> assertTrue(store.complete(store.find(id))));
        var original = store.find(id);
        var preview = service.preview(id, CacheOutboxReplayReason.DEPENDENCY_RECOVERED);
        jdbc.execute("DROP TABLE sys_cache_outbox_replay_log");
        var error =
                assertThrows(
                        ServiceException.class,
                        () -> service.replay(id, preview.reason(), preview.planHash()));
        assertEquals(ResultCode.FAILED.getCode(), error.getCode());
        assertFalse(error.getMessage().contains("INSERT"));
        assertEquals(original.revision(), store.find(id).revision());
        assertEquals(original.completedAt(), store.find(id).completedAt());
        verifyNoInteractions(redis);
    }

    @Test
    void oldCompletionAndFailureCannotOverwriteConfirmedReplay() {
        String id = enqueue();
        var stale = store.find(id);
        var preview = service.preview(id, CacheOutboxReplayReason.OPERATOR_RETRY);
        service.replay(id, preview.reason(), preview.planHash());
        transactions.executeWithoutResult(
                status -> {
                    assertFalse(store.complete(stale));
                    assertFalse(
                            store.retry(
                                    stale,
                                    CacheOutboxFailure.Category.REDIS_FAILURE,
                                    UUID.randomUUID().toString(),
                                    LocalDateTime.now().plusHours(1)));
                });
        assertEquals(1, store.find(id).revision());
        assertEquals(0, store.find(id).attempts());
        assertNull(store.find(id).completedAt());
    }

    @Test
    void twoInstancesConfirmingSamePreviewHaveOnlyOneReplayAndAudit() throws Exception {
        String id = enqueue();
        var preview = service.preview(id, CacheOutboxReplayReason.OPERATOR_RETRY);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first =
                    executor.submit(() -> replayAfterBarrier(id, preview.planHash(), ready, start));
            var second =
                    executor.submit(() -> replayAfterBarrier(id, preview.planHash(), ready, start));
            assertTrue(ready.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(
                    1,
                    first.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            + second.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, auditCount());
        assertEquals(1, store.find(id).revision());
    }

    @Test
    void malformedOrForeignDomainEventsCannotBePreviewedOrSent() {
        String id =
                transactions.execute(
                        status -> store.enqueue("dictionary", "auth:family:private", 1));
        assertEquals(
                ResultCode.PARAM_INVALID.getCode(),
                assertThrows(
                                ServiceException.class,
                                () -> service.preview(id, CacheOutboxReplayReason.OPERATOR_RETRY))
                        .getCode());
        context.getBean(CacheEventDispatcher.class).retryPending();
        assertEquals("INVALID_EVENT", store.find(id).errorCategory());
        assertNotNull(store.find(id).failureId());
        verifyNoInteractions(redis);
    }

    @Test
    void permanentRoleGuardDoesNotTrustAnEffectiveOrSessionSuperFlag() {
        when(resolver.resolvePermanent("operator", "company"))
                .thenReturn(
                        new EffectiveRoleResolver.EffectiveRoles(false, List.of(role("STAFF"))));
        assertEquals(
                ResultCode.FORBIDDEN.getCode(),
                assertThrows(ServiceException.class, service::summary).getCode());
        verify(resolver, never()).resolve(anyString(), anyString());
    }

    @Test
    void mvcRequiresLoginAndDedicatedAnnotationPermissionBeforeGovernance() throws Exception {
        mvc.perform(get("/sys/cache-outbox/summary").header("X-Inner-Company", "company"))
                .andExpect(status().isUnauthorized());
        hasPermission.set(false);
        mvc.perform(get("/sys/cache-outbox/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("code").value(ResultCode.FORBIDDEN.getCode()));
        verifyNoInteractions(resolver);
        hasPermission.set(true);
        mvc.perform(get("/sys/cache-outbox/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0));
    }

    @Test
    void mvcValidatesPreviewConfirmationAndDoesNotExposeRedisKey() throws Exception {
        String id = enqueue();
        mvc.perform(
                        post("/sys/cache-outbox/" + id + "/replay")
                                .header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .content(
                                        "{\"reason\":\"OPERATOR_RETRY\",\"planHash\":\"invalid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(ResultCode.PARAM_INVALID.getCode()));
        mvc.perform(get("/sys/cache-outbox/events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data[0].cacheKey").doesNotExist());
        assertEquals(0, auditCount());
    }

    private int replayAfterBarrier(
            String id, String hash, CountDownLatch ready, CountDownLatch start) throws Exception {
        bind(token);
        try {
            ready.countDown();
            assertTrue(start.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            try {
                service.replay(id, CacheOutboxReplayReason.OPERATOR_RETRY, hash);
                return 1;
            } catch (ServiceException conflict) {
                assertEquals(ResultCode.DATA_CONCURRENT_CONFLICT.getCode(), conflict.getCode());
                return 0;
            }
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private String enqueue() {
        return transactions.execute(
                status ->
                        store.enqueue(
                                PermissionStateStore.PERMISSIONS,
                                SystemKeys.permissionVersion(),
                                1));
    }

    @Test
    void retentionArchiveOnlyTouchesCompletedOldHistoryAndKeepsAuditAndReplay() {
        String old = enqueue(), pending = enqueue(), recent = enqueue();
        transactions.executeWithoutResult(
                status -> {
                    assertTrue(store.complete(store.find(old)));
                    assertTrue(store.complete(store.find(recent)));
                });
        jdbc.update(
                "UPDATE sys_cache_outbox SET completed_at=? WHERE id=?",
                LocalDateTime.now().minusDays(OLD_EVENT_AGE_DAYS),
                old);
        var preview = service.previewArchive(RETENTION_DAYS, ARCHIVE_BATCH_SIZE);
        assertEquals(
                List.of(old),
                preview.events().stream()
                        .map(com.robot.system.api.vo.cache.CacheOutboxEventView::eventId)
                        .toList());
        assertEquals(1, service.archive(RETENTION_DAYS, ARCHIVE_BATCH_SIZE, preview.planHash()));
        assertNotNull(store.find(old).archivedAt());
        assertNull(store.find(pending).archivedAt());
        assertNull(store.find(recent).archivedAt());
        assertEquals("RETENTION_ARCHIVE", store.replayAudits(old).get(0).reason());
        assertNotNull(store.find(old).completedAt(), "archival keeps source and completed fact");
        var replay = service.preview(old, CacheOutboxReplayReason.OPERATOR_RETRY);
        service.replay(old, CacheOutboxReplayReason.OPERATOR_RETRY, replay.planHash());
        assertNull(store.find(old).archivedAt());
        assertNull(store.find(old).completedAt());
        assertEquals(2, store.replayAudits(old).size());
    }

    @Test
    void replayDriftRejectsOldArchivePlanWithNoArchiveOrSuccessAudit() {
        String id = enqueue();
        transactions.executeWithoutResult(status -> assertTrue(store.complete(store.find(id))));
        jdbc.update(
                "UPDATE sys_cache_outbox SET completed_at=? WHERE id=?",
                LocalDateTime.now().minusDays(OLD_EVENT_AGE_DAYS),
                id);
        var preview = service.previewArchive(RETENTION_DAYS, ARCHIVE_BATCH_SIZE);
        transactions.executeWithoutResult(status -> assertTrue(store.reschedule(store.find(id))));
        assertThrows(
                ServiceException.class,
                () -> service.archive(RETENTION_DAYS, ARCHIVE_BATCH_SIZE, preview.planHash()));
        assertNull(store.find(id).archivedAt());
        assertNull(store.find(id).completedAt());
        assertTrue(store.replayAudits(id).isEmpty());
    }

    private int auditCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_cache_outbox_replay_log", Integer.class);
    }

    private void assertConflict(Runnable action) {
        assertEquals(
                ResultCode.DATA_CONCURRENT_CONFLICT.getCode(),
                assertThrows(ServiceException.class, action::run).getCode());
    }

    private static SysRole role(String code) {
        SysRole role = new SysRole();
        role.setId("role");
        role.setRoleCode(code);
        return role;
    }

    private static EffectiveRoleResolver.EffectiveRoles superRoles() {
        return new EffectiveRoleResolver.EffectiveRoles(true, List.of(role("SUPER_ADMIN")));
    }

    private void setupSaToken() {
        originalLogic = StpUtil.getStpLogic();
        originalContext = SaManager.getSaTokenContext();
        originalDao = SaManager.getSaTokenDao();
        originalPermissions = SaManager.getStpInterface();
        memoryDao = new SaTokenDaoDefaultImpl();
        StpLogic logic = new StpLogic(StpUtil.TYPE);
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
        SaManager.setStpInterface(
                new StpInterface() {
                    @Override
                    public List<String> getPermissionList(Object loginId, String loginType) {
                        return hasPermission.get() ? List.of("sys:cache-outbox:manage") : List.of();
                    }

                    @Override
                    public List<String> getRoleList(Object loginId, String loginType) {
                        return List.of();
                    }
                });
        StpUtil.setStpLogic(logic);
    }

    private String login(String user, String company) {
        bind(null);
        StpUtil.login(user);
        AuthHolder.setSessionAttr(
                AuthHolder.SESSION_LOGIN_USER,
                LoginUser.builder().userId(user).companyId(company).superAdmin(true).build());
        String value = StpUtil.getTokenValue();
        bind(value);
        return value;
    }

    private void bind(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (value != null) {
            request.addHeader("Authorization", "Bearer " + value);
        }
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }
}
