package com.robot.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.robot.gateway.properties.AuthProperties;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** 网关全局过滤器安全行为测试：/inner 屏蔽、白名单放行、无 Token 拒绝、 OPTIONS 预检放行、伪造内部头清洗。 */
class AuthGlobalFilterTest {
    private static final int MAX_TRACE_ID_LENGTH = 64;
    private static final int OVERLONG_TRACE_ID_LENGTH = 65;

    private AuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setWhitePaths(Arrays.asList("/auth/login", "/actuator/health"));
        filter = new AuthGlobalFilter(properties);
    }

    private MockServerWebExchange exchange(HttpMethod method, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build());
    }

    /** 放行链（下游直通）；返回请求头快照供断言。 */
    private final java.util.concurrent.atomic.AtomicReference<String> innerHeaderSeen =
            new java.util.concurrent.atomic.AtomicReference<>();

    private reactor.core.publisher.Mono<Void> passThroughChain(
            org.springframework.web.server.ServerWebExchange exchange) {
        innerHeaderSeen.set(exchange.getRequest().getHeaders().getFirst("X-Inner-Call"));
        return Mono.empty();
    }

    @Test
    @DisplayName("/inner/** 外部访问一律 403")
    void innerPathShouldBeForbidden() {
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "/inner/user/by-username");
        filter.filter(exchange, chain -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("白名单路径免 Token 放行")
    void whiteListedPathShouldPass() {
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/auth/login");
        filter.filter(exchange, this::passThroughChain).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    /** 关键链路守护：loginTicket 激活发生在用户拿到 Token 之前， 白名单漏配会导致多公司账号登录 401（历史缺陷，用默认配置直测防回归）。 */
    @Test
    @DisplayName("/auth/context/activate 免 Token 放行（默认白名单）")
    void activateShouldPassWithoutTokenByDefaultWhitelist() {
        AuthProperties defaults = new AuthProperties();
        AuthGlobalFilter defaultFilter = new AuthGlobalFilter(defaults);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/auth/context/activate").build());
        defaultFilter.filter(exchange, this::passThroughChain).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("非白名单无 Token 拒绝 401")
    void missingTokenShouldBeUnauthorized() {
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "/sys/users");
        filter.filter(exchange, chain -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("携带 Bearer Token 放行并注入 TraceId")
    void validTokenShouldPassWithTraceId() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/sys/users")
                                .header("Authorization", "Bearer token-1")
                                .build());
        filter.filter(exchange, this::passThroughChain).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-Id")).isNotBlank();
    }

    @Test
    void legalTraceIdsArePreservedInForwardedAndResponseHeaders() {
        for (String traceId :
                new String[] {"x", "TRACE_2026-10", "A".repeat(MAX_TRACE_ID_LENGTH)}) {
            MockServerWebExchange exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.post("/auth/login")
                                    .header("X-Trace-Id", traceId)
                                    .build());
            filter.filter(
                            exchange,
                            forwarded -> {
                                assertThat(
                                                forwarded
                                                        .getRequest()
                                                        .getHeaders()
                                                        .getFirst("X-Trace-Id"))
                                        .isEqualTo(traceId);
                                return Mono.empty();
                            })
                    .block();
            assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-Id"))
                    .isEqualTo(traceId);
        }
    }

    @Test
    void invalidTraceHeadersAreReplacedBeforeForwarding() {
        for (String traceId :
                new String[] {
                    "",
                    " ",
                    "A".repeat(OVERLONG_TRACE_ID_LENGTH),
                    "trace\r\npassword=fixture",
                    "http://downstream.fixture?token=fixture"
                }) {
            MockServerWebExchange exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.post("/auth/login")
                                    .header("X-Trace-Id", traceId)
                                    .build());
            filter.filter(
                            exchange,
                            forwarded -> {
                                assertThat(
                                                forwarded
                                                        .getRequest()
                                                        .getHeaders()
                                                        .getFirst("X-Trace-Id"))
                                        .matches("[a-f0-9]{32}")
                                        .isNotEqualTo(traceId)
                                        .isEqualTo(
                                                exchange.getResponse()
                                                        .getHeaders()
                                                        .getFirst("X-Trace-Id"));
                                return Mono.empty();
                            })
                    .block();
        }
    }

    @Test
    void invalidTraceHeaderIsNotEchoedByRejectedRequest() {
        String supplied = "http://downstream.fixture?password=fixture";
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/sys/users")
                                .header("X-Trace-Id", supplied)
                                .build());
        filter.filter(
                        exchange,
                        forwarded ->
                                Mono.error(new AssertionError("rejected request must not forward")))
                .block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-Id"))
                .matches("[a-f0-9]{32}");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"traceId\"")
                .doesNotContain(supplied, "downstream.fixture", "password=fixture");
    }

    @Test
    void reactiveTracePropagationDoesNotLeaveThreadMdcAfterChainFailure() {
        java.util.Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.clear();
        try {
            MockServerWebExchange exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.post("/auth/login")
                                    .header("X-Trace-Id", "safe-trace")
                                    .build());
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    filter.filter(
                                                    exchange,
                                                    forwarded -> {
                                                        assertThat(MDC.get("traceId")).isNull();
                                                        return Mono.error(
                                                                new IllegalStateException(
                                                                        "fixture failure"));
                                                    })
                                            .block())
                    .isInstanceOf(IllegalStateException.class);
            assertThat(MDC.get("traceId")).isNull();
        } finally {
            MDC.clear();
            if (previous != null) {
                MDC.setContextMap(previous);
            }
        }
    }

    @Test
    @DisplayName("OPTIONS 预检请求免鉴权放行（CORS）")
    void optionsPreflightShouldPass() {
        MockServerWebExchange exchange = exchange(HttpMethod.OPTIONS, "/sys/users");
        filter.filter(exchange, this::passThroughChain).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("客户端伪造的 X-Inner-Call 头被清洗")
    void forgedInnerHeaderShouldBeStripped() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/auth/menu-list")
                                .header("Authorization", "Bearer t")
                                .header("X-Inner-Call", "forged-secret")
                                .build());
        filter.filter(exchange, this::passThroughChain).block();
        assertThat(innerHeaderSeen.get()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "/auth/login,GET", "/auth/login,OPTIONS",
        "/auth/menu-list,GET", "/auth/menu-list,OPTIONS",
        "/sys/users,GET", "/sys/users,OPTIONS"
    })
    void allInternalHeadersAreStrippedOnProtectedPublicAndPreflightRoutes(
            String path, String methodName) {
        HttpMethod method = HttpMethod.valueOf(methodName);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.method(method, path)
                                .header("Authorization", "Bearer t")
                                .header("x-INNER-call", "secret", "second-secret")
                                .header("X-Inner-User-Id", "forged-user")
                                .header("x-inner-company-id", "forged-company")
                                .header("X-Inner-Username", "forged-username")
                                .header("X-Inner-Future-Claim", "forged-future")
                                .build());
        filter.filter(
                        exchange,
                        forwarded -> {
                            assertThat(forwarded.getRequest().getHeaders().headerNames())
                                    .noneMatch(
                                            name ->
                                                    name.toLowerCase(java.util.Locale.ROOT)
                                                            .startsWith("x-inner-"));
                            assertThat(
                                            forwarded
                                                    .getRequest()
                                                    .getHeaders()
                                                    .getFirst("Authorization"))
                                    .isEqualTo("Bearer t");
                            return Mono.empty();
                        })
                .block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
