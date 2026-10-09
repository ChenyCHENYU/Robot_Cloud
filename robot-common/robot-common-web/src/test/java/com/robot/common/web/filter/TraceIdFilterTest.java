package com.robot.common.web.filter;

import com.robot.common.core.constant.RobotConstants;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceIdFilterTest {
    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void legalTraceIdIsReturnedAndBoundOnlyDuringTheChain() throws Exception {
        for (String candidate : new String[]{"x", "TRACE_2026-10", "Z".repeat(64)}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fixture");
            request.addHeader(RobotConstants.HEADER_TRACE_ID, candidate);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MDC.put("unrelated", "preserved");
            filter.doFilter(request, response, (incoming, outgoing) ->
                    assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isEqualTo(candidate));
            assertThat(response.getHeader(RobotConstants.HEADER_TRACE_ID)).isEqualTo(candidate);
            assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
            assertThat(MDC.get("unrelated")).isEqualTo("preserved");
        }
    }

    @Test
    void illegalAndAbsentInputIsReplacedBeforeMdcAndResponse() throws Exception {
        for (String candidate : new String[]{null, "", " ", "A".repeat(65),
                "trace\r\npassword=fixture", "http://downstream.fixture?token=fixture"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fixture");
            if (candidate != null) {
                request.addHeader(RobotConstants.HEADER_TRACE_ID, candidate);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (incoming, outgoing) -> {
                assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).matches("[a-f0-9]{32}");
                assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNotEqualTo(candidate);
            });
            assertThat(response.getHeader(RobotConstants.HEADER_TRACE_ID)).matches("[a-f0-9]{32}");
            assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
        }
    }

    @Test
    void failingChainStillClearsTraceMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fixture");
        request.addHeader(RobotConstants.HEADER_TRACE_ID, "safe-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (incoming, outgoing) -> {
            assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isEqualTo("safe-trace");
            throw new ServletException("fixture failure");
        })).isInstanceOf(ServletException.class);
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }
}
