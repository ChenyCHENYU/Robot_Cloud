package com.robot.system.permission.service;

import com.robot.system.api.dto.cache.CacheOutboxReplayReason;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.handler.NotLoginExceptionHandler;
import com.robot.common.security.util.AuthHolder;
import com.robot.common.web.handler.GlobalExceptionHandler;
import com.robot.system.api.entity.SysRole;
import com.robot.system.permission.controller.CacheOutboxGovernanceController;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.support.SystemKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真 Spring 事务代理/H2 行锁与回滚 + 真 Sa-Token 内存 DAO/MVC 注解授权；Redis 仅替换外部 IO。 */
class CacheOutboxGovernanceIntegrationTest {
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
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = context.getBean(JdbcTemplate.class);
        store = context.getBean(PermissionStateStore.class);
        service = context.getBean(CacheOutboxGovernanceService.class);
        resolver = context.getBean(EffectiveRoleResolver.class);
        redis = context.getBean(RedisHelper.class);
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        when(resolver.resolvePermanent(anyString(), anyString())).thenReturn(superRoles());
        setupSaToken();
        token = login("operator", "company");
        mvc = MockMvcBuilders.standaloneSetup(new CacheOutboxGovernanceController(service))
                .addInterceptors(new SaInterceptor(handler -> StpUtil.checkLogin()))
                .setControllerAdvice(new NotLoginExceptionHandler(), new GlobalExceptionHandler())
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
        assertConflict(() -> service.replay(id, CacheOutboxReplayReason.DEPENDENCY_RECOVERED, preview.planHash()));
        transactions.executeWithoutResult(status -> store.retry(store.find(id), CacheOutboxFailure.Category.REDIS_FAILURE,
                UUID.randomUUID().toString(), LocalDateTime.now().plusHours(1)));
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
        var error = assertThrows(ServiceException.class, () -> service.replay(id, preview.reason(), preview.planHash()));
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
        transactions.executeWithoutResult(status -> {
            assertFalse(store.complete(stale));
            assertFalse(store.retry(stale, CacheOutboxFailure.Category.REDIS_FAILURE,
                    UUID.randomUUID().toString(), LocalDateTime.now().plusHours(1)));
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
            var first = executor.submit(() -> replayAfterBarrier(id, preview.planHash(), ready, start));
            var second = executor.submit(() -> replayAfterBarrier(id, preview.planHash(), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, auditCount());
        assertEquals(1, store.find(id).revision());
    }

    @Test
    void malformedOrForeignDomainEventsCannotBePreviewedOrSent() {
        String id = transactions.execute(status -> store.enqueue("dictionary", "auth:family:private", 1));
        assertEquals(ResultCode.PARAM_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> service.preview(id, CacheOutboxReplayReason.OPERATOR_RETRY)).getCode());
        context.getBean(CacheEventDispatcher.class).retryPending();
        assertEquals("INVALID_EVENT", store.find(id).errorCategory());
        assertNotNull(store.find(id).failureId());
        verifyNoInteractions(redis);
    }

    @Test
    void permanentRoleGuardDoesNotTrustAnEffectiveOrSessionSuperFlag() {
        when(resolver.resolvePermanent("operator", "company")).thenReturn(
                new EffectiveRoleResolver.EffectiveRoles(false, List.of(role("STAFF"))));
        assertEquals(ResultCode.FORBIDDEN.getCode(), assertThrows(ServiceException.class, service::summary).getCode());
        verify(resolver, never()).resolve(anyString(), anyString());
    }

    @Test
    void mvcRequiresLoginAndDedicatedAnnotationPermissionBeforeGovernance() throws Exception {
        mvc.perform(get("/sys/cache-outbox/summary").header("X-Inner-Company", "company"))
                .andExpect(status().isUnauthorized());
        hasPermission.set(false);
        mvc.perform(get("/sys/cache-outbox/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value(403));
        verifyNoInteractions(resolver);
        hasPermission.set(true);
        mvc.perform(get("/sys/cache-outbox/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(0));
    }

    @Test
    void mvcValidatesPreviewConfirmationAndDoesNotExposeRedisKey() throws Exception {
        String id = enqueue();
        mvc.perform(post("/sys/cache-outbox/" + id + "/replay").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"reason\":\"OPERATOR_RETRY\",\"planHash\":\"invalid\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(400));
        mvc.perform(get("/sys/cache-outbox/events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("data[0].cacheKey").doesNotExist());
        assertEquals(0, auditCount());
    }

    private int replayAfterBarrier(String id, String hash, CountDownLatch ready, CountDownLatch start) throws Exception {
        bind(token);
        try {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
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
        return transactions.execute(status -> store.enqueue(PermissionStateStore.PERMISSIONS, SystemKeys.permissionVersion(), 1));
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_cache_outbox_replay_log", Integer.class);
    }

    private void assertConflict(Runnable action) {
        assertEquals(ResultCode.DATA_CONCURRENT_CONFLICT.getCode(), assertThrows(ServiceException.class, action::run).getCode());
    }

    private static SysRole role(String code) {
        SysRole role = new SysRole(); role.setId("role"); role.setRoleCode(code); return role;
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
        logic.setConfig(new SaTokenConfig().setTokenName("Authorization").setTokenPrefix("Bearer")
                .setIsConcurrent(true).setIsShare(false).setIsReadCookie(false).setIsLog(false));
        SaManager.setSaTokenDao(memoryDao);
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        SaManager.setStpInterface(new StpInterface() {
            @Override public List<String> getPermissionList(Object loginId, String loginType) {
                return hasPermission.get() ? List.of("sys:cache-outbox:manage") : List.of();
            }
            @Override public List<String> getRoleList(Object loginId, String loginType) { return List.of(); }
        });
        StpUtil.setStpLogic(logic);
    }

    private String login(String user, String company) {
        bind(null);
        StpUtil.login(user);
        AuthHolder.setSessionAttr(AuthHolder.SESSION_LOGIN_USER,
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
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource source() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("cache-outbox-schema.sql")).execute(source);
            return source;
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager manager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean PermissionStateStore store(JdbcTemplate jdbc) { return new PermissionStateStore(jdbc); }
        @Bean RedisHelper redis() { return mock(RedisHelper.class); }
        @Bean EffectiveRoleResolver resolver() { return mock(EffectiveRoleResolver.class); }
        @Bean PermissionGrantPolicy policy(EffectiveRoleResolver resolver) {
            return new PermissionGrantPolicy(resolver, mock(SysRoleMenuMapper.class), mock(SysRoleButtonMapper.class));
        }
        @Bean SimpleMeterRegistry meters() { return new SimpleMeterRegistry(); }
        @Bean CacheOutboxMetrics metrics(SimpleMeterRegistry meters) { return new CacheOutboxMetrics(meters); }
        @Bean CacheEventDispatcher dispatcher(PermissionStateStore store, RedisHelper redis,
                PlatformTransactionManager manager, CacheOutboxMetrics metrics) {
            return new CacheEventDispatcher(store, redis, manager, metrics);
        }
        @Bean ObjectMapper mapper() { return new ObjectMapper().registerModule(new JavaTimeModule()); }
        @Bean CacheOutboxGovernanceService service(PermissionStateStore store, PermissionGrantPolicy policy,
                CacheEventDispatcher dispatcher, CacheOutboxMetrics metrics, ObjectMapper mapper) {
            return new CacheOutboxGovernanceService(store, policy, dispatcher, metrics, mapper);
        }
    }
}
