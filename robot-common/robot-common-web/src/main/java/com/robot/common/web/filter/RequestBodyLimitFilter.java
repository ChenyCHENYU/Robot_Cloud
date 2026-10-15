package com.robot.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/** 服务端JSON独立边界，读取 limit+1 字节即拒绝，先于Jackson和认证RPC执行。 */
public final class RequestBodyLimitFilter extends OncePerRequestFilter {
    private static final int AUTH_LIMIT = 64 * 1024;
    private static final int JSON_LIMIT = 10 * 1024 * 1024;
    private static final int MAX_RECEIVING_BODIES = 16;
    private final java.util.concurrent.Semaphore receiving =
            new java.util.concurrent.Semaphore(MAX_RECEIVING_BODIES);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return !request.getRequestURI().startsWith("/auth/")
                && (type == null || !(type.toLowerCase(java.util.Locale.ROOT).contains("json")));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int limit = request.getRequestURI().startsWith("/auth/") ? AUTH_LIMIT : JSON_LIMIT;
        if (request.getContentLengthLong() > limit) {
            reject(response);
            return;
        }
        if (!receiving.tryAcquire()) {
            int throttled = com.robot.common.core.domain.ResultCode.TOO_MANY_REQUESTS.getCode();
            reject(response, throttled, throttled, "请求过于频繁，请稍后重试");
            return;
        }
        try {
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) {
                reject(response);
                return;
            }
            chain.doFilter(new BufferedRequest(request, body), response);
        } finally {
            receiving.release();
        }
    }

    private static void reject(HttpServletResponse response) throws IOException {
        reject(
                response,
                HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                com.robot.common.core.domain.ResultCode.PARAM_INVALID.getCode(),
                "请求体超过允许大小");
    }

    private static void reject(HttpServletResponse response, int status, int code, String message)
            throws IOException {
        String trace = com.robot.common.core.util.TraceIds.resolve(org.slf4j.MDC.get("traceId"));
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("X-Trace-Id", trace);
        response.getWriter()
                .write(
                        "{\"code\":"
                                + code
                                + ",\"message\":\""
                                + message
                                + "\",\"msg\":\""
                                + message
                                + "\",\"traceId\":\""
                                + trace
                                + "\"}");
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new BufferedServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class BufferedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        private BufferedServletInputStream(byte[] body) {
            input = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            java.util.Objects.requireNonNull(listener, "read listener");
            try {
                if (!isFinished()) {
                    listener.onDataAvailable();
                }
                if (isFinished()) {
                    listener.onAllDataRead();
                }
            } catch (IOException error) {
                listener.onError(error);
            }
        }
    }
}
