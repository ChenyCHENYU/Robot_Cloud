package com.robot.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 认证服务配置：密码摘要器（BCrypt，强度 10）。 */
@Configuration
public class PasswordConfig {
    private static final int BCRYPT_STRENGTH = 10;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }
}
