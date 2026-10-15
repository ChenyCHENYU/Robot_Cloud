package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysUser;
import com.robot.system.user.mapper.SysUserMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 独立平台事实不能从公司超管/临时超管推导。 */
class GlobalDirectoryPolicyTest {
    @Test
    void companySuperAdministratorWithoutPlatformFactIsRejected() {
        var grants = mock(PermissionGrantPolicy.class);
        var users = mock(SysUserMapper.class);
        when(grants.requireOperator())
                .thenReturn(
                        new PermissionGrantPolicy.Operator(
                                "u",
                                "c",
                                new EffectiveRoleResolver.EffectiveRoles(
                                        true, List.of(new SysRole()))));
        SysUser account = new SysUser();
        account.setId("u");
        account.setStatusFlag(1);
        account.setDeleteFlag(1);
        when(users.selectById("u")).thenReturn(account);
        assertThrows(
                ServiceException.class,
                () -> new GlobalDirectoryPolicy(grants, users).requirePlatformAdministrator());
        account.setPlatformAdminFlag(1);
        assertDoesNotThrow(
                () -> new GlobalDirectoryPolicy(grants, users).requirePlatformAdministrator());
        account.setStatusFlag(0);
        assertThrows(
                ServiceException.class,
                () -> new GlobalDirectoryPolicy(grants, users).requirePlatformAdministrator());
    }

    @Test
    void temporaryOnlyIdentityIsRejectedBeforeReadingPlatformFact() {
        var grants = mock(PermissionGrantPolicy.class);
        var users = mock(SysUserMapper.class);
        when(grants.requireOperator())
                .thenThrow(new ServiceException(com.robot.common.core.domain.ResultCode.FORBIDDEN));
        assertThrows(
                ServiceException.class,
                () -> new GlobalDirectoryPolicy(grants, users).requirePlatformAdministrator());
        verifyNoInteractions(users);
    }
}
