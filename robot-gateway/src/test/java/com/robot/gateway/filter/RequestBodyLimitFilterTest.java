package com.robot.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.netty.buffer.PooledByteBufAllocator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RequestBodyLimitFilterTest {
    private static final int SMALL_TEST_BODY_BYTES = 10;
    private static final int MAX_CONCURRENT_RECEIVERS = 16;

    @ParameterizedTest
    @CsvSource({"true,-1", "true,0", "true,1", "false,-1", "false,0", "false,1"})
    void actualBytesBoundUnknownLengthAndKnownLengthBeforeForwardingAndReleaseBuffers(
            boolean declared, int boundaryOffset) {
        var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        int size = RequestBodyLimitFilter.AUTH_LIMIT + boundaryOffset;
        var buffer = factory.allocateBuffer(size);
        buffer.write(new byte[size]);
        var builder = MockServerHttpRequest.post("/auth/login");
        if (declared) {
            builder.header("Content-Length", String.valueOf(size));
        }
        var exchange = MockServerWebExchange.from(builder.body(Flux.just(buffer)));
        AtomicInteger forwarded = new AtomicInteger();
        new RequestBodyLimitFilter()
                .filter(
                        exchange,
                        actual -> {
                            forwarded.incrementAndGet();
                            return DataBufferUtils.join(actual.getRequest().getBody())
                                    .doOnNext(DataBufferUtils::release)
                                    .then();
                        })
                .block();
        if (size > RequestBodyLimitFilter.AUTH_LIMIT) {
            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
            assertEquals(0, forwarded.get());
            if (declared) {
                DataBufferUtils.release(buffer);
            } // 提前拒绝时server拥有未订阅body。
        } else {
            assertEquals(1, forwarded.get());
        }
        assertEquals(0, buffer.getNativeBuffer().refCnt());
    }

    @Test
    void cancellingPartialBodyReleasesPooledBuffers() {
        var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        var buffer =
                factory.allocateBuffer(SMALL_TEST_BODY_BYTES)
                        .write(new byte[SMALL_TEST_BODY_BYTES]);
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/auth/login")
                                .body(Flux.concat(Flux.just(buffer), Flux.never())));
        var subscription =
                new RequestBodyLimitFilter().filter(exchange, ignored -> Mono.empty()).subscribe();
        subscription.dispose();
        assertEquals(0, buffer.getNativeBuffer().refCnt());
    }

    @Test
    void rejectsExcessConcurrentReceiversAndCancellationRestoresCapacity() {
        var filter = new RequestBodyLimitFilter();
        java.util.List<reactor.core.Disposable> pending = new java.util.ArrayList<>();
        for (int i = 0; i < MAX_CONCURRENT_RECEIVERS; i++) {
            var exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.post("/auth/login").body(Flux.never()));
            pending.add(filter.filter(exchange, ignored -> Mono.empty()).subscribe());
        }
        var excess =
                MockServerWebExchange.from(MockServerHttpRequest.post("/auth/login").body("{}"));
        filter.filter(excess, ignored -> Mono.error(new AssertionError())).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, excess.getResponse().getStatusCode());
        pending.forEach(reactor.core.Disposable::dispose);
        var recovered =
                MockServerWebExchange.from(MockServerHttpRequest.post("/auth/login").body("{}"));
        filter.filter(recovered, ignored -> Mono.empty()).block();
        assertNull(recovered.getResponse().getStatusCode());
    }
}
