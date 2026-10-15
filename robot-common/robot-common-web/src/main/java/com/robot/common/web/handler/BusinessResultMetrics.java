package com.robot.common.web.handler;

import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Result语义独立于HTTP200；标签仅来自固定分类，不使用用户名、路径、事件或错误消息。 */
@RestControllerAdvice
public final class BusinessResultMetrics implements ResponseBodyAdvice<Object> {
    private final MeterRegistry registry;
    private static final int AUTH_CODE_START = 40100;
    private static final int AUTH_CODE_END = 40200;
    private static final int CONFLICT_CODE_START = 40900;
    private static final int CONFLICT_CODE_END = 41000;
    private static final java.util.Map<Integer, String> EXACT_OUTCOMES =
            java.util.Map.of(
                    ResultCode.SUCCESS.getCode(), "success",
                    ResultCode.TOO_MANY_REQUESTS.getCode(), "throttled",
                    ResultCode.UNAUTHORIZED.getCode(), "authentication",
                    ResultCode.FORBIDDEN.getCode(), "authorization",
                    ResultCode.PARAM_INVALID.getCode(), "validation",
                    ResultCode.USER_PASSWORD_WEAK.getCode(), "validation",
                    ResultCode.FAILED.getCode(), "dependency");

    /**
     * Counts semantic outcomes in the application-owned meter registry.
     *
     * @param registry shared Spring-managed registry; copying it would split the metrics stream
     */
    public BusinessResultMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean supports(
            MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter type,
            MediaType media,
            Class<? extends HttpMessageConverter<?>> converter,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof Result<?> result) {
            registry.counter("robot.http.business.results", "outcome", category(result.getCode()))
                    .increment();
        }
        return body;
    }

    private static String category(int code) {
        String exact = EXACT_OUTCOMES.get(code);
        if (exact != null) {
            return exact;
        }
        if (code >= AUTH_CODE_START && code < AUTH_CODE_END) {
            return "authentication";
        }
        if (code >= CONFLICT_CODE_START && code < CONFLICT_CODE_END) {
            return "conflict";
        }
        return "other";
    }
}
