package com.robot.common.security.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ProductionManagementFilterTest {
    private static final int FORBIDDEN_STATUS = 403;
    private static final int NOT_FOUND_STATUS = 404;
    private static final int EXPECTED_ALLOWED_HANDLER_CALLS = 3;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/v3/api-docs",
                "/v3/api-docs/group",
                "/doc.html",
                "/webjars/swagger-ui/ui.js",
                "/swagger-ui.html",
                "/swagger-ui/",
                "/swagger-ui/index.html",
                "/swagger-ui/swagger-initializer.js"
            })
    void productionDirectDocumentationPathsNeverReachAHandler(String path) throws Exception {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var response = new MockHttpServletResponse();
        new ProductionManagementFilter(environment)
                .doFilter(
                        new MockHttpServletRequest("GET", path),
                        response,
                        (request, downstream) ->
                                fail("production documentation handler must never execute"));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND.value(), response.getStatus());
    }

    @Test
    void directServletManagementIsProtectedAndLocalIsUsable() throws Exception {
        var environment =
                new MockEnvironment().withProperty("robot.management.secret", "fixture-key");
        environment.setActiveProfiles("prod");
        var filter = new ProductionManagementFilter(environment);
        AtomicInteger calls = new AtomicInteger();
        var publicHealth = new MockHttpServletRequest("GET", "/actuator/health");
        filter.doFilter(
                publicHealth,
                new MockHttpServletResponse(),
                (request, response) -> calls.incrementAndGet());
        assertEquals(1, calls.get());
        var protectedRequest = new MockHttpServletRequest("GET", "/actuator/prometheus");
        var rejected = new MockHttpServletResponse();
        filter.doFilter(
                protectedRequest,
                rejected,
                (request, response) -> fail("anonymous handler must not execute"));
        assertEquals(FORBIDDEN_STATUS, rejected.getStatus());
        protectedRequest.addHeader("X-Robot-Management-Token", "fixture-key");
        filter.doFilter(
                protectedRequest,
                new MockHttpServletResponse(),
                (request, response) -> calls.incrementAndGet());
        assertEquals(2, calls.get());
        protectedRequest.addHeader("X-Robot-Management-Token", "fixture-key");
        var repeated = new MockHttpServletResponse();
        filter.doFilter(
                protectedRequest,
                repeated,
                (request, response) -> fail("repeated credential must fail"));
        assertEquals(FORBIDDEN_STATUS, repeated.getStatus());
        var docs = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest("GET", "/v3/api-docs"),
                docs,
                (request, response) -> fail("production docs must be closed"));
        assertEquals(NOT_FOUND_STATUS, docs.getStatus());
        environment.setActiveProfiles("local");
        filter.doFilter(
                new MockHttpServletRequest("GET", "/actuator/prometheus"),
                new MockHttpServletResponse(),
                (request, response) -> calls.incrementAndGet());
        assertEquals(EXPECTED_ALLOWED_HANDLER_CALLS, calls.get());
    }
}
