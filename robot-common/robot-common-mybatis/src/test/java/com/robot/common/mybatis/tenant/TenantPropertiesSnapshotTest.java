package com.robot.common.mybatis.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robot.common.mybatis.properties.TenantProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 真实配置绑定仍可写入，调用方不能扩大租户白名单。 */
class TenantPropertiesSnapshotTest {
    @Test
    void boundWhitelistIsIndependentOfInputAndCannotBeMutatedThroughGetter() {
        var source = new ArrayList<>(List.of("global_table"));
        var properties = new TenantProperties();
        properties.setIgnoreTables(source);
        source.add("private_table");
        assertThat(properties.getIgnoreTables()).containsExactly("global_table");
        assertThatThrownBy(() -> properties.getIgnoreTables().add("private_table"))
                .isInstanceOf(UnsupportedOperationException.class);
        properties.setIgnoreTables(null);
        assertThat(properties.getIgnoreTables()).isEmpty();
    }

    @Test
    void actualSpringBinderWritesWhitelistBeforeTenantHandlerUsesItsSnapshot() {
        new ApplicationContextRunner()
                .withUserConfiguration(BoundProperties.class)
                .withPropertyValues("robot.mybatis.tenant.ignore-tables[0]=GLOBAL_TABLE")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var properties = context.getBean(TenantProperties.class);
                            assertThat(properties.getIgnoreTables())
                                    .containsExactly("GLOBAL_TABLE");
                            var handler = new RobotTenantLineHandler(properties.getIgnoreTables());
                            assertThat(handler.ignoreTable("global_table")).isTrue();
                            assertThat(handler.ignoreTable("private_table")).isFalse();
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TenantProperties.class)
    static class BoundProperties {}
}
