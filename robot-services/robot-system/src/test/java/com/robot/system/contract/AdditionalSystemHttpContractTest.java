package com.robot.system.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.system.account.controller.AccountController;
import com.robot.system.account.controller.inner.LoginLogInnerController;
import com.robot.system.dept.controller.DepartmentController;
import com.robot.system.dict.controller.DictController;
import com.robot.system.menu.controller.MenuButtonController;
import com.robot.system.menu.controller.MenuController;
import com.robot.system.menu.controller.inner.MenuInnerController;
import com.robot.system.permission.controller.CacheOutboxGovernanceController;
import com.robot.system.permission.controller.PermissionGovernanceController;
import com.robot.system.user.controller.inner.UserInnerController;
import jakarta.validation.Valid;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 新增覆盖原用户/角色合同之外的全部System Controller路由、协议形态和请求白名单。 固定JSON合同为审查输入，CI不生成/刷新期望；行为/鉴权/事务由独立回归负责。 */
class AdditionalSystemHttpContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void resourcesGovernanceAccountAndInternalMappingsMatchFrozenContract() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Web.class);
            context.refresh();
            for (String resource : List.of("resources", "governance", "account", "inner")) {
                var contract =
                        JSON.readTree(
                                Objects.requireNonNull(
                                        getClass()
                                                .getResourceAsStream(
                                                        "/contracts/system/"
                                                                + resource
                                                                + ".json")));
                assertEquals(
                        contract.path("routes"),
                        JSON.valueToTree(actualRoutes(context, contract)),
                        resource + " actual MVC drift");
                var models = contract.path("requestModels").fields();
                while (models.hasNext()) {
                    verifyRequestModel(resource, models.next());
                }
            }
        }
    }

    private static Map<String, Object> actualRoutes(
            AnnotationConfigWebApplicationContext context,
            com.fasterxml.jackson.databind.JsonNode contract) {
        Set<String> controllers = new HashSet<>();
        contract.path("controllers").forEach(name -> controllers.add(name.asText()));
        Map<String, Object> actual = new TreeMap<>();
        context.getBean(RequestMappingHandlerMapping.class)
                .getHandlerMethods()
                .forEach(
                        (mapping, handler) -> {
                            if (controllers.contains(handler.getBeanType().getName())) {
                                String id =
                                        handler.getBeanType().getSimpleName()
                                                + "."
                                                + handler.getMethod().getName();
                                assertNull(
                                        actual.put(id, route(mapping, handler)),
                                        "ambiguous handler identity " + id);
                            }
                        });
        return actual;
    }

    private static Map<String, Object> route(
            org.springframework.web.servlet.mvc.method.RequestMappingInfo mapping,
            org.springframework.web.method.HandlerMethod handler) {
        SaCheckPermission permission = handler.getMethodAnnotation(SaCheckPermission.class);
        if (permission == null) {
            permission = handler.getBeanType().getAnnotation(SaCheckPermission.class);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "method",
                mapping.getMethodsCondition().getMethods().stream()
                        .map(Enum::name)
                        .sorted()
                        .toList());
        result.put("paths", mapping.getPatternValues().stream().sorted().toList());
        result.put(
                "consumes",
                mapping.getConsumesCondition().getConsumableMediaTypes().stream()
                        .map(Object::toString)
                        .sorted()
                        .toList());
        result.put("permission", permission == null ? null : List.of(permission.value()));
        result.put("response", simple(handler.getMethod().getGenericReturnType()));
        Map<String, Object> parameters = new TreeMap<>();
        Arrays.stream(handler.getMethod().getParameters())
                .forEach(
                        parameter ->
                                parameters.put(parameterName(parameter), parameter(parameter)));
        result.put("parameters", parameters);
        return result;
    }

    private static Map<String, Object> parameter(Parameter parameter) {
        RequestBody body = parameter.getAnnotation(RequestBody.class);
        RequestParam query = parameter.getAnnotation(RequestParam.class);
        PathVariable path = parameter.getAnnotation(PathVariable.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", simple(parameter.getParameterizedType()));
        result.put(
                "source",
                body != null ? "body" : query != null ? "query" : path != null ? "path" : "model");
        result.put("valid", parameter.isAnnotationPresent(Valid.class));
        result.put(
                "required",
                body != null ? body.required() : query != null ? query.required() : path != null);
        return result;
    }

    private static String parameterName(Parameter parameter) {
        RequestParam query = parameter.getAnnotation(RequestParam.class);
        PathVariable path = parameter.getAnnotation(PathVariable.class);
        if (query != null && !query.value().isEmpty()) {
            return query.value();
        }
        return path != null && !path.value().isEmpty() ? path.value() : parameter.getName();
    }

    private static void verifyRequestModel(
            String resource, Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> model)
            throws Exception {
        Class<?> type = Class.forName(model.getKey());
        Map<String, String> properties = new TreeMap<>();
        var description = JSON.getDeserializationConfig().introspect(JSON.constructType(type));
        description.findProperties().stream()
                .filter(property -> property.couldDeserialize())
                .forEach(
                        property ->
                                properties.put(
                                        property.getName(),
                                        property.getPrimaryType()
                                                .toCanonical()
                                                .replace("java.lang.", "")));
        assertEquals(
                model.getValue().path("properties"),
                JSON.valueToTree(properties),
                type + " writable drift");
        assertFalse(model.getValue().path("sourceOfTruth").asText().isBlank());
        assertFalse(model.getValue().path("definition").asText().isBlank());
        if (!"inner".equals(resource)) {
            for (String forbidden :
                    List.of(
                            "companyId",
                            "platformAdminFlag",
                            "deleteFlag",
                            "securityVersion",
                            "createUserNo")) {
                assertFalse(properties.containsKey(forbidden), "trusted field is writable");
            }
        }
    }

    private static String simple(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz.getSimpleName();
        }
        ParameterizedType generic = (ParameterizedType) type;
        return simple(generic.getRawType())
                + "<"
                + String.join(
                        ",",
                        Arrays.stream(generic.getActualTypeArguments())
                                .map(AdditionalSystemHttpContractTest::simple)
                                .toList())
                + ">";
    }

    private static <T> T controller(Class<T> type) throws Exception {
        Constructor<?> constructor = type.getConstructors()[0];
        return type.cast(
                constructor.newInstance(
                        Arrays.stream(constructor.getParameterTypes())
                                .map(parameter -> mock(parameter))
                                .toArray()));
    }

    @EnableWebMvc
    static class Web {
        @Bean
        MenuController menus() throws Exception {
            return controller(MenuController.class);
        }

        @Bean
        MenuButtonController buttons() throws Exception {
            return controller(MenuButtonController.class);
        }

        @Bean
        DictController dictionaries() throws Exception {
            return controller(DictController.class);
        }

        @Bean
        DepartmentController departments() throws Exception {
            return controller(DepartmentController.class);
        }

        @Bean
        PermissionGovernanceController governance() throws Exception {
            return controller(PermissionGovernanceController.class);
        }

        @Bean
        CacheOutboxGovernanceController outbox() throws Exception {
            return controller(CacheOutboxGovernanceController.class);
        }

        @Bean
        AccountController account() throws Exception {
            return controller(AccountController.class);
        }

        @Bean
        UserInnerController usersInner() throws Exception {
            return controller(UserInnerController.class);
        }

        @Bean
        MenuInnerController menusInner() throws Exception {
            return controller(MenuInnerController.class);
        }

        @Bean
        LoginLogInnerController loginInner() throws Exception {
            return controller(LoginLogInnerController.class);
        }
    }
}
