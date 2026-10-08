package com.robot.common.security.interceptor;

import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.config.RobotSecurityProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部调用鉴权拦截器测试：密钥匹配放行；缺失/错误 403。
 */
class InnerCallInterceptorTest {

    private InnerCallInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        RobotSecurityProperties properties = new RobotSecurityProperties();
        properties.setInnerSecret("test-secret");
        interceptor = new InnerCallInterceptor(properties);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("正确密钥放行")
    void correctSecretShouldPass() throws Exception {
        request.addHeader(RobotConstants.HEADER_INNER_CALL, "test-secret");
        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    @DisplayName("缺失密钥 403")
    void missingSecretShouldBeRejected() throws Exception {
        assertFalse(interceptor.preHandle(request, response, new Object()));
        org.junit.jupiter.api.Assertions.assertEquals(
                HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @Test
    @DisplayName("错误密钥 403（含响应体提示）")
    void wrongSecretShouldBeRejected() throws Exception {
        request.addHeader(RobotConstants.HEADER_INNER_CALL, "wrong");
        assertFalse(interceptor.preHandle(request, response, new Object()));
        org.junit.jupiter.api.Assertions.assertEquals(
                HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        assertTrue(response.getContentAsString().contains("内部接口"));
    }
}
