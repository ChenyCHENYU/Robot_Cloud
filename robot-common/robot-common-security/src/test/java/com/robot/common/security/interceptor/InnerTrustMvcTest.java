package com.robot.common.security.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.config.RobotSecurityProperties;
import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.filter.InnerTrustContextFilter;
import com.robot.common.security.handler.NotLoginExceptionHandler;
import com.robot.common.security.util.AuthHolder;
import com.robot.common.security.util.AuthUtil;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 真实 MVC 请求生命周期：拒绝、异常和异步均不能遗留线程权限。 */
class InnerTrustMvcTest {
    private static final int FORBIDDEN_CODE = 403;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        InnerTrustContext.clear();
        RobotSecurityProperties properties = new RobotSecurityProperties();
        properties.setInnerSecret("test-secret");
        mvc =
                MockMvcBuilders.standaloneSetup(new ProbeController())
                        .addMappedInterceptors(
                                new String[] {"/inner/**"}, new InnerCallInterceptor(properties))
                        .addFilters(new InnerTrustContextFilter())
                        .setControllerAdvice(new NotLoginExceptionHandler(), new ProbeAdvice())
                        .build();
    }

    @AfterEach
    void cleanup() {
        InnerTrustContext.clear();
    }

    @Test
    void verifiedServiceWithoutDelegateHasNoUserAndCannotPerformDelegatedOperation()
            throws Exception {
        mvc.perform(get("/inner/probe").header(RobotConstants.HEADER_INNER_CALL, "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("trusted").value(true))
                .andExpect(jsonPath("user").value(""));
        assertThat(InnerTrustContext.current()).isNull();
        mvc.perform(get("/inner/delegated").header(RobotConstants.HEADER_INNER_CALL, "test-secret"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("code").value(FORBIDDEN_CODE));
    }

    @Test
    void completeDelegateWorksAndFailureDoesNotLeakToNextRequest() throws Exception {
        mvc.perform(
                        get("/inner/probe")
                                .header(RobotConstants.HEADER_INNER_CALL, "test-secret")
                                .header(RobotConstants.HEADER_INNER_USER, "u1")
                                .header(RobotConstants.HEADER_INNER_COMPANY, "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("user").value("u1"))
                .andExpect(jsonPath("company").value("c1"));
        assertThat(InnerTrustContext.current()).isNull();
        mvc.perform(
                        get("/inner/failure")
                                .header(RobotConstants.HEADER_INNER_CALL, "test-secret")
                                .header(RobotConstants.HEADER_INNER_USER, "u1")
                                .header(RobotConstants.HEADER_INNER_COMPANY, "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("error").value(true));
        assertThat(InnerTrustContext.current()).isNull();
        mvc.perform(get("/inner/probe")).andExpect(status().isForbidden());
        assertThat(InnerTrustContext.current()).isNull();
    }

    @Test
    void partialOrRepeatedHeadersAndWrongSecretCannotLeaveTrustedService() throws Exception {
        for (String secret : new String[] {"test-secret", "wrong-secret"}) {
            InnerTrustContext.bindVerifiedService("stale", "stale", null);
            mvc.perform(
                            get("/inner/probe")
                                    .header(RobotConstants.HEADER_INNER_CALL, secret)
                                    .header(RobotConstants.HEADER_INNER_USER, "u1"))
                    .andExpect(status().isForbidden());
            assertThat(InnerTrustContext.current()).isNull();
        }
        mvc.perform(
                        get("/inner/probe")
                                .header(
                                        RobotConstants.HEADER_INNER_CALL,
                                        "test-secret",
                                        "test-secret"))
                .andExpect(status().isForbidden());
        assertThat(InnerTrustContext.current()).isNull();
    }

    @Test
    void loggedSessionRemainsAuthoritativeAndConflictingDelegateIsRejected() throws Exception {
        LoginUser user =
                LoginUser.builder()
                        .userId("u1")
                        .companyId("c1")
                        .username("alice")
                        .contextId("membership1")
                        .build();
        try (MockedStatic<AuthHolder> holder = mockStatic(AuthHolder.class)) {
            holder.when(AuthHolder::getLoginId).thenReturn("u1");
            holder.when(
                            () ->
                                    AuthHolder.getSessionAttr(
                                            AuthHolder.SESSION_LOGIN_USER, LoginUser.class))
                    .thenReturn(user);
            mvc.perform(
                            get("/inner/probe")
                                    .header(RobotConstants.HEADER_INNER_CALL, "test-secret")
                                    .header(RobotConstants.HEADER_INNER_USER, "u1")
                                    .header(RobotConstants.HEADER_INNER_COMPANY, "c1")
                                    .header(RobotConstants.HEADER_INNER_USERNAME, "alice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("context").value("membership1"));
            mvc.perform(
                            get("/inner/probe")
                                    .header(RobotConstants.HEADER_INNER_CALL, "test-secret")
                                    .header(RobotConstants.HEADER_INNER_USER, "u1")
                                    .header(RobotConstants.HEADER_INNER_COMPANY, "c2"))
                    .andExpect(status().isForbidden());
            assertThat(InnerTrustContext.current()).isNull();
            InnerTrustContext.bindVerifiedService("u2", "c2", "bob");
            assertThat(AuthUtil.getLoginUser()).isSameAs(user);
            holder.when(
                            () ->
                                    AuthHolder.getSessionAttr(
                                            AuthHolder.SESSION_LOGIN_USER, LoginUser.class))
                    .thenReturn(LoginUser.builder().userId("u2").companyId("c2").build());
            assertThat(AuthUtil.getLoginUser()).isNull();
            holder.when(
                            () ->
                                    AuthHolder.getSessionAttr(
                                            AuthHolder.SESSION_LOGIN_USER, LoginUser.class))
                    .thenReturn(null);
            assertThat(AuthUtil.getLoginUser()).isNull();
        }
    }

    @Test
    void ordinaryRequestClearsAnyStaleThreadContextBeforeItsHandler() throws Exception {
        InnerTrustContext.bindVerifiedService("stale", "stale", "stale");
        mvc.perform(get("/probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("trusted").value(false))
                .andExpect(jsonPath("user").value(""));
        assertThat(InnerTrustContext.current()).isNull();
    }

    @Test
    void asyncWorkerDoesNotInheritTrustAndRequestThreadIsCleanedBeforeCompletion()
            throws Exception {
        MvcResult pending =
                mvc.perform(
                                get("/inner/async")
                                        .header(RobotConstants.HEADER_INNER_CALL, "test-secret"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        assertThat(InnerTrustContext.current()).isNull();
        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
        assertThat(InnerTrustContext.current()).isNull();
    }

    @RestController
    static class ProbeController {
        @GetMapping({"/inner/probe", "/probe"})
        Map<String, Object> probe() {
            LoginUser user = AuthUtil.getLoginUser();
            return Map.of(
                    "trusted",
                    InnerTrustContext.current() != null,
                    "user",
                    user == null ? "" : user.getUserId(),
                    "company",
                    user == null ? "" : user.getCompanyId(),
                    "context",
                    user == null || user.getContextId() == null ? "" : user.getContextId());
        }

        @GetMapping("/inner/delegated")
        void delegated() {
            AuthUtil.requireTrustedUser("u1", "c1");
        }

        @GetMapping("/inner/failure")
        void failure() {
            throw new IllegalStateException("controller failure");
        }

        @GetMapping("/inner/async")
        Callable<Boolean> async() {
            return () -> InnerTrustContext.current() == null;
        }
    }

    @RestControllerAdvice
    static class ProbeAdvice {
        @ExceptionHandler(IllegalStateException.class)
        Map<String, Boolean> exception() {
            return Map.of("error", true);
        }
    }
}
