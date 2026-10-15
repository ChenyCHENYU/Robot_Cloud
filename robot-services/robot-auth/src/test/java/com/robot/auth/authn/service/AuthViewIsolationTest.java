package com.robot.auth.authn.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.auth.authn.dto.AuthContextVO;
import com.robot.auth.authn.dto.LoginCompaniesResponse;
import com.robot.auth.authn.dto.LoginResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves detached inputs and outputs, while preserving ordinary Jackson2 bean hydration. */
class AuthViewIsolationTest {
    @Test
    void roleInputsBuildersAndReturnedNestedModelsCannotMutateAnIssuedView() throws Exception {
        var role = AuthContextVO.RoleVO.builder().id("role-1").name("Role").build();
        var roles = new ArrayList<>(List.of(role));
        var context =
                AuthContextVO.builder().id("member").companyId("company").roles(roles).build();
        var account = LoginResponse.UserVO.builder().id("user").username("alice").build();
        var available = new ArrayList<>(List.of(context));
        var response =
                LoginResponse.builder()
                        .activeContext(context)
                        .availableContexts(available)
                        .user(account)
                        .build();
        role.setName("injected-role");
        roles.clear();
        available.clear();
        account.setUsername("injected-user");
        context.setCompanyId("foreign");
        assertEquals("company", response.getActiveContext().getCompanyId());
        assertEquals("Role", response.getActiveContext().getRoles().get(0).getName());
        assertEquals("alice", response.getUser().getUsername());
        response.getActiveContext().setCompanyId("foreign");
        response.getAvailableContexts().get(0).getRoles().get(0).setName("injected-role");
        response.getUser().setUsername("injected-user");
        assertEquals("company", response.getActiveContext().getCompanyId());
        assertEquals("Role", response.getAvailableContexts().get(0).getRoles().get(0).getName());
        assertEquals("alice", response.getUser().getUsername());
        var mapper = new ObjectMapper();
        var hydrated = mapper.readValue(mapper.writeValueAsString(response), LoginResponse.class);
        assertEquals("Role", hydrated.getActiveContext().getRoles().get(0).getName());
        assertEquals("alice", hydrated.getUser().getUsername());
    }

    @Test
    void settersAlsoDetachTheirValuesAndNullStillRepresentsUnavailableViews() {
        var role = AuthContextVO.RoleVO.builder().id("role").name("Role").build();
        var context = new AuthContextVO();
        context.setId("member");
        context.setRoles(List.of(role));
        role.setName("injected");
        assertEquals("Role", context.getRoles().get(0).getName());
        var response = new LoginResponse();
        response.setActiveContext(context);
        response.setAvailableContexts(List.of(context));
        var user = LoginResponse.UserVO.builder().id("user").username("alice").build();
        response.setUser(user);
        context.setId("foreign");
        user.setUsername("foreign");
        assertEquals("member", response.getActiveContext().getId());
        assertEquals("alice", response.getUser().getUsername());
        response.setAvailableContexts(null);
        response.setActiveContext(null);
        response.setUser(null);
        context.setRoles(null);
        assertNull(response.getAvailableContexts());
        assertNull(response.getActiveContext());
        assertNull(response.getUser());
        assertNull(context.getRoles());
    }

    @Test
    void companyDiscoverySnapshotsTheProvidedCollection() {
        var companies =
                new ArrayList<>(
                        List.of(
                                new LoginCompaniesResponse.LoginCompany(
                                        "member", true, "Tenant", "Company")));
        var response = new LoginCompaniesResponse(companies);
        companies.clear();
        assertEquals(1, response.companies().size());
        assertThrows(UnsupportedOperationException.class, () -> response.companies().clear());
    }
}
