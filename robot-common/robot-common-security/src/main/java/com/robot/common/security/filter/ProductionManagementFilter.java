package com.robot.common.security.filter;

import com.robot.common.core.util.ManagementEndpointPolicy;
import com.robot.common.core.util.ProductionConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/** 对服务直连的管理和文档入口应用生产边界。 */
public final class ProductionManagementFilter extends OncePerRequestFilter {
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
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!ProductionConfiguration.protectedEnvironment(
                environment.getActiveProfiles(), environment::getProperty)) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (ManagementEndpointPolicy.documentation(path)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (ManagementEndpointPolicy.protectedManagement(path)) {
            var headers = Collections.list(request.getHeaders("X-Robot-Management-Token"));
            String secret = environment.getProperty("robot.management.secret", "");
            if (!ManagementEndpointPolicy.authorized(headers, secret)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
