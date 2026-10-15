package com.robot.system.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import com.robot.system.RobotSystemApplication;
import com.robot.system.api.dto.user.UserPageQuery;
import com.robot.system.api.dto.user.UserSaveRequest;
import com.robot.system.api.dto.user.UserStatusRequest;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysUser;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.user.mapper.SysUserMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

/** 实际MP插件与本地H2事务；不启动Redis/Nacos且不替代生产MySQL迁移验收。 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = RobotSystemApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:robot_member_persistence;MODE=MySQL;"
                    + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "robot.security.inner-secret=test-secret",
            "spring.cloud.sentinel.enabled=false"
        })
@Sql("/member-persistence-schema.sql")
class UserMembershipPersistenceTest {
    private static final int STALE_MEMBERSHIP_REVISION = 3;
    private static final int THREE_BYTE_PASSWORD_LIMIT_CHARACTERS = 24;

    @MockitoBean RedissonClient redissonClient;
    @MockitoBean RedisHelper redisHelper;
    @MockitoBean PermissionGrantPolicy policy;
    @MockitoBean PermissionChangeRecorder recorder;
    @MockitoBean EffectiveRoleResolver resolver;
    @Autowired UserService service;
    @Autowired SysUserMapper userMapper;
    @Autowired JdbcTemplate jdbc;
    MockedStatic<AuthUtil> auth;

    @BeforeEach
    void setUp() {
        auth = mockStatic(AuthUtil.class);
        auth.when(AuthUtil::getLoginUserId).thenReturn("operator");
        auth.when(AuthUtil::getLoginCompanyId).thenReturn("c1");
        auth.when(AuthUtil::getLoginUsername).thenReturn("operator");
        SysRole role = new SysRole();
        role.setId("r-admin");
        when(policy.requireCompany()).thenReturn("c1");
        when(policy.requireOperator())
                .thenReturn(
                        new PermissionGrantPolicy.Operator(
                                "operator",
                                "c1",
                                new EffectiveRoleResolver.EffectiveRoles(true, List.of(role))));
        when(resolver.resolve(anyString(), eq("c1")))
                .thenReturn(new EffectiveRoleResolver.EffectiveRoles(true, List.of(role)));
    }

    @AfterEach
    void close() {
        auth.close();
    }

    @Test
    void actualOptimisticPluginRejectsSecondSnapshot() {
        SysUser first = userMapper.selectById("u1");
        SysUser stale = userMapper.selectById("u1");
        first.setNickname("First");
        stale.setNickname("Stale");
        assertEquals(1, userMapper.updateById(first));
        assertEquals(0, userMapper.updateById(stale));
        assertEquals(
                "First",
                jdbc.queryForObject("SELECT nickname FROM sys_user WHERE id='u1'", String.class));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT revision FROM sys_user WHERE id='u1'", Integer.class));
    }

    @Test
    void staleClientRevisionDoesNotClearActualBindings() {
        UserSaveRequest request = request();
        request.setMembershipRevision(STALE_MEMBERSHIP_REVISION);
        request.setRoleIds(List.of());
        ServiceException error =
                assertThrows(ServiceException.class, () -> service.update("u1", request));
        assertEquals(ResultCode.DATA_CONCURRENT_CONFLICT.getCode(), error.getCode());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_user_role WHERE id='b1' AND delete_flag=1",
                        Integer.class));
        verifyNoInteractions(recorder);
    }

    @Test
    void failedSuccessAuditRollsBackAccountMembershipAndRoleChanges() {
        doThrow(new IllegalStateException("audit unavailable"))
                .when(recorder)
                .record(eq("update_member"), eq("u1"), any());
        UserSaveRequest request = request();
        request.setNickname("Changed");
        request.setRoleIds(List.of());
        assertThrows(IllegalStateException.class, () -> service.update("u1", request));
        assertEquals(
                "Target",
                jdbc.queryForObject("SELECT nickname FROM sys_user WHERE id='u1'", String.class));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT revision FROM sys_user WHERE id='u1'", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT revision FROM sys_user_company WHERE id='m1'", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT delete_flag FROM sys_user_role WHERE id='b1'", Integer.class));
        verifyNoInteractions(redisHelper, redissonClient);
    }

    @Test
    void sharedMemberRemovalPreservesAccountAndOtherCompanyBindings() {
        jdbc.update(
                "INSERT INTO sys_user_company (id,user_id,company_id,primary_flag,status_flag)"
                        + " VALUES ('m2','u1','c2',0,1)");
        jdbc.update(
                "INSERT INTO sys_user_role (id,user_id,company_id,role_id) VALUES"
                        + " ('b2','u1','c2','r2')");
        service.delete("u1", 0);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT delete_flag FROM sys_user WHERE id='u1'", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT delete_flag FROM sys_user_company WHERE id='m1'", Integer.class));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT security_version FROM sys_user_company WHERE id='m1'", Long.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT revision FROM sys_user_company WHERE id='m1'", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT delete_flag FROM sys_user_company WHERE id='m2'", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT delete_flag FROM sys_user_role WHERE id='b1'", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT delete_flag FROM sys_user_role WHERE id='b2'", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT status_flag FROM sys_temp_authorization WHERE id='temp1'",
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT status_flag FROM sys_temp_authorization WHERE id='temp2'",
                        Integer.class));
    }

    @Test
    void disableThenEnableCannotRestoreOldMembershipVersion() {
        service.toggleStatus("u1", new UserStatusRequest(0, 0));
        service.toggleStatus("u1", new UserStatusRequest(1, 1));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT security_version FROM sys_user_company WHERE id='m1'", Long.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT revision FROM sys_user_company WHERE id='m1'", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT status_flag FROM sys_user_company WHERE id='m1'", Integer.class));
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT security_version FROM sys_user WHERE id='u1'", Long.class));
    }

    @Test
    void selfScopeLimitsActualListAndRejectsGuessingSameCompanyUserId() {
        useScope("self");
        assertEquals(
                List.of("u1"),
                service.queryPage(new UserPageQuery()).getList().stream()
                        .map(v -> v.getId())
                        .toList());
        assertEquals("u1", service.getDetail("u1").getId());
        assertEquals(
                ResultCode.FORBIDDEN.getCode(),
                assertThrows(ServiceException.class, () -> service.getDetail("u2")).getCode());
    }

    @Test
    void departmentAndBelowExcludeNeighbourAndForeignDepartmentsInActualSql() {
        useScope("department");
        assertEquals(java.util.Set.of("u1", "u5"), visibleIds());
        assertThrows(ServiceException.class, () -> service.getDetail("u3"));
        useScope("department_below");
        assertEquals(java.util.Set.of("u1", "u3", "u5"), visibleIds());
        assertEquals("u3", service.getDetail("u3").getId());
        assertThrows(ServiceException.class, () -> service.getDetail("u2"));
        assertThrows(ServiceException.class, () -> service.getDetail("u4"));
    }

    @Test
    void foreignOrDisabledMemberDepartmentFallsBackToSelf() {
        useScope("department_below");
        jdbc.update(
                "UPDATE sys_user_company SET department_id='d-foreign' WHERE user_id='u1' AND"
                        + " company_id='c1'");
        assertEquals(java.util.Set.of("u1"), visibleIds());
        jdbc.update(
                "UPDATE sys_user_company SET department_id='d-own' WHERE user_id='u1' AND"
                        + " company_id='c1'");
        jdbc.update("UPDATE sys_department SET status_flag=0 WHERE id='d-own'");
        assertEquals(java.util.Set.of("u1"), visibleIds());
    }

    @Test
    void dirtyDepartmentCycleOrInactiveChildCannotExpandScope() {
        useScope("department_below");
        jdbc.update("UPDATE sys_department SET parent_id='d-child' WHERE id='d-own'");
        assertEquals(java.util.Set.of("u1"), visibleIds());
        jdbc.update("UPDATE sys_department SET parent_id=NULL WHERE id='d-own'");
        jdbc.update("UPDATE sys_department SET status_flag=0 WHERE id='d-child'");
        assertEquals(java.util.Set.of("u1", "u5"), visibleIds());
        assertThrows(ServiceException.class, () -> service.getDetail("u3"));
        jdbc.update(
                "UPDATE sys_user_company SET department_id=NULL WHERE user_id='u1' AND"
                        + " company_id='c1'");
        assertEquals(java.util.Set.of("u1"), visibleIds());
    }

    @Test
    void unsupportedCustomScopeFailsClosedAndAllStaysWithinCurrentCompany() {
        useScope("custom");
        assertThrows(ServiceException.class, () -> service.queryPage(new UserPageQuery()));
        assertThrows(ServiceException.class, () -> service.getDetail("u1"));
        useScope("all");
        assertEquals(java.util.Set.of("u1", "u2", "u3", "u4", "u5"), visibleIds());
        assertThrows(ServiceException.class, () -> service.getDetail("u6"));
    }

    private java.util.Set<String> visibleIds() {
        return service.queryPage(new UserPageQuery()).getList().stream()
                .map(v -> v.getId())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void omittedProfilePreservesAccountAndExplicitNullClearsAllowedColumns() throws Exception {
        jdbc.update("UPDATE sys_user SET email='old@example.test', phone='123' WHERE id='u1'");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        UserSaveRequest omitted =
                mapper.readValue(
                        "{\"username\":\"target\",\"membershipRevision\":0}",
                        UserSaveRequest.class);
        service.update("u1", omitted);
        assertEquals(
                0,
                jdbc.queryForObject("SELECT revision FROM sys_user WHERE id='u1'", Integer.class));
        assertEquals(
                "old@example.test",
                jdbc.queryForObject("SELECT email FROM sys_user WHERE id='u1'", String.class));
        UserSaveRequest clearing =
                mapper.readValue(
                        "{\"username\":\"target\",\"revision\":0,\"membershipRevision\":1,"
                                + "\"email\":null,\"phone\":null}",
                        UserSaveRequest.class);
        service.update("u1", clearing);
        assertEquals(
                null,
                jdbc.queryForObject("SELECT email FROM sys_user WHERE id='u1'", String.class));
        assertEquals(
                null,
                jdbc.queryForObject("SELECT phone FROM sys_user WHERE id='u1'", String.class));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT revision FROM sys_user WHERE id='u1'", Integer.class));
    }

    @Test
    void changingOneCompanyDepartmentPreservesOtherMembershipAndGlobalAccount() {
        jdbc.update(
                "INSERT INTO"
                        + " sys_user_company(id,user_id,company_id,primary_flag,status_flag,department_id)"
                        + " VALUES('m2','u1','c2',0,1,'d-foreign')");
        UserSaveRequest request = new UserSaveRequest();
        request.setUsername("target");
        request.setMembershipRevision(0);
        request.setDepartmentId("d-peer");
        service.update("u1", request);
        assertEquals(
                "d-peer",
                jdbc.queryForObject(
                        "SELECT department_id FROM sys_user_company WHERE id='m1'", String.class));
        assertEquals(
                "d-foreign",
                jdbc.queryForObject(
                        "SELECT department_id FROM sys_user_company WHERE id='m2'", String.class));
        assertEquals(
                "d-own",
                jdbc.queryForObject(
                        "SELECT department_id FROM sys_user WHERE id='u1'", String.class));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT revision FROM sys_user WHERE id='u1'", Integer.class));
        request.setMembershipRevision(1);
        request.setDepartmentId(null);
        service.update("u1", request);
        assertEquals(
                null,
                jdbc.queryForObject(
                        "SELECT department_id FROM sys_user_company WHERE id='m1'", String.class));
    }

    @Test
    void byteLimitFailureLeavesAccountMemberBindingsAndSuccessRecorderUnchanged() {
        UserSaveRequest request = request();
        request.setPassword("中".repeat(THREE_BYTE_PASSWORD_LIMIT_CHARACTERS) + "x");
        ServiceException error =
                assertThrows(ServiceException.class, () -> service.update("u1", request));
        assertEquals(ResultCode.USER_PASSWORD_WEAK.getCode(), error.getCode());
        assertEquals(
                0,
                jdbc.queryForObject("SELECT revision FROM sys_user WHERE id='u1'", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT revision FROM sys_user_company WHERE id='m1'", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_user_role WHERE delete_flag=1", Integer.class));
        verifyNoInteractions(recorder);
    }

    private void useScope(String scope) {
        auth.when(AuthUtil::getLoginUserId).thenReturn("u1");
        SysRole role = new SysRole();
        role.setId("r-scope");
        role.setDataScope(scope);
        when(resolver.resolve("u1", "c1"))
                .thenReturn(new EffectiveRoleResolver.EffectiveRoles(false, List.of(role)));
    }

    private UserSaveRequest request() {
        UserSaveRequest request = new UserSaveRequest();
        request.setUsername("target");
        request.setNickname("Target");
        request.setRevision(0);
        request.setMembershipRevision(0);
        return request;
    }
}
