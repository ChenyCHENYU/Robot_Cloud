package com.robot.system.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.common.security.exception.InnerAccessDeniedException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.security.InnerSystemAccess;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.service.context.UserContextAssembler;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

class UserSupplyBehaviorTest {
    private static final long ACCOUNT_SECURITY_VERSION = 4L;

    @Test
    void authenticationSupplyRequiresTrustAndNeverProjectsLegacyGlobalDepartment() {
        metadata();
        var users = mock(SysUserMapper.class);
        var members = mock(SysUserCompanyMapper.class);
        var access = mock(InnerSystemAccess.class);
        var assembler = mock(UserContextAssembler.class);
        var service = new UserSupplyService(users, members, assembler, access);
        var user = user();
        user.setDepartmentId("legacy-wrong-company");
        user.setPasswordHash("internal-hash");
        when(users.selectOne(any())).thenReturn(user);
        when(users.selectById("u")).thenReturn(user);
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::requireTrustedService).thenThrow(new InnerAccessDeniedException());
            assertThrows(
                    InnerAccessDeniedException.class,
                    () -> service.findAuthenticationUserByUsername("alice"));
            verifyNoInteractions(users);
            auth.when(AuthUtil::requireTrustedService).thenReturn(null);
            var remote = service.findAuthenticationUserByUsername("alice");
            assertEquals("internal-hash", remote.getPasswordHash());
            assertNull(remote.getDepartmentId());
            verify(access).requireUser("u");
            assertEquals("alice", service.findAuthenticationUserById("u").getUsername());
            when(users.selectOne(any())).thenReturn(null);
            assertNull(service.findAuthenticationUserByUsername("missing"));
            user.setDeleteFlag(0);
            assertNull(service.findAuthenticationUserById("u"));
            when(users.selectById("u")).thenReturn(null);
            assertNull(service.findAuthenticationUserById("u"));
            doThrow(new InnerAccessDeniedException()).when(access).requireUser("other");
            assertThrows(
                    InnerAccessDeniedException.class,
                    () -> service.findAuthenticationUserById("other"));
            verify(users, never()).selectById("other");
        }
    }

    @Test
    void disabledDeletedAndMissingAccountsProduceNoContextsAndActiveMembershipsAreRevalidated() {
        metadata();
        var users = mock(SysUserMapper.class);
        var members = mock(SysUserCompanyMapper.class);
        var access = mock(InnerSystemAccess.class);
        var assembler = mock(UserContextAssembler.class);
        var service = new UserSupplyService(users, members, assembler, access);
        assertTrue(service.listLoginContexts("u").isEmpty());
        var user = user();
        user.setStatusFlag(0);
        when(users.selectById("u")).thenReturn(user);
        assertTrue(service.listLoginContexts("u").isEmpty());
        user.setStatusFlag(1);
        user.setDeleteFlag(0);
        assertTrue(service.listLoginContexts("u").isEmpty());
        verifyNoInteractions(members, assembler);
        user.setDeleteFlag(1);
        var membership = new SysUserCompany();
        membership.setId("m");
        membership.setUserId("u");
        var valid = List.of(membership);
        var contexts =
                List.of(
                        RemoteUserContextDTO.builder()
                                .contextId("m")
                                .departmentId("member-dept")
                                .build());
        when(members.selectList(any())).thenReturn(valid);
        when(assembler.assembleAll("u", valid)).thenReturn(contexts);
        assertEquals(contexts, service.listLoginContexts("u"));
        verify(members)
                .selectList(
                        argThat(
                                wrapper ->
                                        wrapper.getSqlSegment().contains("status_flag")
                                                && wrapper.getSqlSegment().contains("delete_flag")
                                                && ((com.baomidou.mybatisplus.core.conditions
                                                                                .AbstractWrapper<
                                                                        ?, ?, ?>)
                                                                wrapper)
                                                        .getParamNameValuePairs()
                                                        .containsValue("u")));
    }

    private static SysUser user() {
        var user = new SysUser();
        user.setId("u");
        user.setUsername("alice");
        user.setDeleteFlag(1);
        user.setStatusFlag(1);
        user.setSecurityVersion(ACCOUNT_SECURITY_VERSION);
        return user;
    }

    private static void metadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "supply-behavior");
        for (var type : List.of(SysUser.class, SysUserCompany.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
    }
}
