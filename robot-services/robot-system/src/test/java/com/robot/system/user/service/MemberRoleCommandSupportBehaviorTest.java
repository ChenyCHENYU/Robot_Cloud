package com.robot.system.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.permission.service.PermissionGrantPolicy.Operator;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 成员写用例的目标角色上界、未结束窗口和绑定范围；不依赖其他测试预热MP元数据。 */
class MemberRoleCommandSupportBehaviorTest {
    private static final int MAX_ROLE_ID_LENGTH = 64;
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-14T00:00:00");
    private final SysUserRoleMapper bindings = mock(SysUserRoleMapper.class);
    private final SysRoleMapper roles = mock(SysRoleMapper.class);
    private final SysTempAuthorizationMapper temporary = mock(SysTempAuthorizationMapper.class);
    private final PermissionGrantPolicy policy = mock(PermissionGrantPolicy.class);
    private final MemberRoleCommandSupport service =
            new MemberRoleCommandSupport(
                    bindings,
                    roles,
                    temporary,
                    policy,
                    Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC));
    private final Operator operator =
            new Operator("operator", "company", EffectiveRoleResolver.EffectiveRoles.EMPTY);

    @BeforeEach
    void metadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "member-command");
        for (var type : List.of(SysUserRole.class, SysTempAuthorization.class, SysRole.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
    }

    @Test
    void ordinarySelfManagementFailsAndPermanentSuperOrUnboundMemberNeedsNoTargetCeilingRead() {
        assertCode(ResultCode.FORBIDDEN, () -> service.assertCanManageMember(operator, "operator"));
        var superOperator =
                new Operator(
                        "operator",
                        "company",
                        new EffectiveRoleResolver.EffectiveRoles(true, List.of()));
        service.assertCanManageMember(superOperator, "operator");
        verifyNoInteractions(bindings, roles, temporary, policy);
        when(bindings.selectList(any())).thenReturn(List.of());
        when(temporary.selectList(any())).thenReturn(List.of());
        service.assertCanManageMember(operator, "target");
        verifyNoInteractions(roles, policy);
    }

    @Test
    void
            managementIncludesDisabledPermanentAndFutureRolesButExcludesForeignDeletedRevokedAndEndedFacts() {
        var permanent = binding("target", "company", "permanent");
        var wrongUser = binding("other", "company", "wrong-user");
        var wrongCompany = binding("target", "foreign", "wrong-company");
        var deleted = binding("target", "company", "deleted");
        deleted.setDeleteFlag(0);
        when(bindings.selectList(any()))
                .thenReturn(List.of(permanent, wrongUser, wrongCompany, deleted));
        var future = grant("future");
        future.setEffectFrom(NOW.plusDays(1));
        var ended = grant("ended");
        ended.setEffectTo(NOW);
        var missingEnd = grant("missing-end");
        missingEnd.setEffectTo(null);
        var revoked = grant("revoked");
        revoked.setStatusFlag(0);
        var deletedGrant = grant("deleted-grant");
        deletedGrant.setDeleteFlag(0);
        var otherUser = grant("other-user");
        otherUser.setUserId("other");
        var otherCompany = grant("other-company");
        otherCompany.setCompanyId("foreign");
        when(temporary.selectList(any()))
                .thenReturn(
                        List.of(
                                future,
                                ended,
                                missingEnd,
                                revoked,
                                deletedGrant,
                                otherUser,
                                otherCompany));
        var disabledPermanent = role("permanent");
        disabledPermanent.setStatusFlag(0);
        var disabledFuture = role("future");
        disabledFuture.setStatusFlag(0);
        when(roles.selectList(any())).thenReturn(List.of(disabledPermanent, disabledFuture));
        service.assertCanManageMember(operator, "target");
        verify(policy).assertManagedRoles(operator, List.of(disabledPermanent, disabledFuture));
        ArgumentCaptor<Wrapper<SysTempAuthorization>> query =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(temporary).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("effect_to >"));
        assertTrue(parameters(query.getValue()).containsAll(List.of("target", "company", NOW, 1)));
        verify(bindings, never()).insert(any(SysUserRole.class));
        verify(temporary, never()).update(any(SysTempAuthorization.class), any());
    }

    @Test
    void danglingForeignOrDeletedManagedRoleRejectsBeforePolicyAndPolicyDenialNeverWrites() {
        when(bindings.selectList(any())).thenReturn(List.of(binding("target", "company", "bound")));
        when(temporary.selectList(any())).thenReturn(List.of());
        var unrelated = role("unrelated");
        var foreign = role("bound");
        foreign.setCompanyId("foreign");
        var deleted = role("bound");
        deleted.setDeleteFlag(0);
        when(roles.selectList(any())).thenReturn(List.of(unrelated, foreign, deleted));
        assertCode(ResultCode.FORBIDDEN, () -> service.assertCanManageMember(operator, "target"));
        verifyNoInteractions(policy);
        var bound = role("bound");
        when(roles.selectList(any())).thenReturn(List.of(bound));
        doThrow(new ServiceException(ResultCode.FORBIDDEN))
                .when(policy)
                .assertManagedRoles(operator, List.of(bound));
        assertCode(ResultCode.FORBIDDEN, () -> service.assertCanManageMember(operator, "target"));
        verify(bindings, never()).insert(any(SysUserRole.class));
    }

    @Test
    void emptyRolePatchAndMalformedIdsCannotStartRoleQueriesOrBindings() {
        assertEquals(List.of(), service.validatedRoles(operator, null));
        assertEquals(List.of(), service.validatedRoles(operator, List.of()));
        for (List<String> invalid :
                List.of(
                        Arrays.asList((String) null),
                        List.of(" "),
                        List.of("a".repeat(MAX_ROLE_ID_LENGTH + 1)),
                        List.of("duplicate", "duplicate"),
                        IntStream.rangeClosed(0, RobotConstants.BATCH_WRITE_LIMIT)
                                .mapToObj(index -> "role-" + index)
                                .toList())) {
            assertCode(ResultCode.PARAM_INVALID, () -> service.validatedRoles(operator, invalid));
        }
        service.insertRoles("target", "company", List.of());
        verifyNoInteractions(bindings, roles, temporary, policy);
    }

    @Test
    void assignableRolesMustAllBeActiveCompanyFactsAndStillPassPermanentDelegationCeiling() {
        var unrelated = role("unrelated");
        var foreign = role("requested");
        foreign.setCompanyId("foreign");
        var deleted = role("requested");
        deleted.setDeleteFlag(0);
        var disabled = role("requested");
        disabled.setStatusFlag(0);
        when(roles.selectList(any())).thenReturn(List.of(unrelated, foreign, deleted, disabled));
        assertCode(
                ResultCode.FORBIDDEN, () -> service.validatedRoles(operator, List.of("requested")));
        verifyNoInteractions(policy);
        var requested = role("requested");
        when(roles.selectList(any())).thenReturn(List.of(requested));
        assertEquals(List.of(requested), service.validatedRoles(operator, List.of("requested")));
        verify(policy).assertRoles(operator, List.of(requested));
        doThrow(new ServiceException(ResultCode.FORBIDDEN))
                .when(policy)
                .assertRoles(operator, List.of(requested));
        assertCode(
                ResultCode.FORBIDDEN, () -> service.validatedRoles(operator, List.of("requested")));
        verifyNoInteractions(bindings, temporary);
    }

    @Test
    void distinctBindingsHaveTrustedMemberCompanyAndLostInsertCannotReportSuccess() {
        when(bindings.insert(any(SysUserRole.class))).thenReturn(1);
        service.insertRoles("target", "company", List.of(role("first"), role("second")));
        ArgumentCaptor<SysUserRole> rows = ArgumentCaptor.forClass(SysUserRole.class);
        verify(bindings, org.mockito.Mockito.times(2)).insert(rows.capture());
        assertNotSame(rows.getAllValues().get(0), rows.getAllValues().get(1));
        assertEquals(
                List.of("first", "second"),
                rows.getAllValues().stream().map(SysUserRole::getRoleId).toList());
        assertTrue(
                rows.getAllValues().stream()
                        .allMatch(
                                row ->
                                        "target".equals(row.getUserId())
                                                && "company".equals(row.getCompanyId())));
        when(bindings.insert(any(SysUserRole.class))).thenReturn(0);
        assertCode(
                ResultCode.DATA_CONCURRENT_CONFLICT,
                () -> service.insertRoles("target", "company", List.of(role("lost"))));
    }

    @Test
    void removalDeletesOnlyThisCompanyBindingsAndRevokesOnlyActiveThisMemberTemporaryRoles() {
        service.deleteRoles("target", "company");
        ArgumentCaptor<Wrapper<SysUserRole>> deleted = ArgumentCaptor.forClass(Wrapper.class);
        verify(bindings).delete(deleted.capture());
        assertTrue(deleted.getValue().getSqlSegment().contains("company_id"));
        assertTrue(deleted.getValue().getSqlSegment().contains("user_id"));
        assertTrue(parameters(deleted.getValue()).containsAll(List.of("target", "company")));
        service.revokeTemporaryRoles("target", "company");
        ArgumentCaptor<SysTempAuthorization> patch =
                ArgumentCaptor.forClass(SysTempAuthorization.class);
        ArgumentCaptor<Wrapper<SysTempAuthorization>> revoked =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(temporary).update(patch.capture(), revoked.capture());
        assertEquals(0, patch.getValue().getStatusFlag());
        assertTrue(revoked.getValue().getSqlSegment().contains("delete_flag"));
        assertTrue(revoked.getValue().getSqlSegment().contains("status_flag"));
        assertTrue(parameters(revoked.getValue()).containsAll(List.of("target", "company", 1)));
    }

    private static java.util.Collection<Object> parameters(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values();
    }

    private static SysUserRole binding(String userId, String companyId, String roleId) {
        var binding = new SysUserRole();
        binding.setUserId(userId);
        binding.setCompanyId(companyId);
        binding.setRoleId(roleId);
        binding.setDeleteFlag(1);
        return binding;
    }

    private static SysTempAuthorization grant(String roleId) {
        var grant = new SysTempAuthorization();
        grant.setUserId("target");
        grant.setCompanyId("company");
        grant.setRoleId(roleId);
        grant.setStatusFlag(1);
        grant.setDeleteFlag(1);
        grant.setEffectFrom(NOW.minusDays(1));
        grant.setEffectTo(NOW.plusDays(2));
        return grant;
    }

    private static SysRole role(String id) {
        var role = new SysRole();
        role.setId(id);
        role.setCompanyId("company");
        role.setStatusFlag(1);
        role.setDeleteFlag(1);
        return role;
    }

    private static void assertCode(ResultCode code, Runnable action) {
        assertEquals(code.getCode(), assertThrows(ServiceException.class, action::run).getCode());
    }
}
