package com.robot.system.user.service;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import com.robot.system.permission.service.PermissionChangeRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * RC08/T05 成员归属约束：A 公司管理员对 B 公司用户（非本公司成员）的
 * 详情/重置密码/启停/编辑/删除操作一律拒绝。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceMembershipTest {

    @Mock private SysUserMapper userMapper;
    @Mock private SysUserCompanyMapper userCompanyMapper;
    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private SysRoleMapper roleMapper;
    @Mock private PermissionChangeRecorder recorder;

    private UserService service;
    private MockedStatic<AuthUtil> auth;

    @BeforeEach
    void setUp() {
        service = new UserService(userCompanyMapper, userRoleMapper, roleMapper,
                new BCryptPasswordEncoder(4), recorder);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "baseMapper", userMapper);
        auth = mockStatic(AuthUtil.class);
        auth.when(AuthUtil::getLoginCompanyId).thenReturn("cmp-A");
        auth.when(AuthUtil::getLoginUserId).thenReturn("operator-A");
        auth.when(AuthUtil::isSuperAdmin).thenReturn(true);
        when(userCompanyMapper.selectCount(any())).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        auth.close();
    }

    @Test
    @DisplayName("跨公司猜 ID 查看详情 → 403")
    void crossCompanyDetailRejected() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.getDetail("usr-in-B"));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    @DisplayName("跨公司重置密码 → 403")
    void crossCompanyResetRejected() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.resetPassword("usr-in-B", "newpass123"));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    @DisplayName("跨公司启停 → 403")
    void crossCompanyStatusRejected() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.toggleStatus("usr-in-B", 0));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    @DisplayName("跨公司编辑 → 403")
    void crossCompanyUpdateRejected() {
        com.robot.system.api.dto.user.UserSaveRequest request =
                new com.robot.system.api.dto.user.UserSaveRequest();
        request.setUsername("victim");
        request.setNickname("victim");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.update("usr-in-B", request));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    @DisplayName("跨公司删除 → 403")
    void crossCompanyDeleteRejected() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.delete("usr-in-B"));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    @DisplayName("本公司成员放行（成员数 > 0）")
    void sameCompanyMemberPasses() {
        when(userCompanyMapper.selectCount(any())).thenReturn(1L);
        when(userMapper.selectById("usr-in-A")).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.getDetail("usr-in-A"));
    }
}
