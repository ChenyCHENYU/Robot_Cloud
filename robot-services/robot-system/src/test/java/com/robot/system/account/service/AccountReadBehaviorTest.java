package com.robot.system.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.account.mapper.SysLoginLogMapper;
import com.robot.system.api.dto.account.PasswordChangeRequest;
import com.robot.system.api.dto.account.ProfileUpdateRequest;
import com.robot.system.api.entity.SysLoginLog;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.api.entity.SysUser;
import com.robot.system.mapper.SysPermissionAuditLogMapper;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.user.service.UserService;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

/** Exercises account projections through their use case and verifies identity-derived queries. */
class AccountReadBehaviorTest {
    private static final int ABSENT_AGENT_INDEX = 5;
    private static final int SECURITY_SETTING_COUNT = 4;
    private static final LocalDateTime FIXED_TIME = LocalDateTime.parse("2026-10-01T00:00:00");

    @Test
    void profileUsesCurrentRoleAndMembershipAndHandlesNeverLoggedInAccount() {
        metadata();
        var users = mock(UserService.class);
        var commands = mock(OwnAccountCommandService.class);
        var login = mock(SysLoginLogMapper.class);
        var audits = mock(SysPermissionAuditLogMapper.class);
        var roles = mock(EffectiveRoleResolver.class);
        var service = new AccountService(users, commands, login, audits, roles);
        var user = new SysUser();
        user.setUsername("alice");
        user.setNickname("Alice");
        when(users.getById("u")).thenReturn(user);
        when(users.currentMemberDepartment("u")).thenReturn("department-current");
        when(login.selectList(any())).thenReturn(List.of());
        when(roles.resolve("u", "c")).thenReturn(EffectiveRoleResolver.EffectiveRoles.EMPTY);
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::getLoginUserId).thenReturn("u");
            auth.when(AuthUtil::getLoginCompanyId).thenReturn("c");
            var profile = service.profile();
            assertEquals("普通用户", profile.role());
            assertEquals("department-current", profile.department());
            assertEquals("", profile.createTime());
            assertEquals("", profile.lastLoginTime());
            assertEquals("", profile.lastLoginIp());
            user.setCreateDateTime(LocalDateTime.parse("2026-10-01T01:02:03"));
            var entry = event("e", null, 1);
            entry.setIp("127.0.0.1");
            when(login.selectList(any())).thenReturn(List.of(entry));
            when(roles.resolve("u", "c"))
                    .thenReturn(new EffectiveRoleResolver.EffectiveRoles(true, List.of()));
            profile = service.profile();
            assertEquals("超级管理员", profile.role());
            assertEquals("2026-10-01 01:02:03", profile.createTime());
            assertEquals("127.0.0.1", profile.lastLoginIp());
            verify(login, times(2))
                    .selectList(
                            argThat(
                                    wrapper ->
                                            wrapper.getSqlSegment().contains("login_type")
                                                    && wrapper.getSqlSegment().contains("LIMIT 1")
                                                    && ((com.baomidou.mybatisplus.core.conditions
                                                                                    .AbstractWrapper<
                                                                            ?, ?, ?>)
                                                                    wrapper)
                                                            .getParamNameValuePairs()
                                                            .containsValue("u")));
            var patch = new ProfileUpdateRequest();
            patch.setEmail(null);
            service.updateProfile(patch);
            verify(commands).updateProfile("u", patch);
            var password = new PasswordChangeRequest();
            password.setOldPassword("old-pass");
            password.setNewPassword("new-pass");
            service.changePassword(password);
            verify(commands).changePassword("u", "old-pass", "new-pass");
            when(users.getById("u")).thenReturn(null);
            assertThrows(ServiceException.class, service::profile);
            auth.when(AuthUtil::getLoginUserId).thenReturn(null);
            assertThrows(ServiceException.class, service::profile);
            assertThrows(ServiceException.class, service::loginRecords);
        }
    }

    @Test
    void loginProjectionHandlesMobileBrowserPriorityUnknownAndFailuresWithoutAgent() {
        metadata();
        var login = mock(SysLoginLogMapper.class);
        var service =
                new AccountService(
                        mock(UserService.class),
                        mock(OwnAccountCommandService.class),
                        login,
                        mock(SysPermissionAuditLogMapper.class),
                        mock(EffectiveRoleResolver.class));
        when(login.selectList(any()))
                .thenReturn(
                        List.of(
                                event("edge", "Mobile Chrome/123 Edg/123", 1),
                                event("chrome", "Chrome/123", 0),
                                event("firefox", "Firefox/123", null),
                                event("safari", "Safari/123", 1),
                                event("unknown", "Robot", 1),
                                event("absent", null, 1)));
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::getLoginUserId).thenReturn("u");
            var records = service.loginRecords();
            assertEquals(
                    List.of("Edge", "Chrome", "Firefox", "Safari", "未知", ""),
                    records.stream().map(item -> item.browser()).toList());
            assertEquals("移动端", records.get(0).device());
            assertEquals("桌面端", records.get(1).device());
            assertEquals("", records.get(ABSENT_AGENT_INDEX).device());
            assertEquals("", records.get(0).ip());
            assertEquals(
                    List.of("success", "failed", "failed", "success", "success", "success"),
                    records.stream().map(item -> item.status()).toList());
            verify(login)
                    .selectList(
                            argThat(
                                    wrapper ->
                                            wrapper.getSqlSegment().contains("LIMIT 20")
                                                    && ((com.baomidou.mybatisplus.core.conditions
                                                                                    .AbstractWrapper<
                                                                            ?, ?, ?>)
                                                                    wrapper)
                                                            .getParamNameValuePairs()
                                                            .containsValue("u")));
            assertEquals(SECURITY_SETTING_COUNT, service.securitySettings().size());
            assertFalse(
                    service.securitySettings().stream()
                            .filter(item -> item.key().equals("two-factor"))
                            .findFirst()
                            .orElseThrow()
                            .enabled());
        }
    }

    @Test
    void activityClassificationAndMissingPrincipalFailClosed() {
        metadata();
        var audits = mock(SysPermissionAuditLogMapper.class);
        var service =
                new AccountService(
                        mock(UserService.class),
                        mock(OwnAccountCommandService.class),
                        mock(SysLoginLogMapper.class),
                        audits,
                        mock(EffectiveRoleResolver.class));
        when(audits.selectList(any()))
                .thenReturn(
                        java.util.Arrays.asList(
                                        null,
                                        "save_role",
                                        "assign_permissions",
                                        "grant_role",
                                        "update_role",
                                        "rebind_member",
                                        "move_menu",
                                        "delete_role",
                                        "inspect")
                                .stream()
                                .map(
                                        action -> {
                                            var entry = new SysPermissionAuditLog();
                                            entry.setId("audit");
                                            entry.setAction(action);
                                            entry.setTarget(action == null ? null : "target");
                                            entry.setCreateDateTime(FIXED_TIME);
                                            return entry;
                                        })
                                .toList());
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::getLoginUserId).thenReturn("u");
            auth.when(AuthUtil::getLoginUsername).thenReturn("alice");
            auth.when(AuthUtil::getLoginCompanyId).thenReturn("c");
            assertEquals(
                    List.of(
                            "other", "create", "create", "create", "update", "update", "update",
                            "delete", "other"),
                    service.activityLogs().stream().map(item -> item.actionType()).toList());
            verify(audits)
                    .selectList(
                            argThat(
                                    wrapper ->
                                            wrapper.getSqlSegment().contains("LIMIT 20")
                                                    && ((com.baomidou.mybatisplus.core.conditions
                                                                                    .AbstractWrapper<
                                                                            ?, ?, ?>)
                                                                    wrapper)
                                                            .getParamNameValuePairs()
                                                            .containsValue("alice")));
            auth.when(AuthUtil::getLoginCompanyId).thenReturn(null);
            assertThrows(ServiceException.class, service::activityLogs);
            auth.when(AuthUtil::getLoginCompanyId).thenReturn("c");
            auth.when(AuthUtil::getLoginUsername).thenReturn(" ");
            assertThrows(ServiceException.class, service::activityLogs);
            auth.when(AuthUtil::getLoginUserId).thenReturn(null);
            assertThrows(ServiceException.class, service::activityLogs);
        }
    }

    private static SysLoginLog event(String id, String agent, Integer status) {
        var entry = new SysLoginLog();
        entry.setId(id);
        entry.setUserAgent(agent);
        entry.setStatusFlag(status);
        entry.setLoginTime(FIXED_TIME);
        return entry;
    }

    private static void metadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "account-behavior");
        for (var type : List.of(SysLoginLog.class, SysPermissionAuditLog.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
    }
}
