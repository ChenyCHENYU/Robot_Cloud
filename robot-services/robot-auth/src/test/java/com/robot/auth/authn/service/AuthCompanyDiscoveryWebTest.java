package com.robot.auth.authn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.controller.AuthController;
import com.robot.auth.authn.dto.LoginCompaniesRequest;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.web.handler.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual MVC, BCrypt, admission and durable audit boundaries for credentialed discovery. */
@SuppressWarnings("deprecation") // Deliberate Boot4 Jackson2 wire compatibility bridge.
class AuthCompanyDiscoveryWebTest {
    private static final int TEST_BCRYPT_COST = 4;
    private static final int INVALID_CREDENTIAL_CODE = 40101;
    private static final int DISABLED_ACCOUNT_CODE = 40102;
    private static final int INVALID_INPUT_CODE = 400;
    private static final int DEPENDENCY_FAILURE_CODE = 500;
    private static final int OVERLONG_PASSWORD_LENGTH = 1025;
    private static final int CHINESE_PASSWORD_72_BYTE_CHARACTERS = 24;
    private static final String PASSWORD = "synthetic-valid?";
    private static final String PATH = "/auth/login-companies";

    private final ObjectMapper mapper = new ObjectMapper();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(TEST_BCRYPT_COST);
    private final RemoteSystemService remote = mock(RemoteSystemService.class);
    private final AuthSessionManager sessions = mock(AuthSessionManager.class);
    private final AuthStateTestSupport.MemoryRedis redis = new AuthStateTestSupport.MemoryRedis();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var queue = new AuthAuditQueue(redis, remote, mapper);
        var credentials = new AuthCredentials(remote, encoder, new AuthAttemptGate(redis), queue);
        var service = new AuthService(remote, redis, sessions, mapper, credentials);
        mvc =
                MockMvcBuilders.standaloneSetup(new AuthController(service))
                        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @ParameterizedTest
    @CsvSource({"-1", "0", "1"})
    void unprovedCredentialsReturnTheSameFailureAndNeverRevealContexts(int accountStatus)
            throws Exception {
        RemoteUserDTO user = accountStatus < 0 ? null : account(accountStatus);
        when(remote.getUserByUsername("alice")).thenReturn(Result.success(user));
        discover("synthetic-wrong-password", INVALID_CREDENTIAL_CODE);
        verify(remote, never()).getUserContexts(anyString());
        assertFailureAudit(user == null ? null : user.getId());
        assertNoSessionMaterial();
    }

    @Test
    void escapedUnpairedSurrogateCannotUseEncoderReplacementToDiscoverCompanies() throws Exception {
        when(remote.getUserByUsername("alice")).thenReturn(Result.success(account(1)));
        mvc.perform(
                        post(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"alice\",\"password\":\"synthetic-valid\\ud800\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(INVALID_CREDENTIAL_CODE))
                .andExpect(jsonPath("message").value("账号或密码错误"))
                .andExpect(jsonPath("data").doesNotExist());
        verify(remote, never()).getUserContexts(anyString());
        assertFailureAudit("usr-1");
        assertNoSessionMaterial();
    }

    @Test
    void discoveryAcceptsExactly72Utf8BytesAndRejectsAnOtherwiseMatching73ByteSuffix()
            throws Exception {
        String valid = "密".repeat(CHINESE_PASSWORD_72_BYTE_CHARACTERS);
        var user = account(1);
        user.setPasswordHash(encoder.encode(valid));
        when(remote.getUserByUsername("alice")).thenReturn(Result.success(user));
        when(remote.getUserContexts("usr-1")).thenReturn(Result.success(List.of()));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(command(valid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0))
                .andExpect(jsonPath("data.companies").isEmpty());
        discover(valid + "x", INVALID_CREDENTIAL_CODE);
        verify(remote).getUserContexts("usr-1");
        assertFailureAudit("usr-1");
        assertThat(redis.events.values().iterator().next()).doesNotContain(valid);
        assertNoSessionMaterial();
    }

    @Test
    void correctCredentialsExposeOnlyEffectiveCompaniesWithoutIssuingATicketOrSession()
            throws Exception {
        when(remote.getUserByUsername("alice")).thenReturn(Result.success(account(1)));
        when(remote.getUserContexts("usr-1"))
                .thenReturn(
                        Result.success(
                                List.of(
                                        RemoteUserContextDTO.builder()
                                                .contextId("member-1")
                                                .primaryFlag(true)
                                                .tenantName("租户")
                                                .companyName("公司")
                                                .build())));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(command(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0))
                .andExpect(jsonPath("data.companies[0].id").value("member-1"))
                .andExpect(jsonPath("data.companies[0].isPrimary").value(true))
                .andExpect(jsonPath("data.companies[0].tenantName").value("租户"))
                .andExpect(jsonPath("data.companies[0].companyName").value("公司"))
                .andExpect(jsonPath("data.token").doesNotExist())
                .andExpect(jsonPath("data.loginTicket").doesNotExist());
        verify(remote).getUserContexts("usr-1");
        assertThat(redis.events).isEmpty();
        assertNoSessionMaterial();
    }

    @Test
    void disabledStatusIsShownOnlyAfterCorrectCredentialsAndIsReliablyAudited() throws Exception {
        when(remote.getUserByUsername("alice")).thenReturn(Result.success(account(0)));
        discover(PASSWORD, DISABLED_ACCOUNT_CODE);
        verify(remote, never()).getUserContexts(anyString());
        assertFailureAudit("usr-1");
        assertNoSessionMaterial();
    }

    @Test
    void accountSupplyFailureRemainsADependencyFailureWithoutInventingAnInvalidPasswordAudit()
            throws Exception {
        when(remote.getUserByUsername("alice")).thenReturn(Result.failed(ResultCode.FAILED));
        discover(PASSWORD, DEPENDENCY_FAILURE_CODE);
        verify(remote, never()).getUserContexts(anyString());
        assertThat(redis.events).isEmpty();
        assertNoSessionMaterial();
    }

    @Test
    void contextSupplyFailureCannotMasqueradeAsAnEmptyCompanyList() throws Exception {
        when(remote.getUserByUsername("alice")).thenReturn(Result.success(account(1)));
        when(remote.getUserContexts("usr-1")).thenReturn(Result.failed(ResultCode.FAILED));
        discover(PASSWORD, DEPENDENCY_FAILURE_CODE);
        assertThat(redis.events).isEmpty();
        assertNoSessionMaterial();
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void missingNullBlankAndOversizedPasswordNeverReachTheCredentialBoundary(String command)
            throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(command))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(INVALID_INPUT_CODE))
                .andExpect(jsonPath("data").doesNotExist());
        verifyNoInteractions(remote, sessions);
        assertThat(redis.entries).isEmpty();
        assertThat(redis.events).isEmpty();
    }

    @Test
    void discoveryCommandNeverPrintsItsPassword() {
        var request = new LoginCompaniesRequest("alice", PASSWORD);
        assertThat(request.toString()).contains("alice").doesNotContain(PASSWORD);
    }

    private static Stream<String> invalidCommands() {
        return Stream.of(
                "{\"username\":\"alice\"}",
                "{\"username\":\"alice\",\"password\":null}",
                "{\"username\":\"alice\",\"password\":\" \"}",
                "{\"username\":\"alice\",\"password\":\""
                        + "p".repeat(OVERLONG_PASSWORD_LENGTH)
                        + "\"}");
    }

    private RemoteUserDTO account(int status) {
        return RemoteUserDTO.builder()
                .id("usr-1")
                .username("alice")
                .statusFlag(status)
                .passwordHash(encoder.encode(PASSWORD))
                .securityVersion(0L)
                .build();
    }

    private String command(String password) throws Exception {
        return mapper.writeValueAsString(Map.of("username", "alice", "password", password));
    }

    private void discover(String password, int code) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(command(password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(code))
                .andExpect(jsonPath("data").doesNotExist());
    }

    private void assertFailureAudit(String expectedUserId) throws Exception {
        assertThat(redis.events).hasSize(1);
        String payload = redis.events.values().iterator().next();
        var event = mapper.readValue(payload, RemoteLoginLogDTO.class);
        assertThat(event.getUserId()).isEqualTo(expectedUserId);
        assertThat(event.getCompanyId()).isNull();
        assertThat(event.getStatus()).isZero();
        assertThat(event.getUsername()).startsWith("attempt-");
        assertThat(payload).doesNotContain("alice", PASSWORD, "synthetic-wrong-password");
    }

    private void assertNoSessionMaterial() {
        verifyNoInteractions(sessions);
        assertThat(redis.entries.keySet()).allMatch(key -> key.contains("attempt:"));
    }
}
