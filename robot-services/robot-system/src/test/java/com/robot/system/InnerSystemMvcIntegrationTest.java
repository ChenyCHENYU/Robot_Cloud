package com.robot.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.mybatis.tenant.TenantQueryScope;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.exception.InnerAccessDeniedException;
import com.robot.common.security.util.AuthHolder;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.menu.service.MenuQueryService;
import com.robot.system.permission.service.DataScopeService;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.user.service.context.UserContextAssembler;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Boot 实际安全装配 + MVC + H2 真实租户插件，无外部基础设施。 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = RobotSystemApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:robot_inner_mvc;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "robot.security.inner-secret=test-secret",
            "spring.cloud.sentinel.enabled=false"
        })
@AutoConfigureMockMvc
@Sql("/inner-trust-schema.sql")
class InnerSystemMvcIntegrationTest {
    private static final int CREDENTIAL_FINGERPRINT_LENGTH = 64;

    @MockitoBean RedissonClient redissonClient;
    @MockitoBean RedisHelper redisHelper;
    @MockitoBean MenuQueryService menuQueryService;
    @MockitoBean DataScopeService dataScopeService;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SysRoleMapper roleMapper;
    @Autowired UserContextAssembler assembler;

    @Test
    void loginBeforeDelegationReadsOnlyActiveActualMembershipsAndTheirOwnBoundRoles()
            throws Exception {
        String body =
                mvc.perform(trusted(get("/inner/user/u1/contexts")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("code").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode contexts = objectMapper.readTree(body).path("data");
        assertThat(contexts).hasSize(2);
        assertThat(contexts.findValuesAsText("companyId")).containsExactlyInAnyOrder("c1", "c2");
        assertThat(contexts.findValuesAsText("code")).containsExactlyInAnyOrder("role1", "role2");
        assertThat(InnerTrustContext.current()).isNull();
        // 无当前公司时普通 mapper 仍 company_id = NULL 查空；请求内豁免没有遗留。
        assertThat(roleMapper.selectList(null)).isEmpty();
        assertThatThrownBy(
                        () -> TenantQueryScope.withoutTenantLine(() -> roleMapper.selectList(null)))
                .isInstanceOf(InnerAccessDeniedException.class);
        verifyNoInteractions(redisHelper, redissonClient);
    }

    @Test
    void missingOrIncorrectCredentialsBlockBeforeDbAndPublicSysRequiresLogin() throws Exception {
        mvc.perform(get("/inner/user/u1/contexts")).andExpect(status().isForbidden());
        mvc.perform(
                        get("/inner/user/u1/contexts")
                                .header(RobotConstants.HEADER_INNER_CALL, "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/sys/users")).andExpect(status().isUnauthorized());
        assertThat(InnerTrustContext.current()).isNull();
        verifyNoInteractions(redisHelper, redissonClient);
    }

    @Test
    void menuRequiresDelegateParameterMatchAndCurrentlyActiveMembership() throws Exception {
        when(menuQueryService.routesOfUser("u1", "c1")).thenReturn(List.of());
        mvc.perform(trusted(get("/inner/menu/c1/routes").param("userId", "u1")))
                .andExpect(status().isForbidden());
        mvc.perform(delegated(get("/inner/menu/c2/routes").param("userId", "u1")))
                .andExpect(status().isForbidden());
        mvc.perform(delegated(get("/inner/menu/c1/routes").param("userId", "u2")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(menuQueryService);
        mvc.perform(delegated(get("/inner/menu/c1/routes").param("userId", "u1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(0));
        verify(menuQueryService).routesOfUser("u1", "c1");
        jdbc.update("UPDATE sys_user_company SET status_flag=0 WHERE id='m1'");
        mvc.perform(delegated(get("/inner/menu/c1/routes").param("userId", "u1")))
                .andExpect(status().isForbidden());
        verifyNoMoreInteractions(menuQueryService);
        assertThat(InnerTrustContext.current()).isNull();
    }

    @Test
    void permissionAndLogPayloadCannotSelectAnotherDelegateIdentity() throws Exception {
        mvc.perform(delegated(get("/inner/permission/c2/data-scope").param("userId", "u1")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(dataScopeService);
        mvc.perform(
                        delegated(post("/inner/log/login-record"))
                                .contentType("application/json")
                                .content(
                                        "{\"userId\":\"u2\",\"companyId\":\"c1\",\"username\":\"bob\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_login_log", Integer.class))
                .isZero();
        mvc.perform(delegated(get("/inner/user/u2/contexts"))).andExpect(status().isForbidden());
    }

    @Test
    void disabledUserCompanyOrTenantCannotUseDelegatedPermissionSupply() throws Exception {
        for (String table : new String[] {"sys_user", "sys_company", "sys_tenant"}) {
            String id =
                    switch (table) {
                        case "sys_user" -> "u1";
                        case "sys_company" -> "c1";
                        default -> "t1";
                    };
            jdbc.update("UPDATE " + table + " SET status_flag=0 WHERE id=?", id);
            mvc.perform(delegated(get("/inner/menu/c1/routes").param("userId", "u1")))
                    .andExpect(status().isForbidden());
            jdbc.update("UPDATE " + table + " SET status_flag=1 WHERE id=?", id);
        }
        verifyNoInteractions(menuQueryService);
    }

    @Test
    void revokedFamilyIsRejectedBeforePermissionOrBusinessQueriesEvenWhenSaLoginRemains()
            throws Exception {
        LoginUser user = LoginUser.builder().userId("u1").companyId("c1").username("alice").build();
        String familyId = "a".repeat(CREDENTIAL_FINGERPRINT_LENGTH);
        when(redisHelper.getString(anyString())).thenReturn(Optional.empty());
        try (MockedStatic<StpUtil> sa = mockStatic(StpUtil.class);
                MockedStatic<AuthHolder> holder = mockStatic(AuthHolder.class)) {
            sa.when(StpUtil::isLogin).thenReturn(true);
            sa.when(StpUtil::getTokenValue).thenReturn("revoked-access");
            holder.when(AuthHolder::getLoginId).thenReturn("u1");
            holder.when(
                            () ->
                                    AuthHolder.getSessionAttr(
                                            AuthHolder.SESSION_LOGIN_USER, LoginUser.class))
                    .thenReturn(user);
            holder.when(
                            () ->
                                    AuthHolder.getSessionAttr(
                                            AuthHolder.SESSION_AUTH_FAMILY, String.class))
                    .thenReturn(familyId);
            mvc.perform(delegated(get("/inner/menu/c1/routes").param("userId", "u1")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(
                            jsonPath("code")
                                    .value(
                                            com.robot.common.core.domain.ResultCode.UNAUTHORIZED
                                                    .getCode()));
            mvc.perform(get("/sys/users").header("Authorization", "Bearer revoked-access"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(
                            jsonPath("code")
                                    .value(
                                            com.robot.common.core.domain.ResultCode.UNAUTHORIZED
                                                    .getCode()));
        }
        verify(redisHelper, times(2)).getString(anyString());
        verifyNoInteractions(menuQueryService, dataScopeService);
        assertThat(InnerTrustContext.current()).isNull();
    }

    @Test
    void assemblerRejectsUnverifiedInvocationAndForeignMembershipEvenWithServiceCredential() {
        assertThatThrownBy(() -> assembler.assembleAll("u1", List.of()))
                .isInstanceOf(InnerAccessDeniedException.class);
        InnerTrustContext.bindVerifiedService(null, null, null);
        try {
            SysUserCompany foreign = new SysUserCompany();
            foreign.setId("other");
            foreign.setUserId("u2");
            foreign.setCompanyId("c1");
            foreign.setStatusFlag(1);
            foreign.setDeleteFlag(1);
            assertThatThrownBy(() -> assembler.assembleAll("u1", List.of(foreign)))
                    .isInstanceOf(InnerAccessDeniedException.class);
            SysUserCompany fabricated = new SysUserCompany();
            fabricated.setId("not-a-membership");
            fabricated.setUserId("u1");
            fabricated.setCompanyId("c5");
            fabricated.setStatusFlag(1);
            fabricated.setDeleteFlag(1);
            assertThat(assembler.assembleAll("u1", List.of(fabricated))).isEmpty();
        } finally {
            InnerTrustContext.clear();
        }
    }

    private static MockHttpServletRequestBuilder trusted(MockHttpServletRequestBuilder builder) {
        return builder.header(RobotConstants.HEADER_INNER_CALL, "test-secret");
    }

    private static MockHttpServletRequestBuilder delegated(MockHttpServletRequestBuilder builder) {
        return trusted(builder)
                .header(RobotConstants.HEADER_INNER_USER, "u1")
                .header(RobotConstants.HEADER_INNER_COMPANY, "c1")
                .header(RobotConstants.HEADER_INNER_USERNAME, "alice");
    }
}
