package com.robot.system.permission.service;

import com.robot.system.api.entity.SysRole;
import com.robot.system.permission.service.EffectiveRoleResolver.EffectiveRoles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 数据范围聚合测试：多角色取最大范围、临时授权并入。
 */
@ExtendWith(MockitoExtension.class)
class DataScopeServiceTest {

    @Mock
    private EffectiveRoleResolver effectiveRoleResolver;

    private DataScopeService service() {
        return new DataScopeService(effectiveRoleResolver,
                org.mockito.Mockito.mock(com.robot.system.user.mapper.SysUserMapper.class),
                org.mockito.Mockito.mock(com.robot.system.dept.mapper.SysDepartmentMapper.class));
    }

    private SysRole role(String id, String scope) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setDataScope(scope);
        return role;
    }

    @Test
    @DisplayName("多角色范围取最大：self + department_below → department_below")
    void shouldPickStrongestScope() {
        when(effectiveRoleResolver.resolve("usr-1", "cmp-1")).thenReturn(new EffectiveRoles(
                false, List.of(role("role-1", "self"), role("role-2", "department_below"))));

        List<com.robot.system.api.vo.permission.DataScopeVO> scopes =
                service().scopesOfUser("usr-1", "cmp-1");
        assertEquals(1, scopes.size());
        assertEquals("department_below", scopes.get(0).scope());
        assertEquals("*", scopes.get(0).module());
    }

    @Test
    @DisplayName("临时授权并入后的 all 角色提升范围")
    void tempAuthorizationShouldRaiseScope() {
        when(effectiveRoleResolver.resolve("usr-1", "cmp-1")).thenReturn(new EffectiveRoles(
                false, List.of(role("role-1", "self"), role("role-9", "all"))));

        List<com.robot.system.api.vo.permission.DataScopeVO> scopes =
                service().scopesOfUser("usr-1", "cmp-1");
        assertEquals("all", scopes.get(0).scope());
    }

    @Test
    @DisplayName("无任何角色不声明数据访问能力")
    void noRoleShouldFallbackToSelf() {
        when(effectiveRoleResolver.resolve("usr-1", "cmp-1"))
                .thenReturn(EffectiveRoles.EMPTY);

        List<com.robot.system.api.vo.permission.DataScopeVO> scopes =
                service().scopesOfUser("usr-1", "cmp-1");
        org.junit.jupiter.api.Assertions.assertTrue(scopes.isEmpty());
    }
}
