package com.robot.common.web.handler;

import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理器：技术异常响应使用固定提示，日志只记录受控类别、业务码和 traceId。
 *
 * <p>traceId 从 MDC 读取并写入响应（wl-skills-bd standards/09：异常响应必带 traceId）。
 * 业务和字段校验消息仍遵守其面向用户的契约，不把拒绝值、下游异常或请求 URI 附加到日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final int MAX_DIAGNOSTIC_CAUSES = 8;

    private enum FailureCategory {
        BUSINESS,
        DUPLICATE_KEY,
        OPTIMISTIC_LOCK,
        UNEXPECTED
    }

    private String traceId() {
        return MDC.get("traceId");
    }

    /**
     * Returns an expected business rejection without logging its sensitive payload.
     *
     * @param e failure classified by the framework handler mapping
     * @param request current request; its raw values are kept out of logs
     * @return safe public failure envelope
     */
    @ExceptionHandler(ServiceException.class)
    public Result<Void> handleServiceException(ServiceException e, HttpServletRequest request) {
        log.warn(
                "HTTP request rejected category={} code={} traceId={}",
                FailureCategory.BUSINESS,
                e.getCode(),
                traceId());
        return Result.failed(e.getCode(), e.getMessage(), traceId());
    }

    /**
     * Returns safe field-level validation messages for a JSON request body.
     *
     * @param e failure classified by the framework handler mapping
     * @return safe public failure envelope
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail =
                e.getBindingResult().getFieldErrors().stream()
                        .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                        .collect(Collectors.joining("；"));
        return Result.failed(ResultCode.PARAM_INVALID, detail, traceId());
    }

    /**
     * Returns safe field-level messages for query and form binding.
     *
     * @param e failure classified by the framework handler mapping
     * @return safe public failure envelope
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String detail =
                e.getFieldErrors().stream()
                        .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                        .collect(Collectors.joining("；"));
        return Result.failed(ResultCode.PARAM_INVALID, detail, traceId());
    }

    /**
     * Returns deterministically ordered scalar-parameter validation messages.
     *
     * @param e failure classified by the framework handler mapping
     * @return safe public failure envelope
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String detail =
                e.getConstraintViolations().stream()
                        .map(
                                violation ->
                                        violation.getPropertyPath() + " " + violation.getMessage())
                        .sorted()
                        .collect(Collectors.joining("；"));
        return Result.failed(
                ResultCode.PARAM_INVALID,
                detail.isEmpty() ? ResultCode.PARAM_INVALID.getMessage() : detail,
                traceId());
    }

    /**
     * Identifies a missing parameter without reflecting its raw value.
     *
     * @param e failure classified by the framework handler mapping
     * @return safe public failure envelope
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException e) {
        return Result.failed(ResultCode.PARAM_INVALID, "缺少必要参数：" + e.getParameterName(), traceId());
    }

    /**
     * Rejects an invalid parameter type without reflecting the supplied value.
     *
     * @param e failure classified by the framework handler mapping
     * @return safe public failure envelope
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.failed(ResultCode.PARAM_INVALID, "参数类型不匹配：" + e.getName(), traceId());
    }

    /**
     * Rejects an unreadable body using a fixed public explanation.
     *
     * @param e failure classified by the framework handler mapping
     * @return safe public failure envelope
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return Result.failed(ResultCode.PARAM_INVALID, "请求体解析失败", traceId());
    }

    /**
     * Rejects unsupported HTTP methods using the stable Result envelope.
     *
     * @param e failure classified by the framework handler mapping
     * @return safe public failure envelope
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.failed(ResultCode.PARAM_INVALID, "请求方式不支持", traceId());
    }

    /**
     * Maps a concurrent unique-key conflict to the public duplicate code.
     *
     * @param e failure classified by the framework handler mapping
     * @param request current request; its raw values are kept out of logs
     * @return safe public failure envelope
     */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(
            org.springframework.dao.DuplicateKeyException e, HttpServletRequest request) {
        log.warn(
                "HTTP request rejected category={} traceId={}",
                FailureCategory.DUPLICATE_KEY,
                traceId());
        return Result.failed(
                ResultCode.DATA_DUPLICATED, ResultCode.DATA_DUPLICATED.getMessage(), traceId());
    }

    /**
     * Maps a stale revision to the public concurrency-conflict code.
     *
     * @param e failure classified by the framework handler mapping
     * @param request current request; its raw values are kept out of logs
     * @return safe public failure envelope
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public Result<Void> handleOptimisticLock(
            org.springframework.dao.OptimisticLockingFailureException e,
            HttpServletRequest request) {
        log.warn(
                "HTTP request rejected category={} traceId={}",
                FailureCategory.OPTIMISTIC_LOCK,
                traceId());
        return Result.failed(
                ResultCode.DATA_CONCURRENT_CONFLICT,
                ResultCode.DATA_CONCURRENT_CONFLICT.getMessage(),
                traceId());
    }

    /**
     * Maps an unmatched handler to the public resource-not-found code.
     *
     * @param e failure classified by the framework handler mapping
     * @return safe public failure envelope
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        return Result.failed(ResultCode.NOT_FOUND, "请求的资源不存在", traceId());
    }

    /**
     * Reports an unexpected failure using fixed diagnostics and a correlation identifier.
     *
     * @param e failure classified by the framework handler mapping
     * @param request current request; its raw values are kept out of logs
     * @return safe public failure envelope
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error(
                "HTTP request failed category={} diagnostic={} traceId={}",
                FailureCategory.UNEXPECTED,
                diagnostic(e),
                traceId());
        return Result.failed(ResultCode.FAILED, ResultCode.FAILED.getMessage(), traceId());
    }

    /** 仅允许固定分类，不输出异常消息、类名、堆栈或请求内容。 */
    private static String diagnostic(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_DIAGNOSTIC_CAUSES; depth++) {
            if (current instanceof org.springframework.dao.DataAccessException) {
                return "DATABASE";
            }
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return "TIMEOUT";
            }
            if (current instanceof java.io.IOException) {
                return "IO";
            }
            if (current instanceof IllegalStateException) {
                return "STATE";
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return "OTHER";
    }
}
