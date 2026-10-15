package com.robot.common.security.filter;

import com.robot.common.security.context.InnerTrustContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** 包围整个 Servlet 调度，覆盖鉴权拒绝、异常、无 handler 和异步调度的线程清理。 */
public final class InnerTrustContextFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        InnerTrustContext.clear();
        try {
            chain.doFilter(request, response);
        } finally {
            InnerTrustContext.clear();
        }
    }

    @Override
    protected void doFilterNestedErrorDispatch(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }
}
