package com.robot.system.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.account.ProfileUpdateRequest;
import com.robot.system.api.entity.SysUser;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.user.mapper.SysUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class OwnAccountBoundaryTest {
    private static final int TEST_BCRYPT_COST = 4;
    private static final int THREE_BYTE_PASSWORD_OVERFLOW_CHARACTERS = 25;

    @Test
    void omittedProfilePreservesValuesExplicitNullUsesActiveAccountCasAndLostUpdateHasNoAudit() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "own-account"),
                SysUser.class);
        var mapper = mock(SysUserMapper.class);
        var policy = mock(PermissionGrantPolicy.class);
        var audit = mock(PermissionChangeRecorder.class);
        var service =
                new OwnAccountCommandService(
                        mapper, policy, new BCryptPasswordEncoder(TEST_BCRYPT_COST), audit);
        var user = user();
        when(mapper.selectById("u")).thenReturn(user);
        when(mapper.update(any(SysUser.class), any())).thenReturn(1);
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::getLoginUserId).thenReturn("u");
            service.updateProfile("u", new ProfileUpdateRequest());
            assertEquals("Alice", user.getNickname());
            assertEquals("alice@example.test", user.getEmail());
            assertEquals("123", user.getPhone());
            var patch = new ProfileUpdateRequest();
            patch.setNickname("New");
            patch.setEmail(null);
            patch.setPhone(null);
            service.updateProfile("u", patch);
            assertEquals("New", user.getNickname());
            assertNull(user.getEmail());
            assertNull(user.getPhone());
            verify(mapper, times(2))
                    .update(
                            any(SysUser.class),
                            argThat(
                                    wrapper ->
                                            wrapper.getSqlSegment().contains("delete_flag")
                                                    && wrapper.getSqlSegment()
                                                            .contains("revision")));
            patch.setNickname(null);
            assertThrows(ServiceException.class, () -> service.updateProfile("u", patch));
            patch.setNickname("Next");
            when(mapper.update(any(SysUser.class), any())).thenReturn(0);
            assertThrows(ServiceException.class, () -> service.updateProfile("u", patch));
            verify(audit, times(2)).record("profile_update", "u", null);
        }
    }

    @Test
    void selfIdentityOldPasswordAndByteBoundariesProtectPasswordWritesAndRevocationVersion() {
        var mapper = mock(SysUserMapper.class);
        var policy = mock(PermissionGrantPolicy.class);
        var audit = mock(PermissionChangeRecorder.class);
        var encoder = new BCryptPasswordEncoder(TEST_BCRYPT_COST);
        var service = new OwnAccountCommandService(mapper, policy, encoder, audit);
        var user = user();
        user.setPasswordHash(encoder.encode("old-password"));
        user.setSecurityVersion(null);
        when(mapper.selectById("u")).thenReturn(user);
        when(mapper.updateById(any(SysUser.class))).thenReturn(1);
        try (var auth = mockStatic(AuthUtil.class)) {
            assertThrows(
                    ServiceException.class,
                    () -> service.changePassword("u", "old-password", "new-password"));
            auth.when(AuthUtil::getLoginUserId).thenReturn("other");
            assertThrows(
                    ServiceException.class,
                    () -> service.changePassword("u", "old-password", "new-password"));
            auth.when(AuthUtil::getLoginUserId).thenReturn("u");
            assertThrows(
                    ServiceException.class,
                    () ->
                            service.changePassword(
                                    "u",
                                    "old-password",
                                    "汉".repeat(THREE_BYTE_PASSWORD_OVERFLOW_CHARACTERS)));
            assertThrows(
                    ServiceException.class,
                    () -> service.changePassword("u", null, "new-password"));
            assertThrows(
                    ServiceException.class,
                    () -> service.changePassword("u", "wrong-password", "new-password"));
            verify(mapper, never()).updateById(any(SysUser.class));
            service.changePassword("u", "old-password", "new-password");
            assertEquals(1L, user.getSecurityVersion());
            assertTrue(encoder.matches("new-password", user.getPasswordHash()));
            when(mapper.updateById(any(SysUser.class))).thenReturn(0);
            assertThrows(
                    ServiceException.class,
                    () -> service.changePassword("u", "new-password", "next-password"));
            verify(audit, times(1)).record("change_password", "u", null);
        }
    }

    private static SysUser user() {
        var user = new SysUser();
        user.setId("u");
        user.setNickname("Alice");
        user.setEmail("alice@example.test");
        user.setPhone("123");
        user.setRevision(1);
        user.setDeleteFlag(1);
        return user;
    }
}
