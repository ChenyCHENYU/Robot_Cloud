package com.robot.system.dict.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.web.handler.GlobalExceptionHandler;
import com.robot.system.RobotSystemApplication;
import com.robot.system.api.dto.dict.DictionarySaveRequest;
import com.robot.system.api.dto.dict.DictionaryStatusRequest;
import com.robot.system.dict.controller.DictController;
import com.robot.system.dict.mapper.SysDictItemMapper;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.support.SystemKeys;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 本地H2真实事务、行锁、MP乐观锁与MVC；不代替MySQL迁移或Redis Lua验收。 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = RobotSystemApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:robot_dict_write;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.cloud.sentinel.enabled=false"
        })
@Sql("/dictionary-schema.sql")
class DictWriteIntegrationTest {
    private static final int FIRST_LONG_TYPE_CODE_LENGTH = 33;
    private static final int MAX_TYPE_CODE_LENGTH = 60;
    private static final int CACHE_KEY_SCHEMA_LIMIT = 255;
    private static final int LOADER_ARGUMENT_INDEX = 3;
    private static final int COMMAND_TIMEOUT_SECONDS = 5;

    @MockitoBean com.robot.system.permission.service.GlobalDirectoryPolicy directoryPolicy;
    @MockitoBean RedissonClient redisson;
    @MockitoBean RedisHelper redis;
    @MockitoSpyBean PermissionStateStore state;
    @MockitoSpyBean SysDictItemMapper items;
    @Autowired DictService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private MockMvc mvc;
    private org.mockito.MockedStatic<com.robot.common.security.util.AuthUtil> actor;

    @org.junit.jupiter.api.AfterEach
    void closeActor() {
        actor.close();
    }

    @BeforeEach
    void setUp() {
        actor = mockStatic(com.robot.common.security.util.AuthUtil.class);
        actor.when(com.robot.common.security.util.AuthUtil::getLoginCompanyId).thenReturn("c1");
        mvc =
                MockMvcBuilders.standaloneSetup(new DictController(service))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        Map<String, Object> cache = new HashMap<>();
        lenient()
                .when(redis.getOrLoad(anyString(), eq(Map.class), any(), any()))
                .thenAnswer(
                        call -> {
                            String key = call.getArgument(0);
                            if (cache.containsKey(key)) {
                                return cache.get(key);
                            }
                            Object loaded =
                                    ((Supplier<?>) call.getArgument(LOADER_ARGUMENT_INDEX)).get();
                            cache.put(key, loaded);
                            return loaded;
                        });
    }

    @Test
    void negativeCacheIsBypassedAfterNewTypeAndItemCommit() {
        assertEquals(List.of(), service.itemsByCode("new_type"));
        var type = type("new_type");
        String typeId = service.create(type);
        var item = item(typeId, "new_code", "0");
        service.create(item);
        assertEquals(
                List.of(Map.of("label", "Label", "value", "0")), service.itemsByCode("new_type"));
        verify(redis)
                .getOrLoad(eq(SystemKeys.dictCache("new_type", 0)), eq(Map.class), any(), any());
        verify(redis)
                .getOrLoad(eq(SystemKeys.dictCache("new_type", 2)), eq(Map.class), any(), any());
        verify(redis, never()).evict(anyString());
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_cache_outbox WHERE completed_at IS NULL",
                        Integer.class));
    }

    @Test
    void disabledTypeSuppressesChildrenWithoutChangingTheirOwnStatus() {
        assertEquals(1, service.itemsByCode("state").size());
        service.toggleStatus("t1", new DictionaryStatusRequest(0, 0));
        assertEquals(List.of(), service.itemsByCode("state"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT status_flag FROM sys_dict_item WHERE id='i1'", Integer.class));
        service.toggleStatus("t1", new DictionaryStatusRequest(1, 1));
        assertEquals(1, service.itemsByCode("state").size());
        var node =
                service.dictTree().stream()
                        .filter(type -> type.id().equals("t1"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(1, node.status());
        assertEquals(2, node.revision());
        assertEquals("enabled", node.children().get(0).code());
    }

    @Test
    void legalPunctuationAndCaseInCodesCannotBreakCacheKeys() {
        String owner = service.create(type("My:State.v1"));
        service.create(item(owner, "enabled", "0"));
        assertEquals(1, service.itemsByCode("my:state.v1").size());
        assertEquals(
                SystemKeys.dictCache("My:State.v1", 2), SystemKeys.dictCache("my:state.v1", 2));
        assertNotEquals(SystemKeys.dictCache("a:b", 0), SystemKeys.dictCache("a_b", 0));
    }

    @Test
    void supportedThirtyThreeAndSixtyCharacterTypeCodesCanBeCreatedAndRead() {
        for (int length : List.of(FIRST_LONG_TYPE_CODE_LENGTH, MAX_TYPE_CODE_LENGTH)) {
            String code = "s".repeat(length);
            String owner = service.create(type(code));
            service.create(item(owner, "enabled", "0"));
            assertEquals(
                    List.of(Map.of("label", "Label", "value", "0")), service.itemsByCode(code));
            String key = SystemKeys.dictCache(code, 2);
            assertTrue(key.matches(".*:dict:[a-f0-9]{64}:db-v2"));
            assertTrue(
                    key.length() <= CACHE_KEY_SCHEMA_LIMIT,
                    "outbox cache key must fit its schema column");
            assertEquals(2L, state.currentVersion("dictionary:" + code));
        }
    }

    @Test
    void adminPayloadPersistsIndependentCodeLabelValueAndRejectsServerFieldForging()
            throws Exception {
        String body =
                """
            {"type":"item","parentId":"t1","name":"Display","dictLabel":"Display","code":"storage_code",
             "value":"stored","dictValue":"stored","sort":3,"status":1,"remark":"note",
             "id":"forged","deleteFlag":0,"revision":99,"createUserNo":"attacker"}
            """;
        String response =
                mvc.perform(post("/sys/dictionaries").contentType("application/json").content(body))
                        .andExpect(jsonPath("code").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String id = mapper.readTree(response).path("data").asText();
        assertNotEquals("forged", id);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM sys_dict_item WHERE id=?", id);
        assertEquals("storage_code", row.get("item_code"));
        assertEquals("stored", row.get("item_value"));
        assertEquals("Display", row.get("item_label"));
        assertEquals(0, row.get("revision"));
        assertEquals(1, row.get("delete_flag"));
        assertNotEquals("attacker", row.get("create_user_no"));
        mvc.perform(get("/sys/dictionaries"))
                .andExpect(jsonPath("data[1].children[1].dictLabel").value("Display"))
                .andExpect(jsonPath("data[1].children[1].code").value("storage_code"));
        for (String invalid :
                List.of(
                        "{\"type\":\"wrong\"}",
                        "{\"type\":\"item\",\"parentId\":\"t1\",\"name\":\"x\",\"value\":\"1\",\"dictValue\":\"2\"}",
                        "{\"type\":\"type\",\"name\":\"x\",\"code\":\"bad code\"}",
                        "{\"type\":\"type\",\"name\":\"x\",\"code\":\"ok\",\"sort\":\"abc\"}")) {
            mvc.perform(post("/sys/dictionaries").contentType("application/json").content(invalid))
                    .andExpect(
                            jsonPath("code")
                                    .value(
                                            com.robot.common.core.domain.ResultCode.PARAM_INVALID
                                                    .getCode()));
        }
    }

    @Test
    void duplicateCodesOrValuesAndMissingOrDisabledOwnersAreRejected() {
        assertCode(() -> service.create(item("t1", "enabled", "new")), ResultCode.DATA_DUPLICATED);
        assertCode(() -> service.create(item("t1", "new", "0")), ResultCode.DATA_DUPLICATED);
        assertCode(() -> service.create(item("missing", "new", "new")), ResultCode.DICT_NOT_FOUND);
        service.toggleStatus("t1", new DictionaryStatusRequest(0, 0));
        assertCode(() -> service.create(item("t1", "new", "new")), ResultCode.PARAM_INVALID);
        assertCode(
                () ->
                        service.delete(
                                "t1",
                                jdbc.queryForObject(
                                        "SELECT revision FROM sys_dict_type WHERE id='t1'",
                                        Integer.class)),
                ResultCode.DATA_REFERENCED);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT delete_flag FROM sys_dict_type WHERE id='t1'", Integer.class));
    }

    @Test
    void staleOrMissingRevisionCannotWriteAndMovingInvalidatesBothTypes() {
        var item = item("t2", "enabled", "0");
        assertCode(() -> service.update("i1", item), ResultCode.PARAM_INVALID);
        item.setRevision(0);
        service.update("i1", item);
        assertEquals(
                "t2",
                jdbc.queryForObject(
                        "SELECT type_id FROM sys_dict_item WHERE id='i1'", String.class));
        assertEquals(2L, state.currentVersion("dictionary:state"));
        assertEquals(2L, state.currentVersion("dictionary:other"));
        assertCode(() -> service.update("i1", item), ResultCode.DATA_CONCURRENT_CONFLICT);
        service.delete(
                "t1",
                jdbc.queryForObject(
                        "SELECT revision FROM sys_dict_type WHERE id='t1'", Integer.class));
        assertEquals(List.of(), service.itemsByCode("state"));
    }

    @Test
    void outboxWriteFailureRollsBackBusinessAndEpochTogether() {
        doThrow(new IllegalStateException("forced failure"))
                .when(state)
                .enqueue(eq("dictionary"), anyString(), anyLong());
        assertThrows(IllegalStateException.class, () -> service.create(type("rolled_back")));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_dict_type WHERE type_code='rolled_back'",
                        Integer.class));
        assertEquals(0L, state.currentVersion("dictionary:rolled_back"));
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_cache_outbox", Integer.class));
        verify(redis, never()).evict(anyString());
    }

    @Test
    @Timeout(10)
    void concurrentDuplicateItemsCannotBothCommit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> action =
                    () -> {
                        start.await();
                        try (var worker =
                                mockStatic(com.robot.common.security.util.AuthUtil.class)) {
                            worker.when(com.robot.common.security.util.AuthUtil::getLoginCompanyId)
                                    .thenReturn("c1");
                            service.create(item("t1", "concurrent", "same"));
                            return true;
                        } catch (ServiceException expected) {
                            assertEquals(ResultCode.DATA_DUPLICATED.getCode(), expected.getCode());
                            return false;
                        }
                    };
            Future<Boolean> first = pool.submit(action), second = pool.submit(action);
            start.countDown();
            assertNotEquals(
                    first.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sys_dict_item WHERE item_code='concurrent'",
                            Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleDeleteCannotRemoveItemMovedToAnotherOwnerOrPublishAnOldOwnerEvent() {
        DictionarySaveRequest move = item("t2", "enabled", "0");
        move.setRevision(0);
        service.update("i1", move);
        assertCode(() -> service.delete("i1", 0), ResultCode.DATA_CONCURRENT_CONFLICT);
        Map<String, Object> current =
                jdbc.queryForMap(
                        "SELECT type_id, revision, delete_flag FROM sys_dict_item WHERE id='i1'");
        assertEquals("t2", current.get("type_id"));
        assertEquals(1, current.get("revision"));
        assertEquals(1, current.get("delete_flag"));
        assertEquals(2L, state.currentVersion("dictionary:state"));
        assertEquals(2L, state.currentVersion("dictionary:other"));
        assertEquals(
                2, jdbc.queryForObject("SELECT COUNT(*) FROM sys_cache_outbox", Integer.class));
    }

    private static DictionarySaveRequest type(String code) {
        var request = new DictionarySaveRequest();
        request.setName("Type");
        request.setCode(code);
        return request;
    }

    private static DictionarySaveRequest item(String owner, String code, String value) {
        var request = new DictionarySaveRequest();
        request.setType("item");
        request.setParentId(owner);
        request.setName("Label");
        request.setCode(code);
        request.setValue(value);
        return request;
    }

    private static void assertCode(Runnable action, ResultCode code) {
        assertEquals(code.getCode(), assertThrows(ServiceException.class, action::run).getCode());
    }
}
