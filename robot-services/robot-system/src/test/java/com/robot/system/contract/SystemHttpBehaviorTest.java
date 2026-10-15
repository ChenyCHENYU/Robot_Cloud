package com.robot.system.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.common.web.handler.GlobalExceptionHandler;
import com.robot.system.account.controller.AccountController;
import com.robot.system.account.controller.inner.LoginLogInnerController;
import com.robot.system.account.service.AccountService;
import com.robot.system.account.service.LoginLogService;
import com.robot.system.dept.controller.DepartmentController;
import com.robot.system.dept.service.DepartmentService;
import com.robot.system.dict.controller.DictController;
import com.robot.system.dict.service.DictService;
import com.robot.system.menu.controller.MenuButtonController;
import com.robot.system.menu.controller.MenuController;
import com.robot.system.menu.controller.inner.MenuInnerController;
import com.robot.system.menu.service.MenuQueryService;
import com.robot.system.menu.service.MenuService;
import com.robot.system.permission.controller.CacheOutboxGovernanceController;
import com.robot.system.permission.controller.PermissionGovernanceController;
import com.robot.system.permission.service.CacheOutboxGovernanceService;
import com.robot.system.permission.service.DataScopeService;
import com.robot.system.permission.service.PermissionGovernanceQueryService;
import com.robot.system.role.controller.RoleController;
import com.robot.system.role.service.RoleCommandService;
import com.robot.system.role.service.RoleQueryService;
import com.robot.system.role.service.TemporaryAuthorizationCommandService;
import com.robot.system.security.InnerSystemAccess;
import com.robot.system.user.controller.UserController;
import com.robot.system.user.controller.inner.UserInnerController;
import com.robot.system.user.service.UserService;
import com.robot.system.user.service.UserSupplyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Real MVC binding/validation/dispatch. Authority is exercised separately in service and
 * inner-filter tests.
 */
class SystemHttpBehaviorTest {
    private static final int AUDIT_PAGE_SIZE = 30;
    private static final int PLAN_HASH_LENGTH = 64;
    private static final int TEMP_AUTHORIZATION_REVISION = 3;
    private static final int ARCHIVE_RETENTION_DAYS = 30;
    private static final int ARCHIVE_LIMIT = 10;
    private static final int INNER_MEMBERSHIP_READ_COUNT = 3;
    private static final int BAD_REQUEST_CODE = 400;

    @Test
    void accountDepartmentAndGovernanceReadsBindPaginationAndExposeSupportedCapabilities()
            throws Exception {
        var f = new Fixture();
        for (String path :
                List.of(
                        "/account/profile",
                        "/account/login-records",
                        "/account/security-settings",
                        "/account/activity-logs",
                        "/sys/departments",
                        "/sys/permissions",
                        "/sys/permissions/tree",
                        "/sys/permission-constraints",
                        "/sys/permission-audit-logs",
                        "/sys/temp-authorizations")) {
            f.success(get(path));
        }
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::getLoginUserId).thenReturn("u");
            auth.when(AuthUtil::getLoginCompanyId).thenReturn("c");
            f.success(get("/sys/data-permissions"));
            verify(f.scopes).scopesOfUser("u", "c");
        }
        f.success(
                get("/sys/permission-audit-logs/page")
                        .param("page", "2")
                        .param("pageSize", "30")
                        .param("action", "move_menu"));
        verify(f.governance)
                .auditPage(
                        argThat(
                                query ->
                                        query.pageValue() == 2
                                                && query.pageSizeValue() == AUDIT_PAGE_SIZE
                                                && "move_menu".equals(query.getAction())));
        f.success(
                get("/sys/temp-authorizations/page")
                        .param("userId", "u")
                        .param("roleId", "r")
                        .param("statusFlag", "0"));
        verify(f.roles)
                .temporaryAuthorizationPage(
                        argThat(
                                query ->
                                        "u".equals(query.getUserId())
                                                && "r".equals(query.getRoleId())
                                                && query.getStatusFlag() == 0));
        f.mvc.perform(get("/sys/governance-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.fieldPermission.read").value(false))
                .andExpect(jsonPath("data.fieldPermission.write").value(false))
                .andExpect(jsonPath("data.temporaryRoleGrant.revoke").value(true));
        verify(f.account).profile();
        verify(f.account).loginRecords();
        verify(f.account).securitySettings();
        verify(f.account).activityLogs();
        verify(f.departments).departmentTree();
        f.success(
                put("/account/profile")
                        .contentType("application/json")
                        .content("{\"email\":null}"));
        verify(f.account)
                .updateProfile(argThat(body -> body.isEmailPresent() && body.getEmail() == null));
        f.success(
                put("/account/password")
                        .contentType("application/json")
                        .content(
                                "{\"oldPassword\":\"old-password\",\"newPassword\":\"new-password\"}"));
        verify(f.account)
                .changePassword(argThat(body -> "old-password".equals(body.getOldPassword())));
    }

    @Test
    void temporaryAndOutboxCommandsPassOnlyValidatedRevisionReasonAndPlan() throws Exception {
        var f = new Fixture();
        var reason = com.robot.system.api.dto.cache.CacheOutboxReplayReason.OPERATOR_RETRY;
        String hash = "a".repeat(PLAN_HASH_LENGTH);
        f.success(
                put("/sys/temp-authorizations/g/revoke")
                        .contentType("application/json")
                        .content("{\"expectedRevision\":3}"));
        verify(f.temporary).revoke("g", TEMP_AUTHORIZATION_REVISION);
        f.reject(
                put("/sys/temp-authorizations/g/revoke")
                        .contentType("application/json")
                        .content("{}"));
        verify(f.temporary, times(1)).revoke(anyString(), any());
        f.success(
                post("/sys/temp-authorizations")
                        .contentType("application/json")
                        .content(
                                ("{\"userId\":\"u\",\"roleId\":\"r\",\"effectFrom\":\"2026-10-14T10:00:00\","
                                        + "\"effectTo\":\"2026-10-15T10:00:00\",\"reason\":\"support\"}")));
        verify(f.temporary)
                .createTempAuthorization(
                        argThat(
                                body ->
                                        "u".equals(body.getUserId())
                                                && "r".equals(body.getRoleId())
                                                && "support".equals(body.getReason())));
        f.success(get("/sys/cache-outbox/summary"));
        f.success(get("/sys/cache-outbox/events").param("limit", "20"));
        f.success(get("/sys/cache-outbox/e/replays"));
        f.success(
                post("/sys/cache-outbox/e/replay-preview")
                        .contentType("application/json")
                        .content("{\"reason\":\"OPERATOR_RETRY\"}"));
        f.success(
                post("/sys/cache-outbox/e/replay")
                        .contentType("application/json")
                        .content("{\"reason\":\"OPERATOR_RETRY\",\"planHash\":\"" + hash + "\"}"));
        verify(f.outbox).preview("e", reason);
        verify(f.outbox).replay("e", reason, hash);
        f.success(
                post("/sys/cache-outbox/archive-preview")
                        .contentType("application/json")
                        .content("{\"retentionDays\":30,\"limit\":10}"));
        f.success(
                post("/sys/cache-outbox/archive")
                        .contentType("application/json")
                        .content(
                                "{\"retentionDays\":30,\"limit\":10,\"planHash\":\""
                                        + hash
                                        + "\"}"));
        verify(f.outbox).archive(ARCHIVE_RETENTION_DAYS, ARCHIVE_LIMIT, hash);
        f.reject(
                post("/sys/cache-outbox/e/replay")
                        .contentType("application/json")
                        .content("{\"reason\":\"OPERATOR_RETRY\",\"planHash\":\"wrong\"}"));
    }

    @Test
    void resourceCommandsDispatchJsonAndFormAndRequireDeleteVersion() throws Exception {
        var f = new Fixture();
        String menu =
                ("{\"name\":\"Reports\",\"type\":\"menu\",\"path\":\"/reports\","
                        + "\"component\":\"reports/index\",\"revision\":2}");
        f.success(get("/sys/menus"));
        f.success(get("/sys/menus/m/buttons"));
        verify(f.menus).requireButtonPage("m");
        f.success(post("/sys/menus").contentType("application/json").content(menu));
        f.success(put("/sys/menus/m").contentType("application/json").content(menu));
        f.success(
                post("/sys/menus")
                        .contentType("application/x-www-form-urlencoded")
                        .param("name", "Folder")
                        .param("type", "directory")
                        .param("path", "/folder"));
        f.success(
                put("/sys/menus/m")
                        .contentType("application/x-www-form-urlencoded")
                        .param("name", "Folder")
                        .param("type", "directory")
                        .param("path", "/folder")
                        .param("revision", "2"));
        f.success(
                put("/sys/menus/m/move")
                        .contentType("application/json")
                        .content(
                                "{\"targetId\":\"target\",\"position\":\"inside\",\"expectedRevision\":2}"));
        verify(f.menus).moveMenu(eq("m"), argThat(body -> body.getRevision() == 2));
        f.reject(
                put("/sys/menus/m/move")
                        .contentType("application/json")
                        .content("{\"targetId\":\"target\",\"position\":\"inside\"}"));
        f.reject(delete("/sys/menus/m"));
        f.success(delete("/sys/menus/m").param("expectedRevision", "2"));
        verify(f.menus).deleteMenu("m", 2);
        String button =
                "{\"name\":\"Export\",\"menuId\":\"m\",\"permission\":\"sys:report:export\",\"revision\":1}";
        f.success(get("/sys/menu-buttons"));
        f.success(post("/sys/menu-buttons").contentType("application/json").content(button));
        f.success(put("/sys/menu-buttons/b").contentType("application/json").content(button));
        f.success(delete("/sys/menu-buttons/b").param("expectedRevision", "1"));
        f.success(post("/sys/menus/buttons").contentType("application/json").content(button));
        f.success(post("/sys/menus/m/buttons").contentType("application/json").content(button));
        verify(f.menus).deleteButton("b", 1);
        verify(f.menus).createButton(eq("m"), any());
        String dictionary =
                "{\"type\":\"type\",\"name\":\"State\",\"code\":\"state\",\"revision\":1}";
        f.success(get("/sys/dictionaries"));
        f.success(get("/sys/dictionaries/by-code").param("code", "state"));
        f.success(post("/sys/dictionaries").contentType("application/json").content(dictionary));
        f.success(put("/sys/dictionaries/d").contentType("application/json").content(dictionary));
        f.success(
                put("/sys/dictionaries/d/status")
                        .contentType("application/json")
                        .content("{\"status\":0,\"revision\":1}"));
        f.reject(
                put("/sys/dictionaries/d/status")
                        .contentType("application/json")
                        .content("{\"status\":0}"));
        f.success(delete("/sys/dictionaries/d").param("expectedRevision", "1"));
        verify(f.dict).delete("d", 1);
    }

    @Test
    void memberRoleAndInternalHttpUsePathIdentityAndPreserveServiceRejection() throws Exception {
        var f = new Fixture();
        f.success(get("/sys/users"));
        f.success(get("/sys/users/u"));
        String user =
                ("{\"id\":\"forged\",\"username\":\"alice\",\"nickname\":\"Alice\",\"email\":null,"
                        + "\"revision\":1,\"membershipRevision\":2}");
        f.success(post("/sys/users").contentType("application/json").content(user));
        f.success(put("/sys/users/u").contentType("application/json").content(user));
        verify(f.users)
                .update(
                        eq("u"),
                        argThat(
                                body ->
                                        "u".equals(body.getId())
                                                && body.isEmailPresent()
                                                && body.getEmail() == null));
        f.success(delete("/sys/users/u").param("expectedRevision", "2"));
        f.success(
                post("/sys/users/u/reset-password")
                        .contentType("application/json")
                        .content(
                                ("{\"password\":\"new-password\",\"expectedRevision\":1,"
                                        + "\"expectedMembershipRevision\":2}")));
        f.success(
                put("/sys/users/u/status")
                        .contentType("application/json")
                        .content("{\"status\":0,\"expectedRevision\":2}"));
        f.success(get("/sys/roles"));
        f.success(get("/sys/roles/options"));
        f.success(get("/sys/roles/r"));
        f.success(get("/sys/roles/r/users"));
        f.success(get("/sys/roles/r/users/page").param("page", "2"));
        f.success(get("/sys/roles/r/data-scopes"));
        f.success(get("/sys/roles/r/temp-authorizations"));
        String role =
                "{\"id\":\"forged\",\"roleName\":\"Staff\",\"roleCode\":\"STAFF\",\"revision\":1}";
        f.success(post("/sys/roles").contentType("application/json").content(role));
        f.success(put("/sys/roles/r").contentType("application/json").content(role));
        verify(f.roleCommands).update(eq("r"), argThat(body -> "r".equals(body.getId())));
        f.success(
                put("/sys/roles/r/permissions")
                        .contentType("application/json")
                        .content("{\"permissionIds\":[],\"revision\":1}"));
        verify(f.roleCommands).assignPermissions("r", List.of(), 1);
        f.success(delete("/sys/roles/r").param("expectedRevision", "1"));
        f.success(get("/inner/user/by-username").param("username", "alice"));
        f.success(get("/inner/user/u"));
        f.success(get("/inner/user/u/contexts"));
        f.success(get("/inner/menu/c/routes").param("userId", "u"));
        f.success(get("/inner/menu/c/button-list").param("userId", "u"));
        when(f.scopes.scopesOfUser("u", "c"))
                .thenReturn(
                        List.of(new com.robot.system.api.vo.permission.DataScopeVO("*", "self")));
        f.success(get("/inner/permission/c/data-scope").param("userId", "u"));
        verify(f.access, times(INNER_MEMBERSHIP_READ_COUNT)).requireActiveMembership("u", "c");
        f.success(
                post("/inner/log/login-record")
                        .contentType("application/json")
                        .content(
                                "{\"username\":\"alice\",\"eventId\":\"6c31a250-5271-4685-a71f-16d812034a32\"}"));
        verify(f.login).recordLogin(argThat(body -> "alice".equals(body.getUsername())));
        doThrow(new ServiceException(ResultCode.DATA_CONCURRENT_CONFLICT))
                .when(f.users)
                .delete("u", 1);
        f.mvc.perform(delete("/sys/users/u").param("expectedRevision", "1"))
                .andExpect(jsonPath("code").value(ResultCode.DATA_CONCURRENT_CONFLICT.getCode()));
    }

    private static class Fixture {
        final AccountService account = mock(AccountService.class);
        final DepartmentService departments = mock(DepartmentService.class);
        final MenuService menus = mock(MenuService.class);
        final MenuQueryService menuQueries = mock(MenuQueryService.class);
        final DictService dict = mock(DictService.class);
        final PermissionGovernanceQueryService governance =
                mock(PermissionGovernanceQueryService.class);
        final DataScopeService scopes = mock(DataScopeService.class);
        final RoleQueryService roles = mock(RoleQueryService.class);
        final RoleCommandService roleCommands = mock(RoleCommandService.class);
        final TemporaryAuthorizationCommandService temporary =
                mock(TemporaryAuthorizationCommandService.class);
        final CacheOutboxGovernanceService outbox = mock(CacheOutboxGovernanceService.class);
        final UserService users = mock(UserService.class);
        final UserSupplyService supply = mock(UserSupplyService.class);
        final InnerSystemAccess access = mock(InnerSystemAccess.class);
        final LoginLogService login = mock(LoginLogService.class);
        final MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new AccountController(account),
                                new DepartmentController(departments),
                                new MenuController(menus, menuQueries),
                                new MenuButtonController(menus, menuQueries),
                                new DictController(dict),
                                new PermissionGovernanceController(
                                        menuQueries, scopes, roles, temporary, governance),
                                new CacheOutboxGovernanceController(outbox),
                                new UserController(users),
                                new RoleController(roles, roleCommands),
                                new UserInnerController(supply),
                                new MenuInnerController(menuQueries, scopes, access),
                                new LoginLogInnerController(login))
                        .setMessageConverters(
                                new MappingJackson2HttpMessageConverter(
                                        new ObjectMapper().findAndRegisterModules()))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();

        void success(MockHttpServletRequestBuilder request) throws Exception {
            mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("code").value(0));
        }

        void reject(MockHttpServletRequestBuilder request) throws Exception {
            mvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("code").value(BAD_REQUEST_CODE));
        }
    }
}
