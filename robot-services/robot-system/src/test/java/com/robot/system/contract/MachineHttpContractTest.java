package com.robot.system.contract;

import static com.robot.system.contract.ContractModelAssertions.annotationConstraints;
import static com.robot.system.contract.ContractModelAssertions.names;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.Result;
import com.robot.common.web.config.RobotWebAutoConfiguration;
import com.robot.system.role.controller.RoleController;
import com.robot.system.user.controller.UserController;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Machine contract gate uses real MVC mappings, Jackson properties and Bean Validation metadata.
 */
class MachineHttpContractTest {
    private static final int ENVELOPE_TOTAL = 21;
    private static final int ENVELOPE_PAGE_SIZE = 10;
    private static final int ALIAS_INTEGER = 3;
    private static final int FORGED_SECURITY_VERSION = 999;
    private static AnnotationConfigWebApplicationContext context;
    private static ObjectMapper mapper;
    private static ValidatorFactory validatorFactory;
    private static ContractModelAssertions models;

    @BeforeAll
    static void initialize() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfiguration.class);
        context.refresh();
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new RobotWebAutoConfiguration().robotJacksonCustomizer().customize(builder);
        mapper = builder.build();
        validatorFactory = Validation.buildDefaultValidatorFactory();
        models = new ContractModelAssertions(mapper, validatorFactory.getValidator());
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    @Test
    void userContractMatchesRealMappingsRequestsQueriesAndResponses() throws Exception {
        verifyContract(load("users"));
    }

    @Test
    void roleContractMatchesRealMappingsRequestsQueriesAndResponses() throws Exception {
        verifyContract(load("roles"));
    }

    @Test
    void contractGateRejectsPathMethodPermissionAndWritableFieldDrift() throws Exception {
        JsonNode original = load("users");
        for (String field : List.of("path", "method", "permission")) {
            ObjectNode changed = original.deepCopy();
            ((ObjectNode) changed.path("routes").get(0)).put(field, "intentional-drift");
            assertThrows(AssertionError.class, () -> verifyContract(changed), field);
        }
        ObjectNode changed = original.deepCopy();
        ObjectNode fields =
                (ObjectNode)
                        changed.path("requests")
                                .path("com.robot.system.api.dto.user.UserSaveRequest")
                                .path("fields");
        fields.set("securityVersion", fields.path("revision").deepCopy());
        assertThrows(
                AssertionError.class,
                () -> verifyContract(changed),
                "must reject unexpected writable fields");
    }

    @Test
    void responseEnvelopeKeepsNumericCountersStringIdsAndRealTimeFormat() throws Exception {
        JsonNode result =
                mapper.valueToTree(
                        Result.success(
                                PageResult.of(
                                        List.of("9007199254740993"),
                                        ENVELOPE_TOTAL,
                                        2,
                                        ENVELOPE_PAGE_SIZE)));
        assertEquals(0, result.path("code").intValue());
        assertTrue(result.path("code").isInt());
        assertEquals(result.path("message"), result.path("msg"));
        assertEquals(Set.of("list", "total", "page", "pageSize"), names(result.path("data")));
        for (String counter : List.of("total", "page", "pageSize")) {
            assertTrue(result.path("data").path(counter).isNumber());
        }
        assertTrue(result.path("data").path("list").get(0).isTextual());
        assertEquals(
                "2026-10-09 12:34:56",
                mapper.valueToTree(LocalDateTime.parse("2026-10-09T12:34:56")).asText());
    }

    @Test
    void aliasesBindAndServerOnlyFieldsCannotBecomeRequestProperties() throws Exception {
        for (String resource : List.of("users", "roles")) {
            JsonNode contract = load(resource);
            var models = contract.path("requests").fields();
            while (models.hasNext()) {
                var model = models.next();
                Class<?> type = Class.forName(model.getKey());
                verifyAliases(type, model.getValue().path("fields"));
                verifyServerFieldsIgnored(type);
            }
        }
    }

    private static void verifyAliases(Class<?> type, JsonNode specification) throws Exception {
        var fields = specification.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            for (JsonNode alias : field.getValue().path("aliases")) {
                JsonNode value =
                        "Integer".equals(field.getValue().path("javaType").asText())
                                ? mapper.valueToTree(ALIAS_INTEGER)
                                : mapper.valueToTree("contract-alias");
                ObjectNode body = mapper.createObjectNode().set(alias.asText(), value);
                Object bean = mapper.treeToValue(body, type);
                assertEquals(value, mapper.valueToTree(bean).path(field.getKey()));
            }
        }
    }

    private static void verifyServerFieldsIgnored(Class<?> type) throws Exception {
        ObjectNode forged =
                mapper.createObjectNode()
                        .put("securityVersion", FORGED_SECURITY_VERSION)
                        .put("companyId", "foreign")
                        .put("createUserNo", "forged")
                        .put("deleteFlag", 0)
                        .put("passwordHash", "forged");
        JsonNode rebound = mapper.valueToTree(mapper.treeToValue(forged, type));
        for (String field :
                List.of(
                        "securityVersion",
                        "companyId",
                        "createUserNo",
                        "deleteFlag",
                        "passwordHash")) {
            assertFalse(rebound.has(field), type.getName() + " must not bind " + field);
        }
    }

    private static void verifyContract(JsonNode contract) throws Exception {
        Class<?> controller = Class.forName(contract.path("controller").asText());
        assertEquals(
                contract.path("routes"),
                mapper.valueToTree(actualRoutes(controller)),
                "MVC route/permission/body/query drift");
        var requests = contract.path("requests").fields();
        while (requests.hasNext()) {
            var model = requests.next();
            models.verifyRequest(Class.forName(model.getKey()), model.getValue());
        }
        var queries = contract.path("queryModels").fields();
        while (queries.hasNext()) {
            var model = queries.next();
            assertEquals(
                    model.getValue(),
                    mapper.valueToTree(models.propertyTypes(Class.forName(model.getKey()), false)),
                    "query binding drift");
        }
        var responses = contract.path("responses").fields();
        while (responses.hasNext()) {
            var model = responses.next();
            models.verifyResponse(
                    Class.forName(model.getKey()),
                    model.getValue(),
                    contract.path("forbiddenResponseFields"));
        }
    }

    private static List<Map<String, Object>> actualRoutes(Class<?> controller) {
        List<Map<String, Object>> result = new ArrayList<>();
        var mapping = context.getBean(RequestMappingHandlerMapping.class);
        mapping.getHandlerMethods()
                .forEach(
                        (info, handler) -> {
                            if (!controller.equals(handler.getBeanType())) {
                                return;
                            }
                            for (String path : info.getPatternValues()) {
                                for (var verb : info.getMethodsCondition().getMethods()) {
                                    result.add(route(handler, path, verb.name()));
                                }
                            }
                        });
        // Keep manifest ordering explicit and independent of JVM map iteration.
        result.sort(java.util.Comparator.comparing(m -> m.get("path") + " " + m.get("method")));
        return result;
    }

    private static Map<String, Object> route(HandlerMethod handler, String path, String method) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("method", method);
        route.put("path", path);
        SaCheckPermission permission = handler.getMethodAnnotation(SaCheckPermission.class);
        assertTrue(
                permission == null || permission.value().length == 1,
                "contract must explicitly model composite permissions");
        route.put("permission", permission == null ? null : permission.value()[0]);
        route.put("response", simpleType(handler.getMethod().getGenericReturnType()));
        route.put("body", null);
        route.put("validBody", false);
        List<Map<String, Object>> query = new ArrayList<>();
        List<String> pathVariables = new ArrayList<>();
        for (Parameter parameter : handler.getMethod().getParameters()) {
            if (parameter.isAnnotationPresent(RequestBody.class)) {
                route.put("body", parameter.getType().getSimpleName());
                route.put("validBody", parameter.isAnnotationPresent(Valid.class));
            }
            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            if (requestParam != null) {
                query.add(routeQueryParameter(parameter, requestParam));
            }
            PathVariable variable = parameter.getAnnotation(PathVariable.class);
            if (variable != null) {
                assertEquals(
                        String.class,
                        parameter.getType(),
                        "opaque ID path parameters must stay String");
                pathVariables.add(
                        variable.value().isEmpty() ? parameter.getName() : variable.value());
            }
        }
        route.put("query", query);
        route.put("pathVariables", pathVariables);
        return route;
    }

    private static Map<String, Object> routeQueryParameter(
            Parameter parameter, RequestParam requestParam) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put(
                "name",
                requestParam.value().isEmpty() ? requestParam.name() : requestParam.value());
        item.put("javaType", simpleType(parameter.getParameterizedType()));
        item.put("required", requestParam.required());
        item.put("validation", annotationConstraints(parameter));
        return item;
    }

    private static String simpleType(Type type) {
        if (type instanceof Class<?> cls) {
            return cls.getSimpleName();
        }
        if (type instanceof ParameterizedType parameterized) {
            return simpleType(parameterized.getRawType())
                    + "<"
                    + String.join(
                            ",",
                            Arrays.stream(parameterized.getActualTypeArguments())
                                    .map(MachineHttpContractTest::simpleType)
                                    .toList())
                    + ">";
        }
        return type.getTypeName();
    }

    private static JsonNode load(String name) throws Exception {
        try (InputStream stream =
                MachineHttpContractTest.class.getResourceAsStream(
                        "/contracts/system/" + name + ".json")) {
            assertNotNull(stream, "contract must be packaged into test resources");
            return mapper.readTree(stream);
        }
    }

    // Explicitly registered lite configuration: never a component-scan candidate for the real
    // application.
    @EnableWebMvc
    static class WebConfiguration {
        @Bean
        UserController userController() throws Exception {
            return controller(UserController.class);
        }

        @Bean
        RoleController roleController() throws Exception {
            return controller(RoleController.class);
        }

        private static <T> T controller(Class<T> type) throws Exception {
            Constructor<?> constructor = type.getConstructors()[0];
            Object[] dependencies =
                    Arrays.stream(constructor.getParameterTypes()).map(t -> mock(t)).toArray();
            return type.cast(constructor.newInstance(dependencies));
        }
    }
}
