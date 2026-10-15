package com.robot.system.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.role.RolePageQuery;
import com.robot.system.api.dto.role.RoleSaveRequest;
import com.robot.system.api.dto.user.UserPasswordResetRequest;
import com.robot.system.api.dto.user.UserStatusRequest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

/** 实际Spring代理、MP、H2及审计/epoch/Outbox验证；不启动外部基础设施。 */
class WriteCommandPersistenceTest extends WriteCommandTestSupport {
    private static final int COMMAND_BARRIER_TIMEOUT_SECONDS = 3;
    private static final int COMMAND_RESULT_TIMEOUT_SECONDS = 6;
    private static final int TWO_GRANTS_AND_REVOKE_EVENT_COUNT = 3;
    private static final int LEGACY_AUDIT_LIMIT = 500;
    private static final int AUDIT_PAGE_SIZE = 10;

    @Test
    void missingAndInvalidActionPayloadsHaveNoDatabaseOrCacheEffects() throws Exception {
        mvc.perform(delete("/sys/users/u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(ResultCode.PARAM_INVALID.getCode()));
        mvc.perform(delete("/sys/roles/r-free"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(ResultCode.PARAM_INVALID.getCode()));
        mvc.perform(delete("/sys/users/u1").param("expectedRevision", "-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(ResultCode.PARAM_INVALID.getCode()));
        mvc.perform(delete("/sys/roles/r-free").param("expectedRevision", "bad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(ResultCode.PARAM_INVALID.getCode()));
        for (String body :
                List.of(
                        "{\"status\":0}",
                        "{\"status\":2,\"expectedRevision\":0}",
                        "{\"status\":0,\"expectedRevision\":-1}",
                        "{\"expectedRevision\":0}")) {
            mvc.perform(put("/sys/users/u1/status").contentType("application/json").content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("code").value(ResultCode.PARAM_INVALID.getCode()));
        }
        for (String body :
                List.of(
                        "{\"password\":\"fixture-password\"}",
                        "{\"password\":\"short\",\"expectedRevision\":0,\"expectedMembershipRevision\":0}",
                        "{\"password\":\"fixture-password\",\"expectedRevision\":0}",
                        "{\"password\":\" \""
                                + " ,\"expectedRevision\":0,\"expectedMembershipRevision\":0}")) {
            mvc.perform(
                            post("/sys/users/u1/reset-password")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("code").value(ResultCode.PARAM_INVALID.getCode()));
        }
        assertOriginalMember();
        assertOriginalRole();
        assertEvents(0);
    }

    @Test
    void staleClientVersionsCannotRemoveDisableResetOrDeleteRole() throws Exception {
        mvc.perform(delete("/sys/users/u1").param("expectedRevision", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(ResultCode.DATA_CONCURRENT_CONFLICT.getCode()));
        mvc.perform(
                        put("/sys/users/u1/status")
                                .contentType("application/json")
                                .content("{\"status\":0,\"expectedRevision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(ResultCode.DATA_CONCURRENT_CONFLICT.getCode()));
        for (String body :
                List.of(
                        "{\"password\":\"fixture-password\",\"expectedRevision\":1,\"expectedMembershipRevision\":0}",
                        "{\"password\":\"fixture-password\",\"expectedRevision\":0,"
                                + "\"expectedMembershipRevision\":1}")) {
            mvc.perform(
                            post("/sys/users/u1/reset-password")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath("code").value(ResultCode.DATA_CONCURRENT_CONFLICT.getCode()));
        }
        mvc.perform(delete("/sys/roles/r-free").param("expectedRevision", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(ResultCode.DATA_CONCURRENT_CONFLICT.getCode()));
        assertOriginalMember();
        assertOriginalRole();
        assertEvents(0);
    }

    @Test
    void currentMemberVersionEnablesStatusCommandAndFreshVersionIsReturned() throws Exception {
        mvc.perform(
                        put("/sys/users/u1/status")
                                .contentType("application/json")
                                .content(
                                        "{\"status\":0,\"expectedRevision\":0,\"companyId\":\"c2\","
                                                + "\"securityVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0));
        assertEquals(0, scalar("SELECT status_flag FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT revision FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT security_version FROM sys_user_company WHERE id='m1'"));
        assertEquals(0, scalar("SELECT revision FROM sys_user WHERE id='u1'"));
        mvc.perform(get("/sys/users/u1")).andExpect(jsonPath("data.membershipRevision").value(1));
        assertCode(
                ResultCode.DATA_CONCURRENT_CONFLICT,
                () -> users.toggleStatus("u1", new UserStatusRequest(1, 0)));
        assertEvents(1);
        users.toggleStatus("u1", new UserStatusRequest(1, 1));
        assertEquals(2, scalar("SELECT revision FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT security_version FROM sys_user_company WHERE id='m1'"));
        assertEvents(2);
    }

    @Test
    void resetConsumesAccountAndMembershipVersionsInOneTransaction() throws Exception {
        mvc.perform(
                        post("/sys/users/u1/reset-password")
                                .contentType("application/json")
                                .content(
                                        "{\"password\":\"fixture-password\",\"expectedRevision\":0,"
                                                + "\"expectedMembershipRevision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0));
        String encoded =
                jdbc.queryForObject(
                        "SELECT password_hash FROM sys_user WHERE id='u1'", String.class);
        assertTrue(passwords.matches("fixture-password", encoded));
        assertEquals(1, scalar("SELECT revision FROM sys_user WHERE id='u1'"));
        assertEquals(1, scalar("SELECT security_version FROM sys_user WHERE id='u1'"));
        assertEquals(1, scalar("SELECT revision FROM sys_user_company WHERE id='m1'"));
        assertEquals(0, scalar("SELECT security_version FROM sys_user_company WHERE id='m1'"));
        assertCode(
                ResultCode.DATA_CONCURRENT_CONFLICT,
                () ->
                        users.resetPassword(
                                "u1",
                                new UserPasswordResetRequest("other-fixture-password", 0, 1)));
        assertEvents(1);
    }

    @Test
    void removalPreservesSharedAccountAndOtherCompanyWithCurrentMemberVersion() {
        jdbc.update(
                "INSERT INTO sys_user_company(id,user_id,company_id,status_flag)"
                        + " VALUES('m2','u1','c2',1)");
        jdbc.update(
                "INSERT INTO sys_user_role(id,user_id,company_id,role_id)"
                        + " VALUES('b2','u1','c2','r-other')");
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

    @Test
    void roleDeletionKeepsBindingsAndAuditWithinCommandTransaction() throws Exception {
        assertEquals(ROLE_FIXTURE_REVISION, roleQueries.getDetail("r-free").getRevision());
        mvc.perform(delete("/sys/roles/r-free").param("expectedRevision", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0));
        assertEquals(0, scalar("SELECT delete_flag FROM sys_role WHERE id='r-free'"));
        assertEquals(0, scalar("SELECT delete_flag FROM sys_role_menu WHERE id='rm-free'"));
        assertEquals(0, scalar("SELECT delete_flag FROM sys_role_button WHERE id='rb-free'"));
        assertEvents(1);
    }

    @Test
    void splitRoleQueriesKeepCompanyAndPreciseResources() {
        var detail = roleQueries.getDetail("r-free");
        assertEquals(List.of("page-a"), detail.getMenuIds());
        assertEquals(List.of("button-a"), detail.getButtonIds());
        assertEquals(
                Set.of("r1", "r-free"),
                roleQueries.queryPage(new RolePageQuery()).getList().stream()
                        .map(role -> role.getId())
                        .collect(Collectors.toSet()));
        assertEquals(
                Set.of("r1", "r-free"),
                roleQueries.options().stream().map(role -> role.id()).collect(Collectors.toSet()));
        assertEquals(
                List.of("u1"),
                roleQueries.roleUsers("r1").stream().map(user -> user.id()).toList());
        assertEquals(
                List.of("temp1"),
                roleQueries.allTempAuthorizations().stream().map(row -> row.getId()).toList());
        assertThrows(ServiceException.class, () -> roleQueries.getDetail("r-other"));
        assertEvents(0);
    }

    @Test
    void splitTemporaryCommandKeepsAuthorizationAndAuditInOneTransaction() {
        String id = temporaryCommands.createTempAuthorization(temporaryRequest());
        assertEquals(
                List.of(id),
                roleQueries.activeTempAuthorizations("u2", "c1").stream()
                        .map(row -> row.getId())
                        .toList());
        assertEvents(1);
    }

    @Test
    void outboxFailureRollsBackTemporaryCommandAfterServiceSplit() {
        failOutbox();
        assertThrows(
                IllegalStateException.class,
                () -> temporaryCommands.createTempAuthorization(temporaryRequest()));
        assertEquals(0, scalar("SELECT COUNT(*) FROM sys_temp_authorization WHERE user_id='u2'"));
        assertEvents(0);
    }

    @Test
    void outboxFailureRollsBackRemovalAndAllAuthorizationChanges() {
        failOutbox();
        assertThrows(IllegalStateException.class, () -> users.delete("u1", 0));
        assertOriginalMember();
        assertEvents(0);
    }

    @Test
    void outboxFailureRollsBackStatusAndSecurityVersion() {
        failOutbox();
        assertThrows(
                IllegalStateException.class,
                () -> users.toggleStatus("u1", new UserStatusRequest(0, 0)));
        assertOriginalMember();
        assertEvents(0);
    }

    @Test
    void outboxFailureRollsBackResetAccountAndMembershipFence() {
        failOutbox();
        assertThrows(
                IllegalStateException.class,
                () ->
                        users.resetPassword(
                                "u1", new UserPasswordResetRequest("fixture-password", 0, 0)));
        assertOriginalMember();
        assertEvents(0);
    }

    @Test
    void outboxFailureRollsBackRoleDeletionAndResourceBindings() {
        failOutbox();
        assertThrows(
                IllegalStateException.class, () -> roles.delete("r-free", ROLE_FIXTURE_REVISION));
        assertOriginalRole();
        assertEvents(0);
    }

    @Test
    void outboxFailureRollsBackEditedRoleAndReplacementBindingsAfterServiceSplit() {
        failOutbox();
        RoleSaveRequest request = new RoleSaveRequest();
        request.setRoleName("Changed");
        request.setRoleCode("FREE");
        request.setRevision(ROLE_FIXTURE_REVISION);
        request.setPermissionIds(List.of("page-b"));
        assertThrows(IllegalStateException.class, () -> roles.update("r-free", request));
        assertOriginalRole();
        assertEquals(
                0,
                scalar(
                        "SELECT COUNT(*) FROM sys_role_menu WHERE role_id='r-free' AND"
                                + " menu_id='page-b'"));
        assertEvents(0);
    }

    @Test
    @Timeout(10)
    void concurrentStatusCommandsWithSameClientVersionCommitOnlyOnce() throws Exception {
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        Callable<Integer> command =
                () -> {
                    try (MockedStatic<AuthUtil> ignored = login()) {
                        barrier.await(COMMAND_BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
            List<Integer> results =
                    List.of(
                            first.get(COMMAND_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                            second.get(COMMAND_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(code -> code == 0).count());
            assertEquals(
                    1,
                    results.stream()
                            .filter(code -> code == ResultCode.DATA_CONCURRENT_CONFLICT.getCode())
                            .count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, scalar("SELECT revision FROM sys_user_company WHERE id='m1'"));
        assertEquals(1, scalar("SELECT security_version FROM sys_user_company WHERE id='m1'"));
        assertEvents(1);
    }

    @Test
    void preciseTemporaryRevokeConsumesRevisionAndDoesNotRevokeOtherGrants() {
        String first = temporaryCommands.createTempAuthorization(temporaryRequest());
        String other = temporaryCommands.createTempAuthorization(temporaryRequest());
        temporaryCommands.revoke(first, 0);
        assertEquals(
                0,
                scalar("SELECT status_flag FROM sys_temp_authorization WHERE id='" + first + "'"));
        assertEquals(
                1, scalar("SELECT revision FROM sys_temp_authorization WHERE id='" + first + "'"));
        assertEquals(
                1,
                scalar("SELECT status_flag FROM sys_temp_authorization WHERE id='" + other + "'"));
        assertThrows(ServiceException.class, () -> temporaryCommands.revoke(first, 0));
        assertThrows(ServiceException.class, () -> temporaryCommands.revoke("temp2", 0));
        assertEvents(TWO_GRANTS_AND_REVOKE_EVENT_COUNT);
    }

    @Test
    void futureTemporaryGrantCanBeRevokedAndFailedOutboxRollsItBack() {
        var request = temporaryRequest();
        request.setEffectFrom(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        request.setEffectTo(LocalDateTime.now(ZoneOffset.UTC).plusDays(2));
        String id = temporaryCommands.createTempAuthorization(request);
        assertTrue(roleQueries.activeTempAuthorizations("u2", "c1").isEmpty());
        failOutbox();
        assertThrows(IllegalStateException.class, () -> temporaryCommands.revoke(id, 0));
        assertEquals(
                1, scalar("SELECT status_flag FROM sys_temp_authorization WHERE id='" + id + "'"));
        assertEquals(
                0, scalar("SELECT revision FROM sys_temp_authorization WHERE id='" + id + "'"));
        assertEvents(1);
    }

    @Test
    void roleMemberStateCombinesAccountAndCurrentCompanyMembership() {
        var query = new com.robot.common.core.domain.PageQuery();
        jdbc.update("UPDATE sys_user_company SET status_flag=0 WHERE id='m1'");
        assertEquals("disabled", roleQueries.roleUsersPage("r1", query).getList().get(0).status());
        jdbc.update("UPDATE sys_user_company SET status_flag=1 WHERE id='m1'");
        jdbc.update("UPDATE sys_user SET status_flag=0 WHERE id='u1'");
        assertEquals("disabled", roleQueries.roleUsersPage("r1", query).getList().get(0).status());
        jdbc.update("UPDATE sys_user SET status_flag=1 WHERE id='u1'");
        assertEquals("active", roleQueries.roleUsersPage("r1", query).getList().get(0).status());
    }

    @Test
    void companyAdministratorCannotTakeOverIndependentPlatformAccount() {
        jdbc.update("UPDATE sys_user SET platform_admin_flag=1 WHERE id='u1'");
        assertThrows(
                ServiceException.class,
                () -> users.resetPassword("u1", new UserPasswordResetRequest("newpass123", 0, 0)));
        assertOriginalMember();
        assertEvents(0);
    }

    @Test
    void olderThanFiveHundredthAuditIsQueryableByStableCompanyPage() {
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int index = 0; index <= LEGACY_AUDIT_LIMIT; index++) {
            rows.add(
                    new Object[] {
                        String.format("audit-%03d", index),
                        "c1",
                        "fixture",
                        "update_member",
                        "u1",
                        LocalDateTime.parse("2026-01-01T00:00:00").plusSeconds(index)
                    });
        }
        rows.add(
                new Object[] {
                    "foreign-audit",
                    "c2",
                    "fixture",
                    "update_member",
                    "u1",
                    LocalDateTime.now(ZoneOffset.UTC)
                });
        jdbc.batchUpdate(
                "INSERT INTO"
                        + " sys_permission_audit_log(id,company_id,actor_user_no,action,target,create_date_time)"
                        + " VALUES(?,?,?,?,?,?)",
                rows);
        var query = new com.robot.system.api.dto.role.PermissionAuditPageQuery();
        query.setPage(LEGACY_AUDIT_LIMIT / AUDIT_PAGE_SIZE + 1);
        query.setPageSize(AUDIT_PAGE_SIZE);
        query.setAction("update_member");
        query.setTarget("u1");
        var result = governance.auditPage(query);
        assertEquals(LEGACY_AUDIT_LIMIT + 1, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals("audit-000", result.getList().get(0).id());
        assertEquals(LEGACY_AUDIT_LIMIT, governance.auditLogs().size());
    }
}
