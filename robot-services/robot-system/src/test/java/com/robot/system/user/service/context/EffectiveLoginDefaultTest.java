package com.robot.system.user.service.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysCompany;
import com.robot.system.api.entity.SysTenant;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.mapper.SysCompanyMapper;
import com.robot.system.mapper.SysTenantMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EffectiveLoginDefaultTest {
    @ParameterizedTest
    @ValueSource(strings = {"membership", "company", "tenant"})
    void
            disabledOriginalPrimaryMembershipCompanyOrTenantLeavesOneStableEffectiveDefaultWithoutStorageChanges(
                    String invalid) {
        var companies = mock(SysCompanyMapper.class);
        var tenants = mock(SysTenantMapper.class);
        var roles = mock(SysRoleMapper.class);
        var bindings = mock(SysUserRoleMapper.class);
        var members = mock(SysUserCompanyMapper.class);
        var assembler = new UserContextAssembler(companies, tenants, roles, bindings, members);
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "effective-default");
        for (var type : List.of(SysUserCompany.class, SysUserRole.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
        var original = member("m-0", "c-0", 1);
        var next = member("m-1", "c-1", 0);
        var last = member("m-2", "c-2", 0);
        when(members.selectList(any()))
                .thenReturn(
                        invalid.equals("membership")
                                ? List.of(last, next)
                                : List.of(last, original, next));
        var company0 = company("c-0", "t-0");
        if (invalid.equals("company")) {
            company0.setStatusFlag(0);
        }
        when(companies.selectBatchIds(any()))
                .thenReturn(List.of(company("c-2", "t-2"), company0, company("c-1", "t-1")));
        var tenant0 = tenant("t-0");
        if (invalid.equals("tenant")) {
            tenant0.setStatusFlag(0);
        }
        when(tenants.selectBatchIds(any()))
                .thenReturn(List.of(tenant0, tenant("t-1"), tenant("t-2")));
        when(bindings.selectList(any())).thenReturn(List.of());
        InnerTrustContext.bindVerifiedService(null, null, null);
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::requireTrustedService).thenReturn(InnerTrustContext.current());
            var contexts = assembler.assembleAll("u", List.of(original, next, last));
            assertEquals(2, contexts.size(), invalid);
            assertEquals(
                    1,
                    contexts.stream()
                            .filter(item -> Boolean.TRUE.equals(item.getPrimaryFlag()))
                            .count(),
                    invalid);
            assertEquals(
                    "m-1",
                    contexts.stream()
                            .filter(item -> Boolean.TRUE.equals(item.getPrimaryFlag()))
                            .findFirst()
                            .orElseThrow()
                            .getContextId(),
                    invalid);
            assertEquals(1, original.getPrimaryFlag());
            assertEquals(0, next.getPrimaryFlag());
            assertEquals(0, last.getPrimaryFlag());
            when(members.selectList(any()))
                    .thenReturn(
                            invalid.equals("membership")
                                    ? List.of(next, last)
                                    : List.of(next, last, original));
            assertEquals(
                    "m-1",
                    assembler.assembleAll("u", List.of(original, next, last)).stream()
                            .filter(item -> Boolean.TRUE.equals(item.getPrimaryFlag()))
                            .findFirst()
                            .orElseThrow()
                            .getContextId());
            verify(members, never()).updateById(any(SysUserCompany.class));
        } finally {
            InnerTrustContext.clear();
        }
    }

    @Test
    void effectiveStoredPrimaryIsPreservedAndMultipleStoredPrimariesResolveDeterministically() {
        var companies = mock(SysCompanyMapper.class);
        var tenants = mock(SysTenantMapper.class);
        var members = mock(SysUserCompanyMapper.class);
        var bindings = mock(SysUserRoleMapper.class);
        var assembler =
                new UserContextAssembler(
                        companies, tenants, mock(SysRoleMapper.class), bindings, members);
        var first = member("m-1", "c-1", 0);
        var primary = member("m-2", "c-2", 1);
        when(members.selectList(any())).thenReturn(List.of(primary, first));
        when(companies.selectBatchIds(any()))
                .thenReturn(List.of(company("c-1", "t"), company("c-2", "t")));
        when(tenants.selectBatchIds(any())).thenReturn(List.of(tenant("t")));
        when(bindings.selectList(any())).thenReturn(List.of());
        InnerTrustContext.bindVerifiedService(null, null, null);
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::requireTrustedService).thenReturn(InnerTrustContext.current());
            assertEquals(
                    "m-2",
                    assembler.assembleAll("u", List.of(first, primary)).stream()
                            .filter(item -> Boolean.TRUE.equals(item.getPrimaryFlag()))
                            .findFirst()
                            .orElseThrow()
                            .getContextId());
            first.setPrimaryFlag(1);
            assertEquals(
                    "m-1",
                    assembler.assembleAll("u", List.of(first, primary)).stream()
                            .filter(item -> Boolean.TRUE.equals(item.getPrimaryFlag()))
                            .findFirst()
                            .orElseThrow()
                            .getContextId());
            when(companies.selectBatchIds(any())).thenReturn(List.of());
            assertTrue(assembler.assembleAll("u", List.of(first, primary)).isEmpty());
        } finally {
            InnerTrustContext.clear();
        }
    }

    private static SysUserCompany member(String id, String company, int primary) {
        var member = new SysUserCompany();
        member.setId(id);
        member.setCompanyId(company);
        member.setUserId("u");
        member.setPrimaryFlag(primary);
        member.setStatusFlag(1);
        member.setDeleteFlag(1);
        return member;
    }

    private static SysCompany company(String id, String tenant) {
        var company = new SysCompany();
        company.setId(id);
        company.setTenantId(tenant);
        company.setDeleteFlag(1);
        company.setStatusFlag(1);
        return company;
    }

    private static SysTenant tenant(String id) {
        var tenant = new SysTenant();
        tenant.setId(id);
        tenant.setDeleteFlag(1);
        tenant.setStatusFlag(1);
        return tenant;
    }
}
