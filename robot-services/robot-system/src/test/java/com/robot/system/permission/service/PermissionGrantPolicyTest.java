package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PermissionGrantPolicyTest {
    @Mock EffectiveRoleResolver resolver;
    @Mock SysRoleMenuMapper menuMapper;
    @Mock SysRoleButtonMapper buttonMapper;
    PermissionGrantPolicy policy;
    PermissionGrantPolicy.Operator operator;

    @BeforeEach
    void setUp() {
        policy = new PermissionGrantPolicy(resolver, menuMapper, buttonMapper);
        operator =
                new PermissionGrantPolicy.Operator(
                        "u-own",
                        "c-1",
                        new EffectiveRoleResolver.EffectiveRoles(
                                false, List.of(role("r-own", "STAFF", "self"))));
    }

    @Test
    void resourceCeilingIgnoresDeletedAndUnrelatedBindings() {
        when(menuMapper.selectList(any()))
                .thenReturn(
                        List.of(
                                binding("r-own", "m-own", 1),
                                binding("r-other", "m-secret", 1),
                                binding("r-own", "m-deleted", 0)));
        when(buttonMapper.selectList(any())).thenReturn(List.of());
        assertDoesNotThrow(() -> policy.assertPermissions(operator, Set.of("m-own")));
        forbidden(() -> policy.assertPermissions(operator, Set.of("m-secret")));
        forbidden(() -> policy.assertPermissions(operator, Set.of("m-deleted")));
    }

    @Test
    void roleAssignmentCannotGrantWiderDataScopeOrSuperCode() {
        forbidden(() -> policy.assertRoles(operator, List.of(role("r-wide", "MANAGER", "all"))));
        forbidden(
                () ->
                        policy.assertRoles(
                                operator, List.of(role("r-super", "SUPER_ADMIN", "self"))));
        verifyNoInteractions(menuMapper, buttonMapper);
    }

    @Test
    void inactiveAndCrossCompanyRoleCannotBeAssigned() {
        SysRole inactive = role("r-off", "STAFF", "self");
        inactive.setStatusFlag(0);
        forbidden(() -> policy.assertRoles(operator, List.of(inactive)));
        SysRole foreign = role("r-foreign", "STAFF", "self");
        foreign.setCompanyId("c-other");
        forbidden(() -> policy.assertRoles(operator, List.of(foreign)));
    }

    @Test
    void targetRoleResourceCeilingUsesAllTargetBindings() {
        when(menuMapper.selectList(any()))
                .thenReturn(
                        List.of(binding("r-target", "m-secret", 1)),
                        List.of(binding("r-own", "m-own", 1)));
        when(buttonMapper.selectList(any())).thenReturn(List.of());
        forbidden(() -> policy.assertRoles(operator, List.of(role("r-target", "MANAGER", "self"))));
    }

    @Test
    void disabledSuperRoleStillProtectsMemberManagement() {
        SysRole target = role("r-super", "SUPER_ADMIN", "self");
        target.setStatusFlag(0);
        forbidden(() -> policy.assertManagedRoles(operator, List.of(target)));
        verifyNoInteractions(menuMapper, buttonMapper);
    }

    @Test
    void effectiveSuperCanManageLegacyCustomRoleButCannotConfigureNewCustomScope() {
        var superOperator =
                new PermissionGrantPolicy.Operator(
                        "u-super",
                        "c-1",
                        new EffectiveRoleResolver.EffectiveRoles(
                                true, List.of(role("r-super", "SUPER_ADMIN", "all"))));
        assertDoesNotThrow(
                () ->
                        policy.assertManagedRoles(
                                superOperator, List.of(role("r-old", "STAFF", "custom"))));
        assertEquals(
                ResultCode.PARAM_INVALID.getCode(),
                assertThrows(
                                ServiceException.class,
                                () -> policy.assertScope(superOperator, "custom"))
                        .getCode());
    }

    @Test
    void ownRoleAndSuperCodeCannotBeRepurposed() {
        forbidden(() -> policy.assertNotSelfRoleEdit(operator, "r-own"));
        forbidden(
                () ->
                        policy.assertRoleCode(
                                operator, role("r-super", "SUPER_ADMIN", "all"), "STAFF"));
    }

    private SysRole role(String id, String code, String scope) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCompanyId("c-1");
        role.setRoleCode(code);
        role.setDataScope(scope);
        role.setDeleteFlag(1);
        role.setStatusFlag(1);
        return role;
    }

    private SysRoleMenu binding(String roleId, String menuId, int deleteFlag) {
        SysRoleMenu binding = new SysRoleMenu();
        binding.setRoleId(roleId);
        binding.setMenuId(menuId);
        binding.setDeleteFlag(deleteFlag);
        return binding;
    }

    private void forbidden(Runnable action) {
        assertEquals(
                ResultCode.FORBIDDEN.getCode(),
                assertThrows(ServiceException.class, action::run).getCode());
    }
}
