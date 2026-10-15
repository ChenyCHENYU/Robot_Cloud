package com.robot.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class ProductionManagementFilterTest {
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
    void productionGatewayDocumentationPathsNeverReachAHandler(String path) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        new ProductionManagementFilter(environment)
                .filter(
                        exchange,
                        ignored ->
                                Mono.error(
                                        new AssertionError(
                                                "production documentation handler must never"
                                                        + " execute")))
                .block();
        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
    }

    @ParameterizedTest
    @CsvSource({
        "/actuator/prometheus,FORBIDDEN",
        "/actuator/gateway/routes,FORBIDDEN",
        "/v3/api-docs,NOT_FOUND"
    })
    void productionManagementWithoutSecretAndDocsNeverReachHandler(String path, HttpStatus status) {
        var environment =
                new MockEnvironment()
                        .withProperty("robot.management.secret", "fixture-management-key");
        environment.setActiveProfiles("prod");
        var filter = new ProductionManagementFilter(environment);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        AtomicInteger calls = new AtomicInteger();
        filter.filter(
                        exchange,
                        ignored -> {
                            calls.incrementAndGet();
                            return Mono.empty();
                        })
                .block();
        assertEquals(0, calls.get());
        assertEquals(status, exchange.getResponse().getStatusCode());
    }

    @Test
    void productionManagementNeedsUniqueSecretButHealthIsPublic() {
        var environment =
                new MockEnvironment()
                        .withProperty("robot.management.secret", "fixture-management-key");
        environment.setActiveProfiles("prod");
        var filter = new ProductionManagementFilter(environment);
        var authorized =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/actuator/prometheus")
                                .header("X-Robot-Management-Token", "fixture-management-key"));
        AtomicInteger calls = new AtomicInteger();
        filter.filter(
                        authorized,
                        ignored -> {
                            calls.incrementAndGet();
                            return Mono.empty();
                        })
                .block();
        filter.filter(
                        MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health")),
                        ignored -> {
                            calls.incrementAndGet();
                            return Mono.empty();
                        })
                .block();
        assertEquals(2, calls.get());
        var repeated =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/actuator/prometheus")
                                .header(
                                        "X-Robot-Management-Token",
                                        "fixture-management-key",
                                        "fixture-management-key"));
        filter.filter(repeated, ignored -> Mono.error(new AssertionError())).block();
        assertEquals(HttpStatus.FORBIDDEN, repeated.getResponse().getStatusCode());
    }
}
