package com.robot.system.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.web.handler.GlobalExceptionHandler;
import com.robot.system.RobotSystemApplication;
import com.robot.system.api.dto.menu.MenuButtonSaveRequest;
import com.robot.system.api.dto.menu.MenuMoveRequest;
import com.robot.system.api.dto.menu.MenuSaveRequest;
import com.robot.system.menu.controller.MenuButtonController;
import com.robot.system.menu.controller.MenuController;
import com.robot.system.permission.service.PermissionChangeRecorder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实事务/MyBatis/H2 行锁与 MVC 白名单绑定；认证行为由独立安全套件覆盖。 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = RobotSystemApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:robot_menu_write;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.cloud.sentinel.enabled=false"
        })
@Sql("/menu-write-schema.sql")
class MenuWriteIntegrationTest {
    private static final int THIRD_SIBLING_SORT = 3;
    private static final int ORIGINAL_LAST_SIBLING_SORT = 9;
    private static final int MAX_TREE_DEPTH = 64;
    private static final int SEEDED_MENU_COUNT = 7;
    private static final int COMMAND_TIMEOUT_SECONDS = 5;

    @MockitoBean com.robot.system.permission.service.GlobalDirectoryPolicy directoryPolicy;
    @MockitoBean RedissonClient redissonClient;
    @MockitoBean RedisHelper redisHelper;
    @MockitoBean PermissionChangeRecorder changeRecorder;
    @Autowired MenuService service;
    @Autowired MenuQueryService queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private MockMvc mvc;
    private org.mockito.MockedStatic<com.robot.common.security.util.AuthUtil> actor;

    @org.junit.jupiter.api.AfterEach
    void closeActor() {
        actor.close();
    }

    @BeforeEach
    void setup() {
        actor = mockStatic(com.robot.common.security.util.AuthUtil.class);
        actor.when(com.robot.common.security.util.AuthUtil::getLoginCompanyId).thenReturn("c1");
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new MenuController(service, queries),
                                new MenuButtonController(service, queries))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void modernAndLegacyRequestsPersistOnlyWhitelistAndReturnContractFields() throws Exception {
        String created =
                mvc.perform(
                                post("/sys/menus")
                                        .contentType("application/json")
                                        .content(
                                                """
                {"id":"forged","name":"Modern","type":"menu","parentId":"A","path":"/modern",
                 "component":"/modern/index","permission":"sys:modern:list","sort":7,"status":1,
                 "hidden":1,"keepAlive":true,"remark":"persisted","revision":999,"deleteFlag":0,
                 "createUserNo":"attacker","createDateTime":"1999-01-01T00:00:00"}
                """))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("code").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String id = mapper.readTree(created).path("data").asText();
        assertThat(id).isNotEqualTo("forged");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM sys_menu WHERE id=?", id);
        assertThat(row)
                .containsEntry("revision", 0)
                .containsEntry("delete_flag", 1)
                .containsEntry("visible_flag", 0)
                .containsEntry("keep_alive_flag", 1)
                .containsEntry("remark", "persisted");
        assertThat(row.get("create_user_no")).isNotEqualTo("attacker");
        mvc.perform(
                        post("/sys/menus")
                                .contentType("application/x-www-form-urlencoded")
                                .param("menuName", "Legacy")
                                .param("menuType", "menu")
                                .param("parentId", "A")
                                .param("path", "/legacy")
                                .param("component", "/legacy/index")
                                .param("permCode", "sys:legacy:list")
                                .param("visibleFlag", "0")
                                .param("keepAliveFlag", "1"))
                .andExpect(jsonPath("code").value(0));
        mvc.perform(get("/sys/menus"))
                .andExpect(
                        jsonPath("$.data[0].children[?(@.id == '" + id + "')].permission")
                                .value("sys:modern:list"))
                .andExpect(jsonPath("$.data[0].children[?(@.id == '" + id + "')].hidden").value(1))
                .andExpect(
                        jsonPath("$.data[0].children[?(@.id == '" + id + "')].revision").value(0));
    }

    @Test
    void routeCollisionKeysFollowCaseInsensitiveMatcherAndPreserveDisplayedPath() {
        String id = service.createMenu(menu("Users", "/Users"));
        clearInvocations(changeRecorder);
        assertCode(
                () -> service.createMenu(menu("Duplicate", "/uSeRs/")), ResultCode.DATA_DUPLICATED);
        assertThat(jdbc.queryForObject("SELECT path FROM sys_menu WHERE id=?", String.class, id))
                .isEqualTo("/Users");
        verifyNoInteractions(changeRecorder);
    }

    @Test
    void updatesRequireClientRevisionAndStaleVersionCannotAlterRow() {
        MenuSaveRequest request = menu("P", "/p");
        request.setParentId("A");
        assertCode(() -> service.updateMenu("P", request), ResultCode.PARAM_INVALID);
        request.setRevision(0);
        request.setName("Changed");
        service.updateMenu("P", request);
        request.setName("Stale overwrite");
        assertCode(() -> service.updateMenu("P", request), ResultCode.DATA_CONCURRENT_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT menu_name FROM sys_menu WHERE id='P'", String.class))
                .isEqualTo("Changed");
        assertThat(jdbc.queryForObject("SELECT revision FROM sys_menu WHERE id='P'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void putAndAllMovePositionsRejectCyclesMissingParentAndInvalidParentType() {
        MenuSaveRequest request = menu("A", "/a");
        request.setType("directory");
        request.setRevision(0);
        request.setParentId("D");
        assertCode(() -> service.updateMenu("A", request), ResultCode.PARAM_INVALID);
        request.setParentId("missing");
        assertCode(() -> service.updateMenu("A", request), ResultCode.MENU_NOT_FOUND);
        request.setParentId("P");
        assertCode(() -> service.updateMenu("A", request), ResultCode.PARAM_INVALID);
        for (String position : List.of("inside", "inner", "before", "after")) {
            assertCode(() -> service.moveMenu("A", move("D", position)), ResultCode.PARAM_INVALID);
        }
        assertCode(() -> service.moveMenu("A", move("B", "garbage")), ResultCode.PARAM_INVALID);
        assertCode(
                () -> service.moveMenu("A", move("missing", "before")), ResultCode.MENU_NOT_FOUND);
        verifyNoInteractions(changeRecorder);
    }

    @Test
    void beforeAfterAndInsideUseStableCompleteSiblingOrder() {
        service.moveMenu("C", move("B", "before"));
        assertThat(rootIds()).containsExactly("A", "C", "B");
        assertThat(
                        jdbc.queryForList(
                                "SELECT sort_no FROM sys_menu WHERE parent_id IS NULL ORDER BY"
                                        + " sort_no",
                                Integer.class))
                .containsExactly(1, 2, THIRD_SIBLING_SORT);
        MenuMoveRequest after = move("B", "after");
        after.setRevision(
                jdbc.queryForObject("SELECT revision FROM sys_menu WHERE id='A'", Integer.class));
        service.moveMenu("A", after);
        assertThat(rootIds()).containsExactly("C", "B", "A");
        service.moveMenu("Q", move("B", "inside"));
        assertThat(jdbc.queryForObject("SELECT parent_id FROM sys_menu WHERE id='Q'", String.class))
                .isEqualTo("B");
        assertThat(
                        jdbc.queryForList(
                                "SELECT id FROM sys_menu WHERE parent_id='A' ORDER BY sort_no,id",
                                String.class))
                .containsExactly("P", "D");
        assertThat(
                        jdbc.queryForList(
                                "SELECT sort_no FROM sys_menu WHERE parent_id='A' ORDER BY sort_no",
                                Integer.class))
                .containsExactly(1, 2);
    }

    @Test
    void siblingReorderAndSourceMoveRollbackTogetherWhenTheTransactionFails() {
        doThrow(new IllegalStateException("audit write failed"))
                .when(changeRecorder)
                .record(eq("move_menu"), eq("C"), any());
        assertThatThrownBy(() -> service.moveMenu("C", move("B", "before")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rootIds()).containsExactly("A", "B", "C");
        assertThat(
                        jdbc.queryForList(
                                "SELECT sort_no FROM sys_menu WHERE parent_id IS NULL ORDER BY"
                                        + " sort_no,id",
                                Integer.class))
                .containsExactly(1, 1, ORIGINAL_LAST_SIBLING_SORT);
        assertThat(jdbc.queryForObject("SELECT revision FROM sys_menu WHERE id='C'", Integer.class))
                .isZero();
    }

    @Test
    @Timeout(5)
    void depthLimitAndDirtyAncestorCycleFailClosed() {
        for (int i = 0; i < MAX_TREE_DEPTH; i++) {
            jdbc.update(
                    "INSERT INTO sys_menu(id,parent_id,menu_type,menu_name,path) VALUES(?,?,?,?,?)",
                    "t" + i,
                    i == 0 ? null : "t" + (i - 1),
                    "directory",
                    "deep",
                    "/t" + i);
        }
        MenuSaveRequest request = menu("Too deep", "/too-deep");
        request.setParentId("t63");
        assertCode(() -> service.createMenu(request), ResultCode.PARAM_INVALID);
        assertCode(() -> service.moveMenu("A", move("t62", "inside")), ResultCode.PARAM_INVALID);
        jdbc.update("UPDATE sys_menu SET parent_id='t63' WHERE id='t0'");
        request.setParentId("t0");
        assertCode(() -> service.createMenu(request), ResultCode.PARAM_INVALID);
    }

    @Test
    void scopedButtonsEnforceValidPageExactPermissionAndVersionAndBlockOrphans() throws Exception {
        mvc.perform(
                        post("/sys/menus/P/buttons")
                                .contentType("application/json")
                                .content(
                                        "{\"name\":\"Save\",\"permission\":\"sys:p:save\",\"remark\":\"button"
                                                + " remark\",\"deleteFlag\":0,\"revision\":99}"))
                .andExpect(jsonPath("code").value(0));
        String id = jdbc.queryForObject("SELECT id FROM sys_menu_button", String.class);
        mvc.perform(get("/sys/menus/P/buttons"))
                .andExpect(jsonPath("data[0].permission").value("sys:p:save"))
                .andExpect(jsonPath("data[0].revision").value(0))
                .andExpect(jsonPath("data[0].remark").value("button remark"));
        mvc.perform(
                        post("/sys/menus/P/buttons")
                                .contentType("application/json")
                                .content("{\"name\":\"Unsafe\",\"permission\":\"sys:*\"}"))
                .andExpect(jsonPath("code").value(ResultCode.PARAM_INVALID.getCode()));
        MenuButtonSaveRequest button = button("sys:p:save");
        button.setMenuId("P");
        assertCode(() -> service.updateButton(id, button), ResultCode.PARAM_INVALID);
        button.setRevision(0);
        service.updateButton(id, button);
        assertCode(() -> service.updateButton(id, button), ResultCode.DATA_CONCURRENT_CONFLICT);
        assertCode(() -> service.createButton("A", button("sys:a:save")), ResultCode.PARAM_INVALID);
        assertCode(
                () -> service.createButton("P", button("sys:p:save")), ResultCode.DATA_DUPLICATED);
        MenuButtonSaveRequest mismatch = button("sys:p:other");
        mismatch.setMenuId("Q");
        assertCode(() -> service.createButton("P", mismatch), ResultCode.PARAM_INVALID);
        assertCode(
                () ->
                        service.deleteMenu(
                                "P",
                                jdbc.queryForObject(
                                        "SELECT revision FROM sys_menu WHERE id='P'",
                                        Integer.class)),
                ResultCode.DATA_REFERENCED);
        jdbc.update("INSERT INTO sys_role_button(id,role_id,button_id) VALUES('rb','r',?)", id);
        service.deleteButton(
                id,
                jdbc.queryForObject(
                        "SELECT revision FROM sys_menu_button WHERE id=?", Integer.class, id));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT delete_flag FROM sys_role_button WHERE id='rb'",
                                Integer.class))
                .isZero();
        service.deleteMenu(
                "P",
                jdbc.queryForObject("SELECT revision FROM sys_menu WHERE id='P'", Integer.class));
    }

    @Test
    void mvcRejectsInvalidEnumsLengthsConflictingFlagsBeforeServiceWrites() throws Exception {
        for (String body :
                List.of(
                        "{\"name\":\"x\",\"type\":\"button\",\"path\":\"/x\"}",
                        "{\"name\":\"x\",\"type\":\"menu\",\"path\":\"/x\",\"component\":\"/x\","
                                + "\"hidden\":1,\"visibleFlag\":1}",
                        "{\"name\":\"x\",\"type\":\"menu\",\"path\":\"/x\",\"component\":\"/x\",\"sort\":-1}")) {
            mvc.perform(post("/sys/menus").contentType("application/json").content(body))
                    .andExpect(jsonPath("code").value(ResultCode.PARAM_INVALID.getCode()));
        }
        verifyNoInteractions(changeRecorder);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_menu", Integer.class))
                .isEqualTo(SEEDED_MENU_COUNT);
    }

    @Test
    @Timeout(10)
    void concurrentInverseMovesCannotBothCommitACycle() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(() -> inverseMove(start, "A", "B"));
            Future<Boolean> second = pool.submit(() -> inverseMove(start, "B", "A"));
            start.countDown();
            assertThat(
                            List.of(
                                    first.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                    second.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            String parentA =
                    jdbc.queryForObject(
                            "SELECT parent_id FROM sys_menu WHERE id='A'", String.class);
            String parentB =
                    jdbc.queryForObject(
                            "SELECT parent_id FROM sys_menu WHERE id='B'", String.class);
            assertThat("B".equals(parentA) && "A".equals(parentB)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean inverseMove(CountDownLatch start, String source, String target)
            throws InterruptedException {
        start.await();
        try (var worker = mockStatic(com.robot.common.security.util.AuthUtil.class)) {
            worker.when(com.robot.common.security.util.AuthUtil::getLoginCompanyId)
                    .thenReturn("c1");
            service.moveMenu(source, move(target, "inside"));
            return true;
        } catch (ServiceException ex) {
            assertThat(ex.getCode()).isEqualTo(ResultCode.PARAM_INVALID.getCode());
            return false;
        }
    }

    @Test
    void childToRootBeforeAfterAndEditPersistNullInsteadOfOnlyReportingSuccess() {
        service.moveMenu("P", move("B", "before"));
        assertThat(jdbc.queryForObject("SELECT parent_id FROM sys_menu WHERE id='P'", String.class))
                .isNull();
        MenuMoveRequest after = move("B", "after");
        service.moveMenu("Q", after);
        assertThat(jdbc.queryForObject("SELECT parent_id FROM sys_menu WHERE id='Q'", String.class))
                .isNull();
        MenuSaveRequest edit = menu("D", "/d");
        edit.setType("directory");
        edit.setParentId("");
        edit.setRevision(
                jdbc.queryForObject("SELECT revision FROM sys_menu WHERE id='D'", Integer.class));
        service.updateMenu("D", edit);
        assertThat(jdbc.queryForObject("SELECT parent_id FROM sys_menu WHERE id='D'", String.class))
                .isNull();
    }

    @Test
    void equivalentAbsolutePathsAreRejectedAcrossDifferentParentsAndSubtreeMoves() {
        MenuSaveRequest relative = menu("Relative", "index");
        relative.setParentId("A");
        service.createMenu(relative);
        MenuSaveRequest duplicate = menu("Absolute", "/a/index");
        duplicate.setParentId("B");
        assertCode(() -> service.createMenu(duplicate), ResultCode.DATA_DUPLICATED);
        MenuSaveRequest legal = menu("Other", "index");
        legal.setParentId("B");
        service.createMenu(legal);
    }

    @Test
    void missingOrOldDeleteAndMoveVersionsHaveNoWritesOrSuccessAudit() {
        assertCode(() -> service.deleteMenu("P", null), ResultCode.PARAM_INVALID);
        MenuSaveRequest edit = menu("Changed", "/p");
        edit.setParentId("A");
        edit.setRevision(0);
        service.updateMenu("P", edit);
        clearInvocations(changeRecorder);
        assertCode(() -> service.deleteMenu("P", 0), ResultCode.DATA_CONCURRENT_CONFLICT);
        MenuMoveRequest stale = move("B", "before");
        assertCode(() -> service.moveMenu("P", stale), ResultCode.DATA_CONCURRENT_CONFLICT);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT delete_flag FROM sys_menu WHERE id='P'", Integer.class))
                .isEqualTo(1);
        verifyNoInteractions(changeRecorder);
    }

    @Test
    void boundPermissionCodeCannotChangeThroughDirectoryUpdate() {
        jdbc.update(
                "INSERT INTO sys_role_menu(id,role_id,menu_id,delete_flag)"
                        + " VALUES('bound','r','P',1)");
        MenuSaveRequest edit = menu("Changed", "/p");
        edit.setParentId("A");
        edit.setRevision(0);
        edit.setPermission("sys:other:write");
        assertCode(() -> service.updateMenu("P", edit), ResultCode.DATA_REFERENCED);
        assertThat(jdbc.queryForObject("SELECT revision FROM sys_menu WHERE id='P'", Integer.class))
                .isZero();
        verifyNoInteractions(changeRecorder);
    }

    private List<String> rootIds() {
        return jdbc.queryForList(
                "SELECT id FROM sys_menu WHERE parent_id IS NULL ORDER BY sort_no,id",
                String.class);
    }

    private static MenuSaveRequest menu(String name, String path) {
        MenuSaveRequest r = new MenuSaveRequest();
        r.setName(name);
        r.setType("menu");
        r.setPath(path);
        r.setComponent(path + "/index");
        return r;
    }

    private static MenuButtonSaveRequest button(String code) {
        MenuButtonSaveRequest r = new MenuButtonSaveRequest();
        r.setName("Button");
        r.setPermission(code);
        return r;
    }

    private static MenuMoveRequest move(String target, String position) {
        MenuMoveRequest r = new MenuMoveRequest();
        r.setTargetId(target);
        r.setPosition(position);
        r.setRevision(0);
        return r;
    }

    private static void assertCode(Runnable operation, ResultCode code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(code.getCode()));
    }
}
