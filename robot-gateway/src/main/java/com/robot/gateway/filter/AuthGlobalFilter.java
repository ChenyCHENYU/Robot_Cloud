package com.robot.gateway.filter;

import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.util.TraceIds;
import com.robot.gateway.properties.AuthProperties;
import com.robot.gateway.util.WebFluxResponseWriter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关全局过滤器：TraceId 注入 → 内部接口屏蔽 → 登录态校验。
 *
 * <p>登录态校验策略：网关侧只做「Token 存在性 + 格式」硬校验与白名单放行， 真实有效性（未过期 / 未顶替）由各服务的 Sa-Token 会话校验完成， 网关与服务共享 Redis
 * 会话，避免网关直连业务库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {
    private static final int FILTER_ORDER = -100;

    private final AuthProperties authProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. TraceId：格式合法才沿用，非法输入生成新标识；响应头回传便于关联排障
        String traceId =
                TraceIds.resolve(request.getHeaders().getFirst(RobotConstants.HEADER_TRACE_ID));
        exchange.getResponse().getHeaders().set(RobotConstants.HEADER_TRACE_ID, traceId);
        // 单次 mutate：写入 TraceId + 清洗客户端伪造的内部调用与委托头（RC07）
        ServerHttpRequest mutated =
                request.mutate()
                        .header(RobotConstants.HEADER_TRACE_ID, traceId)
                        .headers(
                                headers -> {
                                    for (String name :
                                            new java.util.ArrayList<>(headers.headerNames())) {
                                        if (name.toLowerCase(Locale.ROOT).startsWith("x-inner-")) {
                                            headers.remove(name);
                                        }
                                    }
                                })
                        .build();

        // 2. 内部接口屏蔽：/inner/** 只允许服务间调用，外部请求一律拒绝
        if (pathMatcher.match("/inner/**", path)) {
            return WebFluxResponseWriter.write(
                    exchange,
                    HttpStatus.FORBIDDEN,
                    Result.failed(ResultCode.FORBIDDEN, "内部接口禁止外部访问", traceId));
        }

        // 3. CORS 预检放行（OPTIONS 不携带凭据，交由 CORS 配置处理）
        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(exchange.mutate().request(mutated).build());
        }

        // 4. 登录态校验：白名单放行，其余必须携带 Bearer Token
        if (matchesWhiteList(path)) {
            return chain.filter(exchange.mutate().request(mutated).build());
        }
        String authorization = request.getHeaders().getFirst(RobotConstants.HEADER_AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(RobotConstants.TOKEN_PREFIX)) {
            return WebFluxResponseWriter.write(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    Result.failed(
                            ResultCode.UNAUTHORIZED,
                            ResultCode.UNAUTHORIZED.getMessage(),
                            traceId));
        }

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean matchesWhiteList(String path) {
        return authProperties.getWhitePaths().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }
}
