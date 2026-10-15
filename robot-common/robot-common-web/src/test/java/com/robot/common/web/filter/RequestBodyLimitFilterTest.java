package com.robot.common.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestBodyLimitFilterTest {
    private static final int BELOW_AUTH_BYTE_LIMIT = 65535;
    private static final int AUTH_BYTE_LIMIT = 65536;
    private static final int OVER_AUTH_BYTE_LIMIT = 65537;
    private static final int BODY_TOO_LARGE_STATUS = 413;
    private static final int HTTP_OK = 200;
    private static final int FAILURES_BEYOND_CAPACITY = 20;

    @ParameterizedTest
    @ValueSource(ints = {BELOW_AUTH_BYTE_LIMIT, AUTH_BYTE_LIMIT, OVER_AUTH_BYTE_LIMIT})
    void jsonActualSizeRejectsUnknownContentLengthBeforeHandler(int size) throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/auth/login") {
                    @Override
                    public int getContentLength() {
                        return -1;
                    }

                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
        request.setContentType("application/json");
        request.setContent(new byte[size]);
        var response = new MockHttpServletResponse();
        var called = new AtomicInteger();
        new RequestBodyLimitFilter()
                .doFilter(
                        request,
                        response,
                        (actual, ignored) -> {
                            called.incrementAndGet();
                            assertEquals(size, actual.getInputStream().readAllBytes().length);
                        });
        assertEquals(size > AUTH_BYTE_LIMIT ? 0 : 1, called.get());
        assertEquals(
                size > AUTH_BYTE_LIMIT ? BODY_TOO_LARGE_STATUS : HTTP_OK, response.getStatus());
    }

    // Fresh response and failure fixtures model separate requests; sharing them would mask
    // lifecycle bugs.
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    @Test
    void handlerFailureRestoresCapacityAndNonJsonBusinessRequestsRemainUntouched()
            throws Exception {
        var filter = new RequestBodyLimitFilter();
        var request = new MockHttpServletRequest("POST", "/auth/login");
        request.setContentType("application/json");
        request.setContent("{\"name\":\"用户\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (int i = 0; i < FAILURES_BEYOND_CAPACITY; i++) {
            assertThrows(
                    jakarta.servlet.ServletException.class,
                    () ->
                            filter.doFilter(
                                    request,
                                    new MockHttpServletResponse(),
                                    (actual, response) -> {
                                        throw new jakarta.servlet.ServletException("fixture");
                                    }));
        }
        var next = new MockHttpServletRequest("POST", "/auth/login");
        next.setContentType("application/json");
        next.setContent("用户".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        filter.doFilter(
                next,
                new MockHttpServletResponse(),
                (actual, response) -> assertEquals("用户", actual.getReader().readLine()));
        var form = new MockHttpServletRequest("POST", "/sys/menu");
        form.setContentType("application/x-www-form-urlencoded");
        filter.doFilter(
                form,
                new MockHttpServletResponse(),
                (actual, response) -> assertSame(form, actual));
    }
}
