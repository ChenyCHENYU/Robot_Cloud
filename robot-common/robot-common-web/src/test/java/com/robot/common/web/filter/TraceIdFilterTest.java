package com.robot.common.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robot.common.core.constant.RobotConstants;
import jakarta.servlet.ServletException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {
    private static final int MAX_TRACE_CHARACTERS = 64;
    private static final int OVER_TRACE_CHARACTER_LIMIT = 65;

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static Stream<String> legalTraceIds() {
        return Stream.of("x", "TRACE_2026-10", "Z".repeat(MAX_TRACE_CHARACTERS));
    }

    @ParameterizedTest
    @MethodSource("legalTraceIds")
    void legalTraceIdIsReturnedAndBoundOnlyDuringTheChain(String candidate) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fixture");
        request.addHeader(RobotConstants.HEADER_TRACE_ID, candidate);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("unrelated", "preserved");
        filter.doFilter(
                request,
                response,
                (incoming, outgoing) ->
                        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isEqualTo(candidate));
        assertThat(response.getHeader(RobotConstants.HEADER_TRACE_ID)).isEqualTo(candidate);
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get("unrelated")).isEqualTo("preserved");
    }

    private static Stream<String> illegalTraceIds() {
        return Stream.of(
                null,
                "",
                " ",
                "A".repeat(OVER_TRACE_CHARACTER_LIMIT),
                "trace\r\npassword=fixture",
                "http://downstream.fixture?token=fixture");
    }

    @ParameterizedTest
    @MethodSource("illegalTraceIds")
    void illegalAndAbsentInputIsReplacedBeforeMdcAndResponse(String candidate) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fixture");
        if (candidate != null) {
            request.addHeader(RobotConstants.HEADER_TRACE_ID, candidate);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (incoming, outgoing) -> {
                    assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).matches("[a-f0-9]{32}");
                    assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNotEqualTo(candidate);
                });
        assertThat(response.getHeader(RobotConstants.HEADER_TRACE_ID)).matches("[a-f0-9]{32}");
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }

    @Test
    void failingChainStillClearsTraceMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fixture");
        request.addHeader(RobotConstants.HEADER_TRACE_ID, "safe-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        request,
                                        response,
                                        (incoming, outgoing) -> {
                                            assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID))
                                                    .isEqualTo("safe-trace");
                                            throw new ServletException("fixture failure");
                                        }))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }
}
