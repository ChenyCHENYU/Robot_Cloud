package com.robot.common.security.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.filter.InnerTrustContextFilter;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InnerTrustNestedErrorTest {
    @Test
    void nestedContainerErrorDispatchClearsTrustBeforeTheOuterFilterFinally() throws Exception {
        InnerTrustContextFilter filter = new InnerTrustContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        try {
            filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (req, res) -> {
                        InnerTrustContext.bindVerifiedService("u1", "c1", null);
                        request.setDispatcherType(DispatcherType.ERROR);
                        filter.doFilter(
                                req,
                                res,
                                (errorRequest, errorResponse) ->
                                        assertThat(InnerTrustContext.current()).isNull());
                        assertThat(InnerTrustContext.current()).isNull();
                    });
            assertThat(InnerTrustContext.current()).isNull();
        } finally {
            InnerTrustContext.clear();
        }
    }
}
