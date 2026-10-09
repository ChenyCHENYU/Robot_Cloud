package com.robot.common.web.handler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.web.filter.TraceIdFilter;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 实际 MVC 异常分派与 Logback 事件证明技术载荷、拒绝值和原始 URI 不回显。 */
class GlobalExceptionLoggingTest {
    private static final String TECHNICAL_PAYLOAD = "INSERT INTO secret password=fixture-password "
            + "Bearer fixture-access-token http://downstream.fixture?token=fixture-access-token";
    private static final String BUSINESS_MESSAGE = "当前公司不允许修改共享账号";
    private static final String SAFE_TRACE = "safe-trace-001";
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private ListAppender<ILoggingEvent> events;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    private Map<String, String> previousMdc;

    @BeforeEach
    void setUp() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        events = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        events.start();
        logger.addAppender(events);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler()).setValidator(validator)
                .addFilter(new TraceIdFilter()).build();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(events);
        events.stop();
        validator.close();
        MDC.clear();
        if (previousMdc != null) {
            MDC.setContextMap(previousMdc);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "optimistic", "unexpected"})
    void technicalCauseMessagesAndUriAreAbsentFromBodyAndActualLog(String kind) throws Exception {
        MvcResult response = mvc.perform(get("/failure/{kind}/{secret}", kind, "request-uri-token")
                .header("X-Trace-Id", SAFE_TRACE)).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        String body = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = mapper.readTree(body);
        ResultCode expected = switch (kind) {
            case "duplicate" -> ResultCode.DATA_DUPLICATED;
            case "optimistic" -> ResultCode.DATA_CONCURRENT_CONFLICT;
            default -> ResultCode.FAILED;
        };
        assertThat(json.path("code").asInt()).isEqualTo(expected.getCode());
        assertThat(json.path("message").asText()).isEqualTo(expected.getMessage());
        assertThat(json.path("traceId").asText()).isEqualTo(SAFE_TRACE);
        assertSafeText(body);
        assertSafeLog(kind.equals("duplicate") ? "DUPLICATE_KEY"
                : kind.equals("optimistic") ? "OPTIMISTIC_LOCK" : "UNEXPECTED", SAFE_TRACE);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void businessContractPreservesUsefulMessageButLogsOnlyCodeAndCategory() throws Exception {
        MvcResult response = mvc.perform(get("/failure/business/request-uri-token")
                .header("X-Trace-Id", SAFE_TRACE)).andReturn();
        JsonNode body = mapper.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asInt()).isEqualTo(ResultCode.FORBIDDEN.getCode());
        assertThat(body.path("message").asText()).isEqualTo(BUSINESS_MESSAGE);
        assertThat(body.path("traceId").asText()).isEqualTo(SAFE_TRACE);
        assertSafeText(body.toString());
        assertSafeLog("BUSINESS", SAFE_TRACE);
        assertThat(events.list.get(0).getFormattedMessage()).contains("code=" + ResultCode.FORBIDDEN.getCode())
                .doesNotContain(BUSINESS_MESSAGE);
    }

    @Test
    void rejectedPasswordValueIsNotIncludedInValidationBodyOrLog() throws Exception {
        MvcResult response = mvc.perform(post("/validation").header("X-Trace-Id", SAFE_TRACE)
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"fixture-password\"}")).andReturn();
        JsonNode body = mapper.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asInt()).isEqualTo(ResultCode.PARAM_INVALID.getCode());
        assertThat(body.path("message").asText()).contains("password", "密码长度至少80");
        assertSafeText(body.toString());
        assertThat(events.list).isEmpty();
    }

    @Test
    void constraintRulesAreReadFromViolationsInsteadOfArbitraryWrapperMessage() {
        ConstraintViolationException exception = new ConstraintViolationException(TECHNICAL_PAYLOAD,
                validator.validate(new ValidationBody("fixture-password")));
        var response = new GlobalExceptionHandler().handleConstraintViolation(exception);
        assertThat(response.getCode()).isEqualTo(ResultCode.PARAM_INVALID.getCode());
        assertThat(response.getMessage()).contains("password", "密码长度至少80");
        assertSafeText(response.getMessage());
        assertThat(events.list).isEmpty();
    }

    @Test
    void maliciousTraceHeaderIsReplacedInResponseAndCapturedLogMdc() throws Exception {
        String supplied = "http://downstream.fixture?password=fixture-password";
        MvcResult response = mvc.perform(get("/failure/unexpected/request-uri-token")
                .header("X-Trace-Id", supplied)).andReturn();
        JsonNode body = mapper.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String trace = body.path("traceId").asText();
        assertThat(trace).matches("[a-f0-9]{32}").isEqualTo(response.getResponse().getHeader("X-Trace-Id"));
        assertSafeText(body.toString());
        assertSafeLog("UNEXPECTED", trace);
        assertThat(events.list.get(0).getMDCPropertyMap()).containsEntry("traceId", trace);
        assertThat(MDC.get("traceId")).isNull();
    }

    private void assertSafeLog(String category, String trace) {
        assertThat(events.list).hasSize(1);
        ILoggingEvent event = events.list.get(0);
        assertThat(event.getFormattedMessage()).contains("category=" + category, "traceId=" + trace);
        assertThat(event.getThrowableProxy()).isNull();
        assertSafeText(event.getFormattedMessage());
    }

    private static void assertSafeText(String value) {
        assertThat(value).doesNotContain("fixture-password", "fixture-access-token", "downstream.fixture",
                "request-uri-token", "INSERT INTO secret");
    }

    public record ValidationBody(@Size(min = 80, message = "密码长度至少80") String password) {
    }

    @RestController
    public static class FailureController {
        @GetMapping("/failure/{kind}/{secret}")
        public void fail(@PathVariable String kind) {
            RuntimeException cause = new IllegalArgumentException(TECHNICAL_PAYLOAD);
            switch (kind) {
                case "duplicate" -> throw new DuplicateKeyException(TECHNICAL_PAYLOAD, cause);
                case "optimistic" -> throw new OptimisticLockingFailureException(TECHNICAL_PAYLOAD, cause);
                case "business" -> {
                    ServiceException exception = new ServiceException(ResultCode.FORBIDDEN, BUSINESS_MESSAGE);
                    exception.initCause(cause);
                    throw exception;
                }
                default -> throw new IllegalStateException(TECHNICAL_PAYLOAD, cause);
            }
        }

        @PostMapping("/validation")
        public void validate(@Valid @RequestBody ValidationBody body) {
        }
    }
}
