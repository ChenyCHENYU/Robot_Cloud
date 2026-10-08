package com.robot.system.permission.service;

import com.robot.system.api.entity.SysRole;
import com.robot.system.permission.service.EffectiveRoleResolver.EffectiveRoles;
import com.robot.system.api.vo.permission.DataScopeVO;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 有效角色解析器测试（RC01/RC02 事实源）：
 * 超管只来自用户自身有效集合；跨公司角色排除；临时授权并入；停用角色排除。
 */
@ExtendWith(MockitoExtension.class)
class EffectiveRoleResolverTest {

    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private SysTempAuthorizationMapper tempAuthorizationMapper;

    private EffectiveRoleResolver resolver() {
        return new EffectiveRoleResolver(roleMapper, userRoleMapper, tempAuthorizationMapper);
    }

    private SysRole role(String id, String companyId, String code, String dataScope) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCompanyId(companyId);
        role.setRoleCode(code);
        role.setDataScope(dataScope);
        role.setStatusFlag(1);
        role.setDeleteFlag(1);
        return role;
    }

    /** RC01 核心场景：公司有超管+管理员，用户只绑定管理员 → 非超管、只有管理员。 */
    @Test
    @DisplayName("RC01: 公司存在超管而用户未绑定 → 非超管")
    void companySuperAdminDoesNotLeak() {
        when(roleMapper.selectList(any())).thenReturn(List.of(
                role("r-super", "cmp-1", "SUPER_ADMIN", "all"),
                role("r-admin", "cmp-1", "SYS_ADMIN", "all")));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(tempAuthorizationMapper.selectList(any())).thenReturn(List.of());

        EffectiveRoles effective = resolver().resolve("usr-1", "cmp-1");

        org.junit.jupiter.api.Assertions.assertFalse(effective.superAdmin(),
                "未绑定超管的用户不得被判为超管");
        org.junit.jupiter.api.Assertions.assertTrue(effective.isEmpty());
    }

    @Test
    @DisplayName("绑定超管的用户 → 超管")
    void boundSuperAdminIsSuper() {
        when(roleMapper.selectList(any())).thenReturn(List.of(
                role("r-super", "cmp-1", "SUPER_ADMIN", "all")));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                binding("r-super")));
        when(tempAuthorizationMapper.selectList(any())).thenReturn(List.of());

        EffectiveRoles effective = resolver().resolve("usr-1", "cmp-1");
        org.junit.jupiter.api.Assertions.assertTrue(effective.superAdmin());
        assertEquals(1, effective.roles().size());
    }

    @Test
    @DisplayName("跨公司绑定被交集排除（B 公司角色不进 A 上下文）")
    void crossCompanyBindingExcluded() {
        when(roleMapper.selectList(any())).thenReturn(List.of(
                role("r-a", "cmp-A", "STAFF", "self")));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                binding("r-a"), binding("r-b")));
        when(tempAuthorizationMapper.selectList(any())).thenReturn(List.of());

        EffectiveRoles effective = resolver().resolve("usr-1", "cmp-A");

        assertEquals(1, effective.roles().size());
        assertEquals("r-a", effective.roles().get(0).getId());
    }

    @Test
    @DisplayName("临时授权角色并入有效集合（限本公司）")
    void tempAuthorizationMergesIn() {
        when(roleMapper.selectList(any())).thenReturn(List.of(
                role("r-staff", "cmp-1", "STAFF", "self"),
                role("r-acting", "cmp-1", "ACTING_ADMIN", "all")));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(binding("r-staff")));
        com.robot.system.api.entity.SysTempAuthorization tempAuth =
                new com.robot.system.api.entity.SysTempAuthorization();
        tempAuth.setRoleId("r-acting");
        when(tempAuthorizationMapper.selectList(any())).thenReturn(List.of(tempAuth));

        EffectiveRoles effective = resolver().resolve("usr-1", "cmp-1");

        assertEquals(2, effective.roles().size());
        org.junit.jupiter.api.Assertions.assertFalse(effective.superAdmin());
    }

    @Test
    @DisplayName("停用角色不参与（代码级防御过滤兜底）")
    void disabledCompanyRoleExcluded() {
        SysRole disabled = role("r-off", "cmp-1", "STAFF", "self");
        disabled.setStatusFlag(0);
        when(roleMapper.selectList(any())).thenReturn(List.of(disabled));

        EffectiveRoles effective = resolver().resolve("usr-1", "cmp-1");
        org.junit.jupiter.api.Assertions.assertTrue(effective.isEmpty());
    }

    private com.robot.system.api.entity.SysUserRole binding(String roleId) {
        com.robot.system.api.entity.SysUserRole binding =
                new com.robot.system.api.entity.SysUserRole();
        binding.setRoleId(roleId);
        binding.setDeleteFlag(1);
        return binding;
    }
}
