package com.robot.common.mybatis.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.robot.common.mybatis.domain.TenantEntity;
import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.util.AuthHolder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class AuditMetaObjectHandlerTest {
    private static final int EXISTING_REVISION = 12;

    @BeforeAll
    static void initializeActualMybatisFillMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "audit-test"),
                AuditEntity.class);
    }

    @AfterEach
    void clear() {
        InnerTrustContext.clear();
    }

    @Test
    void updateOverwritesPreviouslyLoadedActorAndTimeWithoutTouchingCreateRevisionOrTenant() {
        AuditEntity entity = new AuditEntity();
        LocalDateTime created = LocalDateTime.parse("2025-01-02T03:04:05");
        entity.setCreateUserNo("creator");
        entity.setCreateDateTime(created);
        entity.setUpdateUserNo("previous-editor");
        entity.setUpdateDateTime(created);
        entity.setRevision(EXISTING_REVISION);
        entity.setDeleteFlag(1);
        entity.setCompanyId("original-company");
        LoginUser logged =
                LoginUser.builder()
                        .userId("u1")
                        .username("current-editor")
                        .companyId("new-company")
                        .build();
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        try (MockedStatic<AuthHolder> holder = mockStatic(AuthHolder.class)) {
            holder.when(AuthHolder::getLoginId).thenReturn("u1");
            holder.when(
                            () ->
                                    AuthHolder.getSessionAttr(
                                            AuthHolder.SESSION_LOGIN_USER, LoginUser.class))
                    .thenReturn(logged);
            new AuditMetaObjectHandler().updateFill(SystemMetaObject.forObject(entity));
        }
        assertThat(entity.getUpdateUserNo()).isEqualTo("current-editor");
        assertThat(entity.getUpdateDateTime()).isBetween(before, LocalDateTime.now(ZoneOffset.UTC));
        assertThat(entity.getCreateUserNo()).isEqualTo("creator");
        assertThat(entity.getCreateDateTime()).isEqualTo(created);
        assertThat(entity.getRevision()).isEqualTo(EXISTING_REVISION);
        assertThat(entity.getCompanyId()).isEqualTo("original-company");
        assertThat(entity.getDeleteFlag()).isEqualTo(1);
    }

    @Test
    void internalDelegateFillsActorAndCompanyAndUpdateUsesTheDelegateRatherThanSystemFallback() {
        InnerTrustContext.bindVerifiedService(
                "delegate-user", "delegate-company", "delegate-editor");
        AuditEntity entity = new AuditEntity();
        new AuditMetaObjectHandler().insertFill(SystemMetaObject.forObject(entity));
        assertThat(entity.getCreateUserNo()).isEqualTo("delegate-editor");
        assertThat(entity.getUpdateUserNo()).isEqualTo("delegate-editor");
        assertThat(entity.getCompanyId()).isEqualTo("delegate-company");
        assertThat(entity.getRevision()).isZero();
        assertThat(entity.getDeleteFlag()).isEqualTo(1);
        LocalDateTime created = entity.getCreateDateTime();
        InnerTrustContext.bindVerifiedService("delegate-user", "delegate-company", "next-editor");
        new AuditMetaObjectHandler().updateFill(SystemMetaObject.forObject(entity));
        assertThat(entity.getUpdateUserNo()).isEqualTo("next-editor");
        assertThat(entity.getCreateUserNo()).isEqualTo("delegate-editor");
        assertThat(entity.getCreateDateTime()).isEqualTo(created);
    }

    @TableName("test_audit")
    static class AuditEntity extends TenantEntity {}

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Shanghai", "America/New_York"})
    void auditWritesStayInUtcRegardlessOfJvmDefaultZone(String timezone) {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(timezone));
            InnerTrustContext.bindVerifiedService("actor", "company", "editor");
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
            AuditEntity entity = new AuditEntity();
            AuditMetaObjectHandler handler = new AuditMetaObjectHandler();
            handler.insertFill(SystemMetaObject.forObject(entity));
            handler.updateFill(SystemMetaObject.forObject(entity));
            LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);
            assertThat(entity.getCreateDateTime()).isBetween(before, after);
            assertThat(entity.getUpdateDateTime()).isBetween(before, after);
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
