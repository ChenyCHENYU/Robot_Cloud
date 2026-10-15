package com.robot.gateway.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.domain.Result;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** WebFlux 响应写出工具：网关侧统一 JSON 错误响应（Jackson 序列化，与业务服务一致）。 */
public final class WebFluxResponseWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebFluxResponseWriter() {}

    /**
     * Write the fixed Result envelope with the intended HTTP status.
     *
     * @param exchange current gateway exchange
     * @param status intended HTTP rejection status
     * @param result safe business envelope
     * @return response completion
     */
    public static Mono<Void> write(
            ServerWebExchange exchange, HttpStatus status, Result<?> result) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body;
        try {
            body = MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            body = "{\"code\":500,\"message\":\"gateway serialization error\"}";
        }
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
