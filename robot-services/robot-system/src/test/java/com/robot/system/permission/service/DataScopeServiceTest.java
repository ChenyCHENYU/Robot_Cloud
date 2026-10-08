package com.robot.system.permission.service;

import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * 数据范围聚合测试：多角色取最大范围、临时授权并入。
 */
@ExtendWith(MockitoExtension.class)
class DataScopeServiceTest {

    @Mock
    private SysRoleMapper roleMapper;

    @Mock
    private SysUserRoleMapper userRoleMapper;

    @Mock
    private SysTempAuthorizationMapper tempAuthorizationMapper;

    private DataScopeService service() {
        return new DataScopeService(roleMapper, userRoleMapper, tempAuthorizationMapper);
    }

    private SysRole role(String id, String code, String scope) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode(code);
        role.setDataScope(scope);
        role.setStatusFlag(1);
        role.setDeleteFlag(1);
        role.setCompanyId("cmp-1");
        return role;
    }

    @Test
    @DisplayName("多角色范围取最大：self + department_below → department_below")
    void shouldPickStrongestScope() {
        SysUserRole binding = new SysUserRole();
        binding.setUserId("usr-1");
        binding.setRoleId("role-1");
        SysUserRole binding2 = new SysUserRole();
        binding2.setUserId("usr-1");
        binding2.setRoleId("role-2");
        lenient().when(roleMapper.selectList(any())).thenReturn(
                List.of(role("role-1", "STAFF", "self"),
                        role("role-2", "MANAGER", "department_below")));
        lenient().when(userRoleMapper.selectList(any())).thenReturn(List.of(binding, binding2));
        lenient().when(tempAuthorizationMapper.selectList(any())).thenReturn(List.of());

        List<com.robot.system.api.vo.permission.DataScopeVO> scopes = service().scopesOfUser("usr-1", "cmp-1");
        assertEquals(1, scopes.size());
        assertEquals("department_below", scopes.get(0).scope());
        assertEquals("*", scopes.get(0).module());
    }

    @Test
    @DisplayName("生效中的临时授权并入并提升范围到 all")
    void tempAuthorizationShouldRaiseScope() {
        SysUserRole binding = new SysUserRole();
        binding.setUserId("usr-1");
        binding.setRoleId("role-1");
        lenient().when(roleMapper.selectList(any())).thenReturn(
                List.of(role("role-1", "STAFF", "self"), role("role-9", "ACTING_ADMIN", "all")));
        lenient().when(userRoleMapper.selectList(any())).thenReturn(List.of(binding));

        SysTempAuthorization tempAuth = new SysTempAuthorization();
        tempAuth.setUserId("usr-1");
        tempAuth.setRoleId("role-9");
        tempAuth.setStatusFlag(1);
        tempAuth.setDeleteFlag(1);
        tempAuth.setEffectFrom(LocalDateTime.now().minusDays(1));
        tempAuth.setEffectTo(LocalDateTime.now().plusDays(1));
        lenient().when(tempAuthorizationMapper.selectList(any())).thenReturn(List.of(tempAuth));
        lenient().when(roleMapper.selectBatchIds(any()))
                .thenReturn(List.of(role("role-9", "ACTING_ADMIN", "all")));

        List<com.robot.system.api.vo.permission.DataScopeVO> scopes = service().scopesOfUser("usr-1", "cmp-1");
        assertEquals("all", scopes.get(0).scope());
    }

    @Test
    @DisplayName("无任何角色回落 self（最小权限）")
    void noRoleShouldFallbackToSelf() {
        lenient().when(roleMapper.selectList(any())).thenReturn(List.of());
        lenient().when(tempAuthorizationMapper.selectList(any())).thenReturn(List.of());

        List<com.robot.system.api.vo.permission.DataScopeVO> scopes = service().scopesOfUser("usr-1", "cmp-1");
        assertEquals("self", scopes.get(0).scope());
    }
}
