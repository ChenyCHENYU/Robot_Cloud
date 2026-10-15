package com.robot.system.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Jackson properties and Bean Validation metadata assertions for the frozen public wire models. */
final class ContractModelAssertions {
    private final ObjectMapper mapper;
    private final Validator validator;
    private final ContractModelFixtures fixtures;

    ContractModelAssertions(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
        this.fixtures = new ContractModelFixtures(mapper);
    }

    void verifyRequest(Class<?> type, JsonNode expected) {
        Map<String, String> writable = fixtures.propertyTypes(type, false);
        assertEquals(
                names(expected.path("fields")),
                writable.keySet(),
                type + " writable whitelist drift");
        var bean = validator.getConstraintsForClass(type);
        var fields = expected.path("fields").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            verifyField(
                    type,
                    field.getKey(),
                    field.getValue(),
                    writable.get(field.getKey()),
                    bean.getConstraintsForProperty(field.getKey()));
        }
        Map<String, Object> extra = new TreeMap<>();
        for (PropertyDescriptor property : bean.getConstrainedProperties()) {
            if (!writable.containsKey(property.getPropertyName())) {
                extra.put(property.getPropertyName(), constraints(property));
            }
        }
        assertEquals(
                expected.path("classValidation"),
                mapper.valueToTree(extra),
                "non-writable cross-field validation drift");
    }

    private void verifyField(
            Class<?> type,
            String name,
            JsonNode specification,
            String javaType,
            PropertyDescriptor descriptor) {
        assertEquals(specification.path("javaType").asText(), javaType, type + "." + name);
        assertEquals(
                specification.path("validation"),
                mapper.valueToTree(constraints(descriptor)),
                "validation drift " + type + "." + name);
        assertEquals(specification.path("aliases"), mapper.valueToTree(aliases(type, name)));
        if (specification.has("elementValidation")) {
            Map<String, Object> element = new TreeMap<>();
            assertNotNull(descriptor);
            for (ContainerElementTypeDescriptor child :
                    descriptor.getConstrainedContainerElementTypes()) {
                element.putAll(constraints(child));
            }
            assertEquals(
                    specification.path("elementValidation"),
                    mapper.valueToTree(element),
                    "collection element validation drift");
        }
        for (String semantic :
                List.of("semanticId", "sourceOfTruth", "constraintSource", "definition")) {
            assertFalse(
                    specification.path(semantic).asText().isBlank(),
                    "missing semantic contract: " + semantic);
        }
    }

    private static List<String> aliases(Class<?> type, String name) {
        try {
            JsonAlias annotation = type.getDeclaredField(name).getAnnotation(JsonAlias.class);
            return annotation == null ? List.of() : Arrays.asList(annotation.value());
        } catch (NoSuchFieldException error) {
            return fail("request field must be an explicit DTO field: " + name);
        }
    }

    Map<String, String> propertyTypes(Class<?> type, boolean serialize) {
        return fixtures.propertyTypes(type, serialize);
    }

    void verifyResponse(Class<?> type, JsonNode expected, JsonNode forbidden) throws Exception {
        Map<String, String> actual = fixtures.propertyTypes(type, true);
        assertEquals(names(expected), actual.keySet(), type + " response whitelist drift");
        JsonNode serialized = mapper.valueToTree(fixtures.sampleBean(type));
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
            if ("LocalDateTime".equals(actual.get(field.getKey()))) {
                assertEquals("2026-10-09 12:34:56", value.asText());
            }
        }
        for (JsonNode name : forbidden) {
            assertFalse(actual.containsKey(name.asText()), "credential/governance leak: " + name);
        }
    }

    private Map<String, Object> constraints(ElementDescriptor descriptor) {
        Map<String, Object> result = new TreeMap<>();
        if (descriptor != null) {
            for (ConstraintDescriptor<?> constraint : descriptor.getConstraintDescriptors()) {
                result.put(
                        constraint.getAnnotation().annotationType().getSimpleName(),
                        supportedAttributes(constraint.getAttributes()));
            }
        }
        return result;
    }

    static Map<String, Object> annotationConstraints(AnnotatedElement element) {
        Map<String, Object> result = new TreeMap<>();
        for (var annotation : element.getAnnotations()) {
            if (!annotation
                    .annotationType()
                    .getPackageName()
                    .equals("jakarta.validation.constraints")) {
                continue;
            }
            Map<String, Object> attributes = annotationAttributes(annotation);
            result.put(
                    annotation.annotationType().getSimpleName(), supportedAttributes(attributes));
        }
        return result;
    }

    private static Map<String, Object> annotationAttributes(
            java.lang.annotation.Annotation annotation) {
        Map<String, Object> attributes = new TreeMap<>();
        for (var method : annotation.annotationType().getDeclaredMethods()) {
            try {
                attributes.put(method.getName(), method.invoke(annotation));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        }
        return attributes;
    }

    private static Map<String, Object> supportedAttributes(Map<String, Object> attributes) {
        Map<String, Object> result = new TreeMap<>();
        for (String name : List.of("min", "max", "value", "regexp", "optional")) {
            if (attributes.containsKey(name)) {
                Object value = attributes.get(name);
                // JSON has one integer value space; annotation long/int reflection types are not a
                // contract change.
                result.put(
                        name,
                        value instanceof Number number
                                ? Math.toIntExact(number.longValue())
                                : value);
            }
        }
        return result;
    }

    static Set<String> names(JsonNode node) {
        Set<String> result = new TreeSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }
}
