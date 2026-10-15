package com.robot.api.system.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/** 契约包自动装配入口。内部凭据拦截器只在 RemoteSystemService 的 Feign 子上下文装配。 */
@AutoConfiguration
public class RobotApiAutoConfiguration {}
