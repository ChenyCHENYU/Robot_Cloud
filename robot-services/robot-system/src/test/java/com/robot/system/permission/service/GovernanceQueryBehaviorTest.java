package com.robot.system.permission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.role.PermissionAuditPageQuery;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.api.entity.SysPermissionConstraint;
import com.robot.system.mapper.SysPermissionAuditLogMapper;
import com.robot.system.mapper.SysPermissionConstraintMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

class GovernanceQueryBehaviorTest {
    @Test
    void boundedReadAndAuditFiltersAreCompanyScopedAndInvalidWindowDoesNotQuery() {
        for (var type : List.of(SysPermissionAuditLog.class, SysPermissionConstraint.class)) {
            initializeEntityMetadata(type);
        }
        var audits = mock(SysPermissionAuditLogMapper.class);
        var constraints = mock(SysPermissionConstraintMapper.class);
        var service = new PermissionGovernanceQueryService(constraints, audits);
        when(audits.selectPage(any(), any()))
                .thenAnswer(
                        inv ->
                                ((Page<SysPermissionAuditLog>) inv.getArgument(0))
                                        .setRecords(List.of())
                                        .setTotal(0));
        when(audits.selectList(any())).thenReturn(List.of());
        when(constraints.selectList(any())).thenReturn(List.of());
        try (var auth = mockStatic(AuthUtil.class)) {
            auth.when(AuthUtil::getLoginCompanyId).thenReturn("c");
            var query = new PermissionAuditPageQuery();
            assertTrue(service.auditPage(query).getList().isEmpty());
            query.setFrom(LocalDateTime.parse("2026-01-01T00:00:00"));
            service.auditPage(query);
            query.setTo(query.getFrom().plusDays(1));
            query.setAction("move_menu");
            query.setTarget("menu-id");
            service.auditPage(query);
            verify(audits, atLeastOnce())
                    .selectPage(
                            any(),
                            argThat(
                                    wrapper ->
                                            wrapper.getSqlSegment().contains("company_id")
                                                    && wrapper.getSqlSegment()
                                                            .contains("create_date_time")
                                                    && wrapper.getSqlSegment().contains("target")));
            query.setFrom(null);
            service.auditPage(query);
            query.setFrom(query.getTo().plusSeconds(1));
            int before = mockingDetails(audits).getInvocations().size();
            assertThrows(ServiceException.class, () -> service.auditPage(query));
            assertEquals(before, mockingDetails(audits).getInvocations().size());
            assertTrue(service.constraints().isEmpty());
            assertTrue(service.auditLogs().isEmpty());
            verify(constraints)
                    .selectList(
                            argThat(
                                    wrapper ->
                                            wrapper.getSqlSegment().contains("LIMIT 500")
                                                    && wrapper.getSqlSegment()
                                                            .contains("company_id")));
            auth.when(AuthUtil::getLoginCompanyId).thenReturn(" ");
            assertThrows(ServiceException.class, service::constraints);
            assertThrows(ServiceException.class, service::auditLogs);
        }
    }

    private static void initializeEntityMetadata(Class<?> type) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "governance-query"), type);
    }
}
