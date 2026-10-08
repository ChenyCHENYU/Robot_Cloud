package com.robot.api.system.feign;

import com.robot.common.core.constant.RobotConstants;
import feign.RequestInterceptor;
import feign.RequestTemplate;

import java.util.function.Supplier;

/**
 * 内部调用凭据透传：所有 Feign 调用自动携带 X-Inner-Call 密钥，
 * 与提供方 InnerCallInterceptor 配对。
 *
 * <p>密钥来源优先级（与提供方 robot.security.inner-secret 完全同源，
 * 避免两侧配置漂移）：Spring 配置 → ROBOT_INNER_SECRET 环境变量 → 开发默认值。
 */
public class InnerCallRelayInterceptor implements RequestInterceptor {

    private static final String DEFAULT_SECRET = "robot-inner-dev-secret";

    private final Supplier<String> secretSupplier;

    public InnerCallRelayInterceptor(Supplier<String> secretSupplier) {
        this.secretSupplier = secretSupplier;
    }

    @Override
    public void apply(RequestTemplate template) {
        String secret = secretSupplier.get();
        if (secret != null && !secret.isBlank()) {
            template.header(RobotConstants.HEADER_INNER_CALL, secret);
        }
    }

    /** 默认解析链：配置属性 → 环境变量 → 开发默认值。 */
    public static String resolveSecret(String configured,
                                       String envValue) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return DEFAULT_SECRET;
    }
}
