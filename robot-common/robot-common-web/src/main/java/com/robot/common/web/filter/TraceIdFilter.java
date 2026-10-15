package com.robot.common.web.filter;

import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.util.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** TraceId 过滤器：沿用格式合法的 X-Trace-Id，缺失或非法时生成； 写入 MDC（日志模板 %X{traceId}）并在响应头回传，请求结束清理防串号。 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = TraceIds.resolve(request.getHeader(RobotConstants.HEADER_TRACE_ID));
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(RobotConstants.HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }
}
