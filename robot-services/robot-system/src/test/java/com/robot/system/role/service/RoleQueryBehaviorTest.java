package com.robot.system.role.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.robot.common.core.domain.PageQuery;
import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.dto.role.RolePageQuery;
import com.robot.system.api.dto.role.TemporaryAuthorizationPageQuery;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import java.time.Clock;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

class RoleQueryBehaviorTest {
    private static final int QUERIED_MEMBER_COUNT = 4;
    private static final int RETAINED_MEMBER_COUNT = 3;

    @Test
    void roleQueriesKeepCompanyGuardAndOptionalFiltersInSql() {
        var fixture = new Fixture();
        var role = role("r", "c");
        when(fixture.roles.selectById("r")).thenReturn(role);
        when(fixture.roles.selectList(any())).thenReturn(List.of(role));
        when(fixture.roles.selectPage(any(Page.class), any()))
                .thenAnswer(
                        inv ->
                                ((Page<SysRole>) inv.getArgument(0))
                                        .setRecords(List.of(role))
                                        .setTotal(1));
        var query = new RolePageQuery();
        query.setKeyword("Staff");
        query.setStatus(0);
        assertEquals(1, fixture.service.queryPage(query).getTotal());
        verify(fixture.roles)
                .selectPage(
                        any(),
                        argThat(
                                wrapper ->
                                        wrapper.getSqlSegment().contains("LIKE")
                                                && wrapper.getSqlSegment().contains("status_flag")
                                                && wrapper.getSqlSegment().contains("company_id")));
        query.setKeyword(" ");
        query.setStatus(null);
        fixture.service.queryPage(query);
        query.setKeyword(null);
        fixture.service.queryPage(query);
        assertEquals("r", fixture.service.options().get(0).id());
        when(fixture.menus.selectList(any())).thenReturn(List.of());
        when(fixture.buttons.selectList(any())).thenReturn(List.of());
        assertTrue(fixture.service.getDetail("r").getPermissionIds().isEmpty());
        assertEquals("self", fixture.service.dataScopes("r").get(0).scope());
        role.setDataScope("company");
        assertEquals("company", fixture.service.dataScopes("r").get(0).scope());
        assertThrows(ServiceException.class, () -> fixture.service.getDetail(" "));
        assertThrows(ServiceException.class, () -> fixture.service.getDetail("absent"));
        role.setCompanyId("other");
        assertThrows(ServiceException.class, () -> fixture.service.getDetail("r"));
        role.setCompanyId("c");
        role.setDeleteFlag(0);
        assertThrows(ServiceException.class, () -> fixture.service.getDetail("r"));
        assertThrows(
                ServiceException.class,
                () -> fixture.service.activeTempAuthorizations("u", "other"));
    }

    @Test
    void memberPageDropsRemovedMemberAndSeparatesAccountAndMembershipDisablement() {
        var fixture = new Fixture();
        when(fixture.roles.selectById("r")).thenReturn(role("r", "c"));
        when(fixture.users.selectPage(any(Page.class), any()))
                .thenAnswer(
                        inv ->
                                ((Page<SysUser>) inv.getArgument(0))
                                        .setRecords(List.of())
                                        .setTotal(0));
        assertTrue(fixture.service.roleUsersPage("r", new PageQuery()).getList().isEmpty());
        verifyNoInteractions(fixture.members);
        var enabled = user("active", 1);
        var accountOff = user("account-off", 0);
        var memberOff = user("member-off", 1);
        var removed = user("removed", 1);
        when(fixture.users.selectPage(any(Page.class), any()))
                .thenAnswer(
                        inv ->
                                ((Page<SysUser>) inv.getArgument(0))
                                        .setRecords(
                                                List.of(enabled, accountOff, memberOff, removed))
                                        .setTotal(QUERIED_MEMBER_COUNT));
        when(fixture.members.selectList(any()))
                .thenReturn(
                        List.of(
                                member("active", 1),
                                member("account-off", 1),
                                member("member-off", 0)));
        var result = fixture.service.roleUsersPage("r", new PageQuery());
        assertEquals(
                List.of("active", "disabled", "disabled"),
                result.getList().stream().map(item -> item.status()).toList());
        assertEquals(QUERIED_MEMBER_COUNT, result.getTotal());
        assertEquals(RETAINED_MEMBER_COUNT, fixture.service.roleUsers("r").size());
    }

    @Test
    void
            temporaryHistoryIncludesMissingNamesButNeverForeignCompanyRoleNameAndUsesBoundedLegacyReads() {
        var fixture = new Fixture();
        when(fixture.roles.selectById("r")).thenReturn(role("r", "c"));
        when(fixture.temporary.selectPage(any(Page.class), any()))
                .thenAnswer(
                        inv ->
                                ((Page<SysTempAuthorization>) inv.getArgument(0))
                                        .setRecords(List.of())
                                        .setTotal(0));
        assertTrue(fixture.service.allTempAuthorizations().isEmpty());
        assertTrue(fixture.service.tempAuthorizations("r").isEmpty());
        verify(fixture.users, never()).selectBatchIds(any());
        var first = grant("a", "u", "r");
        var stale = grant("b", "absent", "foreign");
        when(fixture.temporary.selectPage(any(Page.class), any()))
                .thenAnswer(
                        inv ->
                                ((Page<SysTempAuthorization>) inv.getArgument(0))
                                        .setRecords(List.of(first, stale))
                                        .setTotal(2));
        when(fixture.users.selectBatchIds(any())).thenReturn(List.of(user("u", 1)));
        when(fixture.roles.selectBatchIds(any()))
                .thenReturn(List.of(role("r", "c"), role("foreign", "other")));
        var query = new TemporaryAuthorizationPageQuery();
        query.setUserId("u");
        query.setRoleId("r");
        query.setStatusFlag(0);
        var history = fixture.service.temporaryAuthorizationPage(query).getList();
        assertEquals("u", history.get(0).getUserNickname());
        assertEquals("r", history.get(0).getRoleName());
        assertNull(history.get(1).getUserNickname());
        assertNull(history.get(1).getRoleName());
        when(fixture.temporary.selectList(any())).thenReturn(List.of(first));
        assertEquals(1, fixture.service.activeTempAuthorizations("u", "c").size());
    }

    private static SysRole role(String id, String company) {
        var role = new SysRole();
        role.setId(id);
        role.setRoleName(id);
        role.setCompanyId(company);
        role.setDeleteFlag(1);
        role.setStatusFlag(1);
        return role;
    }

    private static SysUser user(String id, int status) {
        var user = new SysUser();
        user.setId(id);
        user.setNickname(id);
        user.setUsername(id);
        user.setStatusFlag(status);
        return user;
    }

    private static SysUserCompany member(String id, int status) {
        var member = new SysUserCompany();
        member.setUserId(id);
        member.setStatusFlag(status);
        return member;
    }

    private static SysTempAuthorization grant(String id, String user, String role) {
        var grant = new SysTempAuthorization();
        grant.setId(id);
        grant.setUserId(user);
        grant.setRoleId(role);
        return grant;
    }

    private static class Fixture {
        final SysRoleMapper roles = mock(SysRoleMapper.class);
        final SysRoleMenuMapper menus = mock(SysRoleMenuMapper.class);
        final SysRoleButtonMapper buttons = mock(SysRoleButtonMapper.class);
        final SysUserMapper users = mock(SysUserMapper.class);
        final SysTempAuthorizationMapper temporary = mock(SysTempAuthorizationMapper.class);
        final SysUserCompanyMapper members = mock(SysUserCompanyMapper.class);
        final RoleQueryService service;

        Fixture() {
            var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "role-query");
            var policy = mock(PermissionGrantPolicy.class);
            when(policy.requireCompany()).thenReturn("c");
            service =
                    new RoleQueryService(
                            roles,
                            menus,
                            buttons,
                            policy,
                            temporary,
                            users,
                            members,
                            Clock.systemUTC());
            for (var type :
                    List.of(
                            SysRole.class,
                            SysUser.class,
                            SysUserCompany.class,
                            SysTempAuthorization.class)) {
                TableInfoHelper.initTableInfo(assistant, type);
            }
        }
    }
}
