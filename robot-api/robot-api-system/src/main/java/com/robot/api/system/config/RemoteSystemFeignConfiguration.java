package com.robot.api.system.config;

import com.robot.api.system.feign.InnerCallRelayInterceptor;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.common.security.interceptor.InnerTrustRelayInterceptor;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 仅由 @FeignClient(configuration=...) 引用，不进入应用组件扫描/父上下文。 */
public class RemoteSystemFeignConfiguration {
    @Bean
    public RequestInterceptor innerCallRelayInterceptor(Environment environment) {
        return new InnerCallRelayInterceptor(() -> InnerCallRelayInterceptor.resolveSecret(
                environment.getProperty("robot.security.inner-secret"),
                environment.getProperty("ROBOT_INNER_SECRET")));
    }

    @Bean
    public RequestInterceptor innerTrustRelayInterceptor() {
        // 账号供给的目标来自密码/票据/refresh 验证，不能混入请求可能携带的另一 access 身份。
        return new InnerTrustRelayInterceptor(RemoteSystemService.class,
                path -> !path.startsWith("/inner/user/"));
    }
}
