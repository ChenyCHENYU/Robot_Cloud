package com.robot.system.contract;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.Result;
import com.robot.common.web.config.RobotWebAutoConfiguration;
import com.robot.system.role.controller.RoleController;
import com.robot.system.user.controller.UserController;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
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

import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Machine contract gate uses real MVC mappings, Jackson properties and Bean Validation metadata. */
class MachineHttpContractTest {
    private static AnnotationConfigWebApplicationContext context;
    private static ObjectMapper mapper;
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

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
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
        if (validatorFactory != null) validatorFactory.close();
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
        ObjectNode fields = (ObjectNode) changed.path("requests")
                .path("com.robot.system.api.dto.user.UserSaveRequest").path("fields");
        fields.set("securityVersion", fields.path("revision").deepCopy());
        assertThrows(AssertionError.class, () -> verifyContract(changed), "must reject unexpected writable fields");
    }

    @Test
    void responseEnvelopeKeepsNumericCountersStringIdsAndRealTimeFormat() throws Exception {
        JsonNode result = mapper.valueToTree(Result.success(PageResult.of(List.of("9007199254740993"), 21, 2, 10)));
        assertEquals(0, result.path("code").intValue());
        assertTrue(result.path("code").isInt());
        assertEquals(result.path("message"), result.path("msg"));
        assertEquals(Set.of("list", "total", "page", "pageSize"), names(result.path("data")));
        for (String counter : List.of("total", "page", "pageSize")) assertTrue(result.path("data").path(counter).isNumber());
        assertTrue(result.path("data").path("list").get(0).isTextual());
        assertEquals("2026-10-09 12:34:56", mapper.valueToTree(LocalDateTime.of(2026, 10, 9, 12, 34, 56)).asText());
    }

    @Test
    void aliasesBindAndServerOnlyFieldsCannotBecomeRequestProperties() throws Exception {
        for (String resource : List.of("users", "roles")) {
            JsonNode contract = load(resource);
            var models = contract.path("requests").fields();
            while (models.hasNext()) {
                var model = models.next();
                Class<?> type = Class.forName(model.getKey());
                var fields = model.getValue().path("fields").fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    for (JsonNode alias : field.getValue().path("aliases")) {
                        JsonNode value = "Integer".equals(field.getValue().path("javaType").asText())
                                ? mapper.valueToTree(3) : mapper.valueToTree("contract-alias");
                        ObjectNode body = mapper.createObjectNode().set(alias.asText(), value);
                        Object bean = mapper.treeToValue(body, type);
                        assertEquals(value, mapper.valueToTree(bean).path(field.getKey()));
                    }
                }
                ObjectNode forged = mapper.createObjectNode().put("securityVersion", 999).put("companyId", "foreign")
                        .put("createUserNo", "forged").put("deleteFlag", 0).put("passwordHash", "forged");
                JsonNode rebound = mapper.valueToTree(mapper.treeToValue(forged, type));
                for (String field : List.of("securityVersion", "companyId", "createUserNo", "deleteFlag", "passwordHash")) {
                    assertFalse(rebound.has(field), type.getName() + " must not bind " + field);
                }
            }
        }
    }

    private static void verifyContract(JsonNode contract) throws Exception {
        Class<?> controller = Class.forName(contract.path("controller").asText());
        assertEquals(contract.path("routes"), mapper.valueToTree(actualRoutes(controller)), "MVC route/permission/body/query drift");
        var requests = contract.path("requests").fields();
        while (requests.hasNext()) {
            var model = requests.next();
            verifyRequest(Class.forName(model.getKey()), model.getValue());
        }
        var queries = contract.path("queryModels").fields();
        while (queries.hasNext()) {
            var model = queries.next();
            assertEquals(model.getValue(), mapper.valueToTree(propertyTypes(Class.forName(model.getKey()), false)), "query binding drift");
        }
        var responses = contract.path("responses").fields();
        while (responses.hasNext()) {
            var model = responses.next();
            verifyResponse(Class.forName(model.getKey()), model.getValue(), contract.path("forbiddenResponseFields"));
        }
    }

    private static List<Map<String, Object>> actualRoutes(Class<?> controller) {
        List<Map<String, Object>> result = new ArrayList<>();
        var mapping = context.getBean(RequestMappingHandlerMapping.class);
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (!controller.equals(handler.getBeanType())) return;
            for (String path : info.getPatternValues()) {
                for (var verb : info.getMethodsCondition().getMethods()) result.add(route(handler, path, verb.name()));
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
        assertTrue(permission == null || permission.value().length == 1, "contract must explicitly model composite permissions");
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
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", requestParam.value().isEmpty() ? requestParam.name() : requestParam.value());
                item.put("javaType", simpleType(parameter.getParameterizedType()));
                item.put("required", requestParam.required());
                item.put("validation", annotationConstraints(parameter));
                query.add(item);
            }
            PathVariable variable = parameter.getAnnotation(PathVariable.class);
            if (variable != null) {
                assertEquals(String.class, parameter.getType(), "opaque ID path parameters must stay String");
                pathVariables.add(variable.value().isEmpty() ? parameter.getName() : variable.value());
            }
        }
        route.put("query", query);
        route.put("pathVariables", pathVariables);
        return route;
    }

    private static void verifyRequest(Class<?> type, JsonNode expected) {
        Map<String, String> writable = propertyTypes(type, false);
        assertEquals(names(expected.path("fields")), writable.keySet(), type + " writable whitelist drift");
        var bean = validator.getConstraintsForClass(type);
        var fields = expected.path("fields").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode specification = field.getValue();
            assertEquals(specification.path("javaType").asText(), writable.get(field.getKey()), type + "." + field.getKey());
            PropertyDescriptor descriptor = bean.getConstraintsForProperty(field.getKey());
            assertEquals(specification.path("validation"), mapper.valueToTree(constraints(descriptor)), "validation drift " + type + "." + field.getKey());
            List<String> aliases = new ArrayList<>();
            try {
                Field javaField = type.getDeclaredField(field.getKey());
                JsonAlias alias = javaField.getAnnotation(JsonAlias.class);
                if (alias != null) aliases.addAll(Arrays.asList(alias.value()));
            } catch (NoSuchFieldException error) {
                fail("request field must be an explicit DTO field: " + field.getKey());
            }
            assertEquals(specification.path("aliases"), mapper.valueToTree(aliases));
            if (specification.has("elementValidation")) {
                Map<String, Object> element = new TreeMap<>();
                assertNotNull(descriptor);
                for (ContainerElementTypeDescriptor child : descriptor.getConstrainedContainerElementTypes()) element.putAll(constraints(child));
                assertEquals(specification.path("elementValidation"), mapper.valueToTree(element), "collection element validation drift");
            }
            for (String semantic : List.of("semanticId", "sourceOfTruth", "constraintSource", "definition")) {
                assertFalse(specification.path(semantic).asText().isBlank(), "missing semantic contract: " + semantic);
            }
        }
        Map<String, Object> extra = new TreeMap<>();
        for (PropertyDescriptor property : bean.getConstrainedProperties()) {
            if (!writable.containsKey(property.getPropertyName())) extra.put(property.getPropertyName(), constraints(property));
        }
        assertEquals(expected.path("classValidation"), mapper.valueToTree(extra), "non-writable cross-field validation drift");
    }

    private static void verifyResponse(Class<?> type, JsonNode expected, JsonNode forbidden) throws Exception {
        Map<String, String> actual = propertyTypes(type, true);
        assertEquals(names(expected), actual.keySet(), type + " response whitelist drift");
        JsonNode serialized = mapper.valueToTree(sampleBean(type));
        var fields = expected.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            assertEquals(field.getValue().path("javaType").asText(), actual.get(field.getKey()));
            JsonNode value = serialized.path(field.getKey());
            switch (field.getValue().path("jsonType").asText()) {
                case "number" -> assertTrue(value.isNumber(), field.getKey());
                case "array" -> assertTrue(value.isArray(), field.getKey());
                case "string" -> assertTrue(value.isTextual(), field.getKey());
                default -> fail("unknown JSON type");
            }
            if ("LocalDateTime".equals(actual.get(field.getKey()))) assertEquals("2026-10-09 12:34:56", value.asText());
        }
        for (JsonNode name : forbidden) assertFalse(actual.containsKey(name.asText()), "credential/governance leak: " + name);
    }

    private static Map<String, String> propertyTypes(Class<?> type, boolean serialize) {
        var javaType = mapper.constructType(type);
        var description = serialize ? mapper.getSerializationConfig().introspect(javaType) : mapper.getDeserializationConfig().introspect(javaType);
        Map<String, String> properties = new TreeMap<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            if (serialize ? property.couldSerialize() : property.couldDeserialize()) {
                properties.put(property.getName(), simpleJavaType(property.getPrimaryType()));
            }
        }
        return properties;
    }

    private static Object sampleBean(Class<?> type) throws Exception {
        if (type.isRecord()) {
            var components = type.getRecordComponents();
            Constructor<?> constructor = type.getDeclaredConstructor(Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class[]::new));
            return constructor.newInstance(Arrays.stream(components).map(c -> sample(c.getType())).toArray());
        }
        Object bean;
        try {
            bean = type.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException absentNoArgs) {
            Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
                    .max(java.util.Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
            constructor.setAccessible(true);
            bean = constructor.newInstance(Arrays.stream(constructor.getParameterTypes()).map(MachineHttpContractTest::sample).toArray());
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            field.set(bean, sample(field.getType()));
        }
        return bean;
    }

    private static Object sample(Class<?> type) {
        if (type == String.class) return "9007199254740993";
        if (type == Integer.class || type == int.class) return 3;
        if (type == Long.class || type == long.class) return 7L;
        if (type == LocalDateTime.class) return LocalDateTime.of(2026, 10, 9, 12, 34, 56);
        if (type == List.class) return List.of("resource-id");
        throw new IllegalArgumentException("unmodeled public type: " + type);
    }

    private static Map<String, Object> constraints(ElementDescriptor descriptor) {
        Map<String, Object> result = new TreeMap<>();
        if (descriptor != null) {
            for (ConstraintDescriptor<?> constraint : descriptor.getConstraintDescriptors()) {
                result.put(constraint.getAnnotation().annotationType().getSimpleName(), supportedAttributes(constraint.getAttributes()));
            }
        }
        return result;
    }

    private static Map<String, Object> annotationConstraints(AnnotatedElement element) {
        Map<String, Object> result = new TreeMap<>();
        for (var annotation : element.getAnnotations()) {
            if (!annotation.annotationType().getPackageName().equals("jakarta.validation.constraints")) continue;
            Map<String, Object> attributes = new TreeMap<>();
            for (var method : annotation.annotationType().getDeclaredMethods()) {
                try { attributes.put(method.getName(), method.invoke(annotation)); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            }
            result.put(annotation.annotationType().getSimpleName(), supportedAttributes(attributes));
        }
        return result;
    }

    private static Map<String, Object> supportedAttributes(Map<String, Object> attributes) {
        Map<String, Object> result = new TreeMap<>();
        for (String name : List.of("min", "max", "value", "regexp")) {
            if (attributes.containsKey(name)) {
                Object value = attributes.get(name);
                // JSON has one integer value space; annotation long/int reflection types are not a contract change.
                result.put(name, value instanceof Number number ? Math.toIntExact(number.longValue()) : value);
            }
        }
        return result;
    }

    private static String simpleJavaType(com.fasterxml.jackson.databind.JavaType type) {
        if (type.containedTypeCount() == 0) return type.getRawClass().getSimpleName();
        List<String> arguments = new ArrayList<>();
        for (int index = 0; index < type.containedTypeCount(); index++) arguments.add(simpleJavaType(type.containedType(index)));
        return type.getRawClass().getSimpleName() + "<" + String.join(",", arguments) + ">";
    }

    private static String simpleType(Type type) {
        if (type instanceof Class<?> cls) return cls.getSimpleName();
        if (type instanceof ParameterizedType parameterized) {
            return simpleType(parameterized.getRawType()) + "<" + String.join(",", Arrays.stream(parameterized.getActualTypeArguments()).map(MachineHttpContractTest::simpleType).toList()) + ">";
        }
        return type.getTypeName();
    }

    private static Set<String> names(JsonNode node) {
        Set<String> result = new TreeSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static JsonNode load(String name) throws Exception {
        try (InputStream stream = MachineHttpContractTest.class.getResourceAsStream("/contracts/system/" + name + ".json")) {
            assertNotNull(stream, "contract must be packaged into test resources");
            return mapper.readTree(stream);
        }
    }

    // Explicitly registered lite configuration: never a component-scan candidate for the real application.
    @EnableWebMvc
    static class WebConfiguration {
        @Bean UserController userController() throws Exception { return controller(UserController.class); }
        @Bean RoleController roleController() throws Exception { return controller(RoleController.class); }
        private static <T> T controller(Class<T> type) throws Exception {
            Constructor<?> constructor = type.getConstructors()[0];
            Object[] dependencies = Arrays.stream(constructor.getParameterTypes()).map(t -> mock(t)).toArray();
            return type.cast(constructor.newInstance(dependencies));
        }
    }
}
