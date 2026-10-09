package com.robot.system.account.controller;

import com.robot.common.web.handler.GlobalExceptionHandler;
import com.robot.system.account.service.AccountService;
import com.robot.system.api.dto.account.ProfileUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证真实 MVC 参数边界，避免个人账户写入绕过 Bean Validation。 */
class AccountValidationTest {
    private final AccountService service = mock(AccountService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AccountController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void blankOldPasswordOrShortNewPasswordNeverReachesBusinessService() throws Exception {
        for (String body : new String[]{
                "{\"oldPassword\":\"\",\"newPassword\":\"new-password\"}",
                "{\"oldPassword\":\"old-password\",\"newPassword\":\"123\"}"}) {
            mvc.perform(put("/account/password").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("code").value(400));
        }
        verifyNoInteractions(service);
    }

    @Test
    void invalidProfileNeverReachesBusinessService() throws Exception {
        mvc.perform(put("/account/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Alice\",\"email\":\"invalid-email\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void identityAndPrivilegeFieldsCannotBecomeSelfServiceWriteFields() throws Exception {
        mvc.perform(put("/account/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Alice\",\"email\":\"alice@example.com\","
                                + "\"userId\":\"victim\",\"companyId\":\"foreign\","
                                + "\"securityVersion\":0,\"roleIds\":[\"super\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(0));
        ArgumentCaptor<ProfileUpdateRequest> request = ArgumentCaptor.forClass(ProfileUpdateRequest.class);
        verify(service).updateProfile(request.capture());
        assertThat(request.getValue().getNickname()).isEqualTo("Alice");
        assertThat(request.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(request.getValue().getPhone()).isNull();
    }
}
