package com.robot.common.mybatis.tenant;

import com.baomidou.mybatisplus.core.plugins.IgnoreStrategy;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.exception.InnerAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TenantQueryScopeTest {
    @AfterEach
    void clear() {
        InterceptorIgnoreHelper.clearIgnoreStrategy();
        InnerTrustContext.clear();
    }

    @Test
    void unverifiedRequestCannotDisableTenantLine() {
        assertThatThrownBy(() -> TenantQueryScope.withoutTenantLine(() -> "forbidden"))
                .isInstanceOf(InnerAccessDeniedException.class);
        assertThat(InterceptorIgnoreHelper.hasIgnoreStrategy()).isFalse();
    }

    @Test
    void nestedScopeAndInnerFailurePreserveOuterScopeAndCleanupOnExit() {
        InnerTrustContext.bindVerifiedService(null, null, null);
        TenantQueryScope.withoutTenantLine(() -> {
            assertThat(InterceptorIgnoreHelper.willIgnoreTenantLine("query")).isTrue();
            assertThat(InterceptorIgnoreHelper.willIgnoreBlockAttack("query")).isFalse();
            assertThatThrownBy(() -> TenantQueryScope.withoutTenantLine(() -> {
                throw new IllegalStateException("nested");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(InterceptorIgnoreHelper.willIgnoreTenantLine("query")).isTrue();
            return null;
        });
        assertThat(InterceptorIgnoreHelper.hasIgnoreStrategy()).isFalse();
        assertThat(InterceptorIgnoreHelper.willIgnoreTenantLine("query")).isFalse();
        assertThatThrownBy(() -> TenantQueryScope.withoutTenantLine(() -> {
            throw new IllegalStateException("outer");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(InterceptorIgnoreHelper.hasIgnoreStrategy()).isFalse();
    }

    @Test
    void foreignIgnoreScopeIsRejectedAndRetainsItsOriginalStrategy() {
        InnerTrustContext.bindVerifiedService(null, null, null);
        InterceptorIgnoreHelper.handle(IgnoreStrategy.builder().blockAttack(true).build());
        assertThatThrownBy(() -> TenantQueryScope.withoutTenantLine(() -> null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(InterceptorIgnoreHelper.willIgnoreBlockAttack("query")).isTrue();
        assertThat(InterceptorIgnoreHelper.willIgnoreTenantLine("query")).isFalse();
    }
}
