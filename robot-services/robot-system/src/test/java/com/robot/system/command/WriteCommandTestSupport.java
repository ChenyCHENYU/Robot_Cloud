package com.robot.system.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.util.AuthUtil;
import com.robot.common.web.handler.GlobalExceptionHandler;
import com.robot.system.RobotSystemApplication;
import com.robot.system.api.dto.role.TempAuthorizationCreateRequest;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockedStatic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 写命令持久化测试的独立事务夹具、身份生命周期和副作用断言。 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = RobotSystemApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:robot_write_commands;MODE=MySQL;"
                    + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.cloud.sentinel.enabled=false"
        })
@Sql("/write-command-schema.sql")
abstract class WriteCommandTestSupport {
    static final int ROLE_FIXTURE_REVISION = 4;

    @MockitoBean RedissonClient redissonClient;
    @MockitoBean RedisHelper redis;
    @MockitoBean PermissionGrantPolicy policy;
    @MockitoBean EffectiveRoleResolver resolver;
    @MockitoSpyBean PermissionStateStore state;
    @Autowired UserService users;
    @Autowired RoleCommandService roles;
    @Autowired RoleQueryService roleQueries;
    @Autowired TemporaryAuthorizationCommandService temporaryCommands;
    @Autowired com.robot.system.permission.service.PermissionGovernanceQueryService governance;
    @Autowired JdbcTemplate jdbc;
    @Autowired BCryptPasswordEncoder passwords;
    MockedStatic<AuthUtil> auth;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        auth = login();
        SysRole operatorRole = new SysRole();
        operatorRole.setId("r-admin");
        var operatorRoles = new EffectiveRoleResolver.EffectiveRoles(true, List.of(operatorRole));
        when(policy.requireCompany()).thenReturn("c1");
        when(policy.requireOperator())
                .thenReturn(new PermissionGrantPolicy.Operator("operator", "c1", operatorRoles));
        when(resolver.resolve(anyString(), eq("c1"))).thenReturn(operatorRoles);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new UserController(users), new RoleController(roleQueries, roles))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @AfterEach
    void close() {
        auth.close();
    }

    TempAuthorizationCreateRequest temporaryRequest() {
        TempAuthorizationCreateRequest request = new TempAuthorizationCreateRequest();
        request.setUserId("u2");
        request.setRoleId("r-free");
        request.setReason("fixture approval");
        request.setEffectFrom(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        request.setEffectTo(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        return request;
    }

    void failOutbox() {
        doThrow(new IllegalStateException("fixture outbox unavailable"))
                .when(state)
                .enqueue(anyString(), anyString(), anyLong());
    }

    void assertOriginalMember() {
        assertEquals(1, scalar("SELECT status_flag FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_user_company WHERE id='m1'"));
        assertEquals(0, scalar("SELECT revision FROM sys_user_company WHERE id='m1'"));
        assertEquals(0, scalar("SELECT security_version FROM sys_user_company WHERE id='m1'"));
        assertEquals(0, scalar("SELECT revision FROM sys_user WHERE id='u1'"));
        assertEquals(0, scalar("SELECT security_version FROM sys_user WHERE id='u1'"));
        assertEquals(
                0,
                scalar(
                        "SELECT COUNT(*) FROM sys_user WHERE id='u1' AND password_hash IS NOT"
                                + " NULL"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_user_role WHERE id='b1'"));
        assertEquals(1, scalar("SELECT status_flag FROM sys_temp_authorization WHERE id='temp1'"));
    }

    void assertOriginalRole() {
        assertEquals(
                "Free",
                jdbc.queryForObject(
                        "SELECT role_name FROM sys_role WHERE id='r-free'", String.class));
        assertEquals(
                ROLE_FIXTURE_REVISION, scalar("SELECT revision FROM sys_role WHERE id='r-free'"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_role WHERE id='r-free'"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_role_menu WHERE id='rm-free'"));
        assertEquals(1, scalar("SELECT delete_flag FROM sys_role_button WHERE id='rb-free'"));
    }

    void assertEvents(int count) {
        assertEquals(count, scalar("SELECT COUNT(*) FROM sys_permission_audit_log"));
        assertEquals(count, scalar("SELECT COUNT(*) FROM sys_cache_outbox"));
        assertEquals(
                1 + count, scalar("SELECT version FROM sys_cache_epoch WHERE scope='permissions'"));
        verifyNoInteractions(redis, redissonClient);
    }

    int scalar(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    static MockedStatic<AuthUtil> login() {
        MockedStatic<AuthUtil> mocked = mockStatic(AuthUtil.class);
        mocked.when(AuthUtil::getLoginCompanyId).thenReturn("c1");
        mocked.when(AuthUtil::getLoginUserId).thenReturn("operator");
        mocked.when(AuthUtil::getLoginUsername).thenReturn("operator");
        return mocked;
    }

    static void assertCode(ResultCode expected, Runnable command) {
        assertEquals(
                expected.getCode(), assertThrows(ServiceException.class, command::run).getCode());
    }
}
