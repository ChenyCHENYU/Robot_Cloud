package com.robot.common.mybatis.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.robot.common.mybatis.properties.TenantProperties;
import com.robot.common.mybatis.tenant.RobotTenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.HashSet;

/**
 * MyBatis-Plus 装配：插件链顺序固定（官方推荐）——
 * 多租户 → 分页 → 乐观锁 → 防全表更新删除。
 *
 * <p>插件顺序说明：租户拦截必须最先（改写 SQL 前置于分页计数）；
 * BlockAttack 兜底禁止无 WHERE 的全表 update/delete（wl-skills-bd 数据安全护栏）。
 */
@AutoConfiguration
@EnableConfigurationProperties(TenantProperties.class)
public class RobotMybatisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TenantProperties tenantProperties) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1. 租户行级过滤（可通过 robot.mybatis.tenant.enabled=false 关闭，默认开启）
        if (tenantProperties.isEnabled()) {
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    robotTenantLineHandler(tenantProperties)));
        }

        // 2. 分页（MySQL 方言，单库）
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);

        // 3. 乐观锁（REVISION）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 4. 防全表更新与删除
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "robot.mybatis.tenant", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public RobotTenantLineHandler robotTenantLineHandler(TenantProperties tenantProperties) {
        return new RobotTenantLineHandler(new HashSet<>(tenantProperties.getIgnoreTables()));
    }

    @Bean
    @ConditionalOnMissingBean
    public IdentifierGenerator robotIdGenerator() {
        return new RobotIdGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public com.robot.common.mybatis.handler.AuditMetaObjectHandler auditMetaObjectHandler() {
        return new com.robot.common.mybatis.handler.AuditMetaObjectHandler();
    }
}
