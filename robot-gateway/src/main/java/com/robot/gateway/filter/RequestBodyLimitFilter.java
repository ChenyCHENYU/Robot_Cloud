package com.robot.gateway.filter;

import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.gateway.util.WebFluxResponseWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 在转发前读取有界 body；没有 Content-Length 的 chunked/HTTP2 同样按真实字节检查。 */
@Component
public final class RequestBodyLimitFilter implements GlobalFilter, Ordered {
    static final int AUTH_LIMIT = 64 * 1024;
    static final int SYSTEM_LIMIT = 10 * 1024 * 1024;
    private static final int MAX_RECEIVERS = 16;
    private static final int FILTER_ORDER = -90;
    private final java.util.concurrent.Semaphore receiving =
            new java.util.concurrent.Semaphore(MAX_RECEIVERS);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        int limit = path.startsWith("/auth/") ? AUTH_LIMIT : SYSTEM_LIMIT;
        if (exchange.getRequest().getHeaders().getContentLength() > limit) {
            return rejected(exchange);
        }
        if (!receiving.tryAcquire()) {
            return WebFluxResponseWriter.write(
                    exchange,
                    HttpStatus.TOO_MANY_REQUESTS,
                    Result.failed(
                            ResultCode.TOO_MANY_REQUESTS,
                            ResultCode.TOO_MANY_REQUESTS.getMessage(),
                            exchange.getResponse().getHeaders().getFirst("X-Trace-Id")));
        }
        return DataBufferUtils.join(exchange.getRequest().getBody(), limit)
                .map(
                        buffer -> {
                            try {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                return bytes;
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        })
                .defaultIfEmpty(new byte[0])
                .flatMap(
                        bytes -> {
                            var request =
                                    new BoundedBodyRequest(
                                            exchange.getRequest(),
                                            bytes,
                                            exchange.getResponse().bufferFactory());
                            return chain.filter(exchange.mutate().request(request).build());
                        })
                .onErrorResume(DataBufferLimitException.class, error -> rejected(exchange))
                .doFinally(signal -> receiving.release());
    }

    private Mono<Void> rejected(ServerWebExchange exchange) {
        return WebFluxResponseWriter.write(
                exchange,
                HttpStatus.PAYLOAD_TOO_LARGE,
                Result.failed(
                        ResultCode.PARAM_INVALID,
                        "请求体超过允许大小",
                        exchange.getResponse().getHeaders().getFirst("X-Trace-Id")));
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    /** Owns the fresh bounded aggregate; it does not retain the outer filter or exchange. */
    private static final class BoundedBodyRequest extends ServerHttpRequestDecorator {
        private final byte[] bytes;
        private final DataBufferFactory buffers;

        private BoundedBodyRequest(
                ServerHttpRequest delegate, byte[] bytes, DataBufferFactory buffers) {
            super(delegate);
            this.bytes = bytes.clone();
            this.buffers = buffers;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(super.getHeaders());
            headers.remove(HttpHeaders.TRANSFER_ENCODING);
            headers.setContentLength(bytes.length);
            return headers;
        }

        @Override
        public Flux<DataBuffer> getBody() {
            return Flux.defer(() -> Flux.just(buffers.wrap(bytes)));
        }
    }
}
