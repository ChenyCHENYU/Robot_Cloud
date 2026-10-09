package com.robot.system.command;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.util.AuthUtil;
import com.robot.common.web.handler.GlobalExceptionHandler;
import com.robot.system.RobotSystemApplication;
import com.robot.system.api.dto.role.RoleSaveRequest;
import com.robot.system.api.dto.role.RolePageQuery;
import com.robot.system.api.dto.role.TempAuthorizationCreateRequest;
import com.robot.system.api.dto.user.UserPasswordResetRequest;
import com.robot.system.api.dto.user.UserStatusRequest;
import com.robot.system.api.entity.SysRole;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.role.controller.RoleController;
import com.robot.system.role.service.RoleCommandService;
import com.robot.system.role.service.RoleQueryService;
import com.robot.system.role.service.TemporaryAuthorizationCommandService;
import com.robot.system.user.controller.UserController;
import com.robot.system.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 实际Spring代理、MP、H2及审计/epoch/Outbox验证；不启动外部基础设施。 */
@ActiveProfiles("test")
@SpringBootTest(classes = RobotSystemApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:robot_write_commands;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.cloud.sentinel.enabled=false"
})
@Sql("/write-command-schema.sql")
class WriteCommandPersistenceTest {
    @MockBean RedissonClient redissonClient;
    @MockBean RedisHelper redis;
    @MockBean PermissionGrantPolicy policy;
    @MockBean EffectiveRoleResolver resolver;
    @SpyBean PermissionStateStore state;
    @Autowired UserService users;
    @Autowired RoleCommandService roles;
    @Autowired RoleQueryService roleQueries;
    @Autowired TemporaryAuthorizationCommandService temporaryCommands;
    @Autowired JdbcTemplate jdbc;
    @Autowired BCryptPasswordEncoder passwords;
    MockedStatic<AuthUtil> auth;
    MockMvc mvc;

    @BeforeEach void setup() {
        auth = login();
        SysRole operatorRole = new SysRole();
        operatorRole.setId("r-admin");
        var operatorRoles = new EffectiveRoleResolver.EffectiveRoles(true, List.of(operatorRole));
        when(policy.requireCompany()).thenReturn("c1");
        when(policy.requireOperator()).thenReturn(new PermissionGrantPolicy.Operator("operator", "c1", operatorRoles));
        when(resolver.resolve(anyString(), eq("c1"))).thenReturn(operatorRoles);
        mvc = MockMvcBuilders.standaloneSetup(new UserController(users), new RoleController(roleQueries, roles))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach void close() { auth.close(); }

    @Test void missingAndInvalidActionPayloadsHaveNoDatabaseOrCacheEffects() throws Exception {
        mvc.perform(delete("/sys/users/u1")).andExpect(status().isOk()).andExpect(jsonPath("code").value(400));
        mvc.perform(delete("/sys/roles/r-free")).andExpect(status().isOk()).andExpect(jsonPath("code").value(400));
        mvc.perform(delete("/sys/users/u1").param("expectedRevision", "-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(400));
        mvc.perform(delete("/sys/roles/r-free").param("expectedRevision", "bad"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(400));
        for (String body : List.of("{\"status\":0}", "{\"status\":2,\"expectedRevision\":0}",
                "{\"status\":0,\"expectedRevision\":-1}", "{\"expectedRevision\":0}")) {
            mvc.perform(put("/sys/users/u1/status").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("code").value(400));
        }
        for (String body : List.of("{\"password\":\"fixture-password\"}",
                "{\"password\":\"short\",\"expectedRevision\":0,\"expectedMembershipRevision\":0}",
                "{\"password\":\"fixture-password\",\"expectedRevision\":0}",
                "{\"password\":\" \" ,\"expectedRevision\":0,\"expectedMembershipRevision\":0}")) {
            mvc.perform(post("/sys/users/u1/reset-password").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("code").value(400));
        }
        assertOriginalMember();
        assertOriginalRole();
        assertEvents(0);
    }

    @Test void staleClientVersionsCannotRemoveDisableResetOrDeleteRole() throws Exception {
        mvc.perform(delete("/sys/users/u1").param("expectedRevision", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(40902));
        mvc.perform(put("/sys/users/u1/status").contentType("application/json")
                        .content("{\"status\":0,\"expectedRevision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(40902));
        for (String body : List.of(
                "{\"password\":\"fixture-password\",\"expectedRevision\":1,\"expectedMembershipRevision\":0}",
                "{\"password\":\"fixture-password\",\"expectedRevision\":0,\"expectedMembershipRevision\":1}")) {
            mvc.perform(post("/sys/users/u1/reset-password").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("code").value(40902));
        }
        mvc.perform(delete("/sys/roles/r-free").param("expectedRevision", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(40902));
        assertOriginalMember();
        assertOriginalRole();
        assertEvents(0);
    }

    @Test void currentMemberVersionEnablesStatusCommandAndFreshVersionIsReturned() throws Exception {
        mvc.perform(put("/sys/users/u1/status").contentType("application/json")
                        .content("{\"status\":0,\"expectedRevision\":0,\"companyId\":\"c2\",\"securityVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(0));
        assertEquals(0, scalar("SELECT status_flag FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT revision FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT security_version FROM sys_user_company WHERE id='m1'"));
        assertEquals(0, scalar("SELECT revision FROM sys_user WHERE id='u1'"));
        mvc.perform(get("/sys/users/u1")).andExpect(jsonPath("data.membershipRevision").value(1));
        assertCode(ResultCode.DATA_CONCURRENT_CONFLICT, () -> users.toggleStatus("u1", new UserStatusRequest(1, 0)));
        assertEvents(1);
        users.toggleStatus("u1", new UserStatusRequest(1, 1));
        assertEquals(2, scalar("SELECT revision FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT security_version FROM sys_user_company WHERE id='m1'"));
        assertEvents(2);
    }

    @Test void resetConsumesAccountAndMembershipVersionsInOneTransaction() throws Exception {
        mvc.perform(post("/sys/users/u1/reset-password").contentType("application/json")
                        .content("{\"password\":\"fixture-password\",\"expectedRevision\":0,\"expectedMembershipRevision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(0));
        String encoded = jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id='u1'", String.class);
        assertTrue(passwords.matches("fixture-password", encoded));
        assertEquals(1, scalar("SELECT revision FROM sys_user WHERE id='u1'"));
        assertEquals(1, scalar("SELECT security_version FROM sys_user WHERE id='u1'"));
        assertEquals(1, scalar("SELECT revision FROM sys_user_company WHERE id='m1'"));
        assertEquals(0, scalar("SELECT security_version FROM sys_user_company WHERE id='m1'"));
        assertCode(ResultCode.DATA_CONCURRENT_CONFLICT,
                () -> users.resetPassword("u1", new UserPasswordResetRequest("other-fixture-password", 0, 1)));
        assertEvents(1);
    }

    @Test void removalPreservesSharedAccountAndOtherCompanyWithCurrentMemberVersion() {
        jdbc.update("INSERT INTO sys_user_company(id,user_id,company_id,status_flag) VALUES('m2','u1','c2',1)");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,company_id,role_id) VALUES('b2','u1','c2','r-other')");
        users.delete("u1", 0);
        assertEquals(0, scalar("SELECT delete_flag FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT revision FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT security_version FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_user WHERE id='u1'"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_user_company WHERE id='m2'"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_user_role WHERE id='b2'"));
        assertEquals(0, scalar("SELECT status_flag FROM sys_temp_authorization WHERE id='temp1'"));
        assertEquals(1, scalar("SELECT status_flag FROM sys_temp_authorization WHERE id='temp2'"));
        assertEvents(1);
    }

    @Test void roleDeletionKeepsBindingsAndAuditWithinCommandTransaction() throws Exception {
        assertEquals(4, roleQueries.getDetail("r-free").getRevision());
        mvc.perform(delete("/sys/roles/r-free").param("expectedRevision", "4"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(0));
        assertEquals(0, scalar("SELECT delete_flag FROM sys_role WHERE id='r-free'"));
        assertEquals(0, scalar("SELECT delete_flag FROM sys_role_menu WHERE id='rm-free'"));
        assertEquals(0, scalar("SELECT delete_flag FROM sys_role_button WHERE id='rb-free'"));
        assertEvents(1);
    }

    @Test void splitRoleQueriesKeepCompanyAndPreciseResources() {
        var detail = roleQueries.getDetail("r-free");
        assertEquals(List.of("page-a"), detail.getMenuIds());
        assertEquals(List.of("button-a"), detail.getButtonIds());
        assertEquals(Set.of("r1", "r-free"), roleQueries.queryPage(new RolePageQuery()).getList().stream()
                .map(role -> role.getId()).collect(Collectors.toSet()));
        assertEquals(Set.of("r1", "r-free"), roleQueries.options().stream()
                .map(role -> role.id()).collect(Collectors.toSet()));
        assertEquals(List.of("u1"), roleQueries.roleUsers("r1").stream().map(user -> user.id()).toList());
        assertEquals(List.of("temp1"), roleQueries.allTempAuthorizations().stream().map(row -> row.getId()).toList());
        assertThrows(ServiceException.class, () -> roleQueries.getDetail("r-other"));
        assertEvents(0);
    }

    @Test void splitTemporaryCommandKeepsAuthorizationAndAuditInOneTransaction() {
        String id = temporaryCommands.createTempAuthorization(temporaryRequest());
        assertEquals(List.of(id), roleQueries.activeTempAuthorizations("u2", "c1").stream().map(row -> row.getId()).toList());
        assertEvents(1);
    }

    @Test void outboxFailureRollsBackTemporaryCommandAfterServiceSplit() {
        failOutbox();
        assertThrows(IllegalStateException.class,
                () -> temporaryCommands.createTempAuthorization(temporaryRequest()));
        assertEquals(0, scalar("SELECT COUNT(*) FROM sys_temp_authorization WHERE user_id='u2'"));
        assertEvents(0);
    }

    @Test void outboxFailureRollsBackRemovalAndAllAuthorizationChanges() {
        failOutbox();
        assertThrows(IllegalStateException.class, () -> users.delete("u1", 0));
        assertOriginalMember();
        assertEvents(0);
    }

    @Test void outboxFailureRollsBackStatusAndSecurityVersion() {
        failOutbox();
        assertThrows(IllegalStateException.class, () -> users.toggleStatus("u1", new UserStatusRequest(0, 0)));
        assertOriginalMember();
        assertEvents(0);
    }

    @Test void outboxFailureRollsBackResetAccountAndMembershipFence() {
        failOutbox();
        assertThrows(IllegalStateException.class,
                () -> users.resetPassword("u1", new UserPasswordResetRequest("fixture-password", 0, 0)));
        assertOriginalMember();
        assertEvents(0);
    }

    @Test void outboxFailureRollsBackRoleDeletionAndResourceBindings() {
        failOutbox();
        assertThrows(IllegalStateException.class, () -> roles.delete("r-free", 4));
        assertOriginalRole();
        assertEvents(0);
    }

    @Test void outboxFailureRollsBackEditedRoleAndReplacementBindingsAfterServiceSplit() {
        failOutbox();
        RoleSaveRequest request = new RoleSaveRequest();
        request.setRoleName("Changed"); request.setRoleCode("FREE"); request.setRevision(4);
        request.setPermissionIds(List.of("page-b"));
        assertThrows(IllegalStateException.class, () -> roles.update("r-free", request));
        assertOriginalRole();
        assertEquals(0, scalar("SELECT COUNT(*) FROM sys_role_menu WHERE role_id='r-free' AND menu_id='page-b'"));
        assertEvents(0);
    }

    @Test @Timeout(10) void concurrentStatusCommandsWithSameClientVersionCommitOnlyOnce() throws Exception {
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        Callable<Integer> command = () -> {
            try (MockedStatic<AuthUtil> ignored = login()) {
                barrier.await(3, TimeUnit.SECONDS);
                try {
                    users.toggleStatus("u1", new UserStatusRequest(0, 0));
                    return 0;
                } catch (ServiceException failure) {
                    return failure.getCode();
                }
            }
        };
        try {
            var first = executor.submit(command);
            var second = executor.submit(command);
            List<Integer> results = List.of(first.get(6, TimeUnit.SECONDS), second.get(6, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(code -> code == 0).count());
            assertEquals(1, results.stream().filter(code -> code == ResultCode.DATA_CONCURRENT_CONFLICT.getCode()).count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, scalar("SELECT revision FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT security_version FROM sys_user_company WHERE id='m1'"));
        assertEvents(1);
    }

    private TempAuthorizationCreateRequest temporaryRequest() {
        TempAuthorizationCreateRequest request = new TempAuthorizationCreateRequest();
        request.setUserId("u2"); request.setRoleId("r-free"); request.setReason("fixture approval");
        request.setEffectFrom(LocalDateTime.now().minusMinutes(1));
        request.setEffectTo(LocalDateTime.now().plusDays(1));
        return request;
    }

    private void failOutbox() {
        doThrow(new IllegalStateException("fixture outbox unavailable")).when(state).enqueue(anyString(), anyString(), anyLong());
    }

    private void assertOriginalMember() {
        assertEquals(1, scalar("SELECT status_flag FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_user_company WHERE id='m1'"));
        assertEquals(0, scalar("SELECT revision FROM sys_user_company WHERE id='m1'"));
        assertEquals(0, scalar("SELECT security_version FROM sys_user_company WHERE id='m1'"));
        assertEquals(0, scalar("SELECT revision FROM sys_user WHERE id='u1'"));
        assertEquals(0, scalar("SELECT security_version FROM sys_user WHERE id='u1'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM sys_user WHERE id='u1' AND password_hash IS NOT NULL"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_user_role WHERE id='b1'"));
        assertEquals(1, scalar("SELECT status_flag FROM sys_temp_authorization WHERE id='temp1'"));
    }

    private void assertOriginalRole() {
        assertEquals("Free", jdbc.queryForObject("SELECT role_name FROM sys_role WHERE id='r-free'", String.class));
        assertEquals(4, scalar("SELECT revision FROM sys_role WHERE id='r-free'"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_role WHERE id='r-free'"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_role_menu WHERE id='rm-free'"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_role_button WHERE id='rb-free'"));
    }

    private void assertEvents(int count) {
        assertEquals(count, scalar("SELECT COUNT(*) FROM sys_permission_audit_log"));
        assertEquals(count, scalar("SELECT COUNT(*) FROM sys_cache_outbox"));
        assertEquals(1 + count, scalar("SELECT version FROM sys_cache_epoch WHERE scope='permissions'"));
        verifyNoInteractions(redis, redissonClient);
    }

    private int scalar(String sql) { return jdbc.queryForObject(sql, Integer.class); }

    private static MockedStatic<AuthUtil> login() {
        MockedStatic<AuthUtil> mocked = mockStatic(AuthUtil.class);
        mocked.when(AuthUtil::getLoginCompanyId).thenReturn("c1");
        mocked.when(AuthUtil::getLoginUserId).thenReturn("operator");
        mocked.when(AuthUtil::getLoginUsername).thenReturn("operator");
        return mocked;
    }

    private static void assertCode(ResultCode expected, Runnable command) {
        assertEquals(expected.getCode(), assertThrows(ServiceException.class, command::run).getCode());
    }
}
