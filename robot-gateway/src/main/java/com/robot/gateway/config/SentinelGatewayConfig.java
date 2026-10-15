package com.robot.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Sentinel 网关流控配置：限流触发时返回统一 Result 结构（HTTP 429 + 业务码）， 与前端 request-core 的错误识别保持一致。
 *
 * <p>Sentinel SCG 集成由 spring-cloud-starter-alibaba-sentinel + sentinel-spring-cloud-gateway-adapter
 * 自动装配，本类只定制阻塞响应。
 */
@Configuration
public class SentinelGatewayConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Register the bounded fixed HTTP429 Result response for Sentinel gateway blocking. */
    @PostConstruct
    public void initBlockHandler() {
        GatewayCallbackManager.setBlockHandler(
                (exchange, throwable) ->
                        ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(
                                        toJsonBytes(Result.failed(ResultCode.TOO_MANY_REQUESTS))));
    }

    private static byte[] toJsonBytes(Result<?> result) {
        try {
            return MAPPER.writeValueAsBytes(result);
        } catch (Exception e) {
            return "{\"code\":429,\"message\":\"too many requests\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
