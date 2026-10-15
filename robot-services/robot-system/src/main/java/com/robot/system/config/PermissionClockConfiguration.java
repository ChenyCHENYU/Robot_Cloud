package com.robot.system.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 授权窗口统一使用UTC墙上时间；可替换Clock用于临时授权和缓存边界测试。 */
@Configuration(proxyBeanMethods = false)
public class PermissionClockConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock permissionClock() {
        return Clock.systemUTC();
    }
}
