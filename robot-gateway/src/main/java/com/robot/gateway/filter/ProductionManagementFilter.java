package com.robot.gateway.filter;

import com.robot.common.core.util.ManagementEndpointPolicy;
import com.robot.common.core.util.ProductionConfiguration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** WebFilter覆盖management handler，不能用转发链GlobalFilter代替。 */
@Component
public final class ProductionManagementFilter implements WebFilter, Ordered {
    private final Environment environment;

    /**
     * Bind the runtime environment without retaining request-specific state.
     *
     * @param environment trusted Spring configuration
     */
    public ProductionManagementFilter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!ProductionConfiguration.protectedEnvironment(
                environment.getActiveProfiles(), environment::getProperty)) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        if (ManagementEndpointPolicy.documentation(path)) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        if (ManagementEndpointPolicy.protectedManagement(path)) {
            var headers = exchange.getRequest().getHeaders().get("X-Robot-Management-Token");
            String secret = environment.getProperty("robot.management.secret", "");
            if (!ManagementEndpointPolicy.authorized(headers, secret)) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
