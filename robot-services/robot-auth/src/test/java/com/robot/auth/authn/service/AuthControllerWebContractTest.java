package com.robot.auth.authn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteDataScopeDTO;
import com.robot.api.system.dto.RemoteMenuRouteDTO;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.controller.AuthController;
import com.robot.auth.authn.dto.ActivateContextRequest;
import com.robot.auth.authn.dto.LoginCompaniesResponse;
import com.robot.auth.authn.dto.LoginRequest;
import com.robot.auth.authn.dto.LoginResponse;
import com.robot.auth.authn.dto.RefreshTokenRequest;
import com.robot.auth.authn.dto.RefreshTokenResponse;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.web.handler.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Real MVC mappings, validation and Jackson2 wire behavior; security interceptors have separate
 * integration suites.
 */
@SuppressWarnings("deprecation") // Deliberate Boot4 Jackson2 wire compatibility bridge.
class AuthControllerWebContractTest {
    private static final int SECRET_HEX_LENGTH = 64;
    private static final int ACCESS_TTL_SECONDS = 1800;
    private static final int VALIDATION_CODE = 400;
    private static final int OVERLONG_USERNAME_LENGTH = 31;
    private static final int OVERLONG_LOGIN_PASSWORD_LENGTH = 1025;
    private static final int SHORT_SECRET_LENGTH = 63;
    private static final int OVERLONG_SECRET_LENGTH = 65;
    private static final int CHINESE_PASSWORD_72_BYTE_CHARACTERS = 24;
    private static final int TEST_BCRYPT_COST = 4;
    private static final int INVALID_CREDENTIAL_CODE = 40101;
    private static final int THROTTLED_CODE = 429;

    private final AuthService service = mock(AuthService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = mvc(service);
    }

    private MockMvc mvc(AuthService target) {
        return MockMvcBuilders.standaloneSetup(new AuthController(target))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void pendingLoginPreservesTicketAndEmptyTokensInTheFrontendEnvelope() throws Exception {
        when(service.login(any()))
                .thenReturn(
                        LoginResponse.builder()
                                .token("")
                                .refreshToken("")
                                .loginTicket("a".repeat(SECRET_HEX_LENGTH))
                                .availableContexts(List.of())
                                .build());
        mvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"alice\",\"password\":\"秘密密码ab\",\"contextId\":\"member-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0))
                .andExpect(jsonPath("message").value("成功"))
                .andExpect(jsonPath("msg").value("成功"))
                .andExpect(jsonPath("data.token").value(""))
                .andExpect(jsonPath("data.refreshToken").value(""))
                .andExpect(jsonPath("data.expiresIn").value(0))
                .andExpect(jsonPath("data.loginTicket").value("a".repeat(SECRET_HEX_LENGTH)))
                .andExpect(jsonPath("data.availableContexts").isArray());
        var captured = ArgumentCaptor.forClass(LoginRequest.class);
        verify(service).login(captured.capture());
        assertThat(captured.getValue().getUsername()).isEqualTo("alice");
        assertThat(captured.getValue().getPassword()).isEqualTo("秘密密码ab");
        assertThat(captured.getValue().getContextId()).isEqualTo("member-1");
    }

    @Test
    void companyActivationSwitchRefreshAndLogoutMappingsReturnTheirContractEnvelopes()
            throws Exception {
        when(service.getLoginCompanies(any()))
                .thenReturn(
                        new LoginCompaniesResponse(
                                List.of(
                                        new LoginCompaniesResponse.LoginCompany(
                                                "member-1", true, "租户", "公司"))));
        when(service.activateContext(any()))
                .thenReturn(
                        LoginResponse.builder()
                                .token("access")
                                .refreshToken("refresh")
                                .expiresIn(ACCESS_TTL_SECONDS)
                                .user(
                                        LoginResponse.UserVO.builder()
                                                .id("usr-1")
                                                .username("alice")
                                                .build())
                                .build());
        when(service.switchContext(any()))
                .thenReturn(LoginResponse.builder().token("new-access").build());
        when(service.refreshToken("b".repeat(SECRET_HEX_LENGTH)))
                .thenReturn(
                        new RefreshTokenResponse(
                                "rotated-access",
                                "c".repeat(SECRET_HEX_LENGTH),
                                ACCESS_TTL_SECONDS));
        mvc.perform(
                        post("/auth/login-companies")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"alice\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0))
                .andExpect(jsonPath("data.companies[0].id").value("member-1"))
                .andExpect(jsonPath("data.companies[0].isPrimary").value(true));
        mvc.perform(
                        post("/auth/context/activate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                new ActivateContextRequest(
                                                        "a".repeat(SECRET_HEX_LENGTH),
                                                        "member-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0))
                .andExpect(jsonPath("data.token").value("access"))
                .andExpect(jsonPath("data.user.id").value("usr-1"));
        mvc.perform(
                        post("/auth/context/switch")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contextId\":\"member-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.token").value("new-access"));
        mvc.perform(
                        post("/auth/refresh-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                new RefreshTokenRequest(
                                                        "b".repeat(SECRET_HEX_LENGTH)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.token").value("rotated-access"))
                .andExpect(jsonPath("data.refreshToken").value("c".repeat(SECRET_HEX_LENGTH)))
                .andExpect(jsonPath("data.expiresIn").value(ACCESS_TTL_SECONDS));
        mvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0))
                .andExpect(jsonPath("data").doesNotExist());
        verify(service).getLoginCompanies(any());
        verify(service).activateContext(any());
        verify(service).switchContext(any());
        verify(service).refreshToken("b".repeat(SECRET_HEX_LENGTH));
        verify(service).logout();
    }

    @Test
    void currentMenuButtonsAndDataPermissionGetMappingsPreserveFrontendShapes() throws Exception {
        when(service.getCurrentMenus())
                .thenReturn(
                        List.of(RemoteMenuRouteDTO.builder().path("/home").name("Home").build()));
        when(service.getCurrentButtons()).thenReturn(Map.of("users", List.of("save", "delete")));
        when(service.getCurrentDataScopes())
                .thenReturn(List.of(new RemoteDataScopeDTO("users", "SELF")));
        mvc.perform(get("/auth/menu-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data[0].path").value("/home"));
        mvc.perform(get("/auth/button-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.users[0]").value("save"));
        mvc.perform(get("/auth/data-permission"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data[0].scope").value("SELF"));
        verify(service).getCurrentMenus();
        verify(service).getCurrentButtons();
        verify(service).getCurrentDataScopes();
    }

    @Test
    void invalidNullBlankAndOversizedCredentialsNeverReachServices() throws Exception {
        for (String path :
                List.of(
                        "/auth/login",
                        "/auth/login-companies",
                        "/auth/context/activate",
                        "/auth/context/switch",
                        "/auth/refresh-token")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("code").value(VALIDATION_CODE));
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("null"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("code").value(VALIDATION_CODE));
        }
        for (String invalid :
                List.of(
                        "{\"username\":\"alice\",\"password\":null}",
                        "{\"username\":\" \",\"password\":\"valid-password\"}",
                        mapper.writeValueAsString(
                                Map.of(
                                        "username",
                                        "a".repeat(OVERLONG_USERNAME_LENGTH),
                                        "password",
                                        "valid-password")),
                        mapper.writeValueAsString(
                                Map.of(
                                        "username",
                                        "alice",
                                        "password",
                                        "p".repeat(OVERLONG_LOGIN_PASSWORD_LENGTH))))) {
            mvc.perform(
                            post("/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(invalid))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("code").value(VALIDATION_CODE));
        }
        for (String invalid :
                List.of(
                        "{\"refreshToken\":null}",
                        "{\"refreshToken\":\" \"}",
                        mapper.writeValueAsString(
                                Map.of("refreshToken", "a".repeat(SHORT_SECRET_LENGTH))),
                        mapper.writeValueAsString(
                                Map.of("refreshToken", "a".repeat(OVERLONG_SECRET_LENGTH))))) {
            mvc.perform(
                            post("/auth/refresh-token")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(invalid))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("code").value(VALIDATION_CODE));
        }
        mvc.perform(
                        post("/auth/context/switch")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "contextId",
                                                        "m".repeat(OVERLONG_SECRET_LENGTH)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(VALIDATION_CODE));
        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonOrWrongVerbUsesTheFixedBusinessFailureEnvelope() throws Exception {
        mvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"malformed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(VALIDATION_CODE))
                .andExpect(jsonPath("message").value("请求体解析失败"));
        mvc.perform(get("/auth/login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(VALIDATION_CODE));
        verifyNoInteractions(service);
    }

    @Test
    void realBcryptLoginAcceptsExactly72Utf8BytesAndNeverTruncatesA73ByteSuffix() throws Exception {
        String valid = "密".repeat(CHINESE_PASSWORD_72_BYTE_CHARACTERS); // 72 UTF-8 bytes, 24 Java
        // characters.
        var encoder = new BCryptPasswordEncoder(TEST_BCRYPT_COST);
        var remote = mock(RemoteSystemService.class);
        var sessions = mock(AuthSessionManager.class);
        var queue = mock(AuthAuditQueue.class);
        var user =
                RemoteUserDTO.builder()
                        .id("usr-1")
                        .username("alice")
                        .statusFlag(1)
                        .passwordHash(encoder.encode(valid))
                        .securityVersion(0L)
                        .build();
        var context =
                RemoteUserContextDTO.builder()
                        .contextId("member-1")
                        .companyId("company-1")
                        .securityVersion(0L)
                        .build();
        when(remote.getUserByUsername("alice")).thenReturn(Result.success(user));
        when(remote.getUserContexts("usr-1")).thenReturn(Result.success(List.of(context)));
        when(sessions.issue(eq("login"), eq(user), eq(context), anyList()))
                .thenReturn(LoginResponse.builder().token("issued-once").build());
        var actual =
                new AuthService(
                        remote,
                        new AuthStateTestSupport.MemoryRedis(),
                        sessions,
                        mapper,
                        new AuthCredentials(remote, encoder, mock(AuthAttemptGate.class), queue));
        var actualMvc = mvc(actual);
        actualMvc
                .perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("username", "alice", "password", valid))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0))
                .andExpect(jsonPath("data.token").value("issued-once"));
        actualMvc
                .perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "username",
                                                        "alice",
                                                        "password",
                                                        valid + "x"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(INVALID_CREDENTIAL_CODE))
                .andExpect(jsonPath("message").value("账号或密码错误"))
                .andExpect(jsonPath("data").doesNotExist());
        verify(sessions, times(1)).issue(anyString(), any(), any(), anyList());
        verify(remote, times(1)).getUserContexts("usr-1");
        verify(queue).append(any());
    }

    @Test
    void serviceCredentialRejectionAndLimiterCodeSurviveHttp200Wrapping() throws Exception {
        when(service.login(any())).thenThrow(new ServiceException(ResultCode.AUTH_LOGIN_FAILED));
        when(service.refreshToken(anyString()))
                .thenThrow(new ServiceException(ResultCode.TOO_MANY_REQUESTS));
        mvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"alice\",\"password\":\"bad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(INVALID_CREDENTIAL_CODE))
                .andExpect(jsonPath("msg").value("账号或密码错误"));
        mvc.perform(
                        post("/auth/refresh-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                new RefreshTokenRequest(
                                                        "a".repeat(SECRET_HEX_LENGTH)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(THROTTLED_CODE))
                .andExpect(jsonPath("data").doesNotExist());
    }

    @Test
    void escapedUnpairedSurrogateCannotAuthenticateAgainstALegitimateQuestionMarkPassword()
            throws Exception {
        var encoder = new BCryptPasswordEncoder(TEST_BCRYPT_COST);
        var remote = mock(RemoteSystemService.class);
        var sessions = mock(AuthSessionManager.class);
        var queue = mock(AuthAuditQueue.class);
        var user =
                RemoteUserDTO.builder()
                        .id("usr-1")
                        .username("alice")
                        .statusFlag(1)
                        .passwordHash(encoder.encode("synthetic-valid?"))
                        .securityVersion(0L)
                        .build();
        when(remote.getUserByUsername("alice")).thenReturn(Result.success(user));
        var actual =
                new AuthService(
                        remote,
                        new AuthStateTestSupport.MemoryRedis(),
                        sessions,
                        mapper,
                        new AuthCredentials(remote, encoder, mock(AuthAttemptGate.class), queue));
        // The HTTP body contains the literal JSON escape, not a pre-replaced byte sequence.
        mvc(actual)
                .perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"alice\",\"password\":\"synthetic-valid\\ud800\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(INVALID_CREDENTIAL_CODE))
                .andExpect(jsonPath("message").value("账号或密码错误"));
        verifyNoInteractions(sessions);
        verify(remote, never()).getUserContexts(anyString());
        verify(queue).append(any());
    }
}
