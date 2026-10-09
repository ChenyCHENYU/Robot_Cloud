package com.robot.common.web.handler;

import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
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

import java.util.stream.Collectors;

/**
 * 全局异常处理器：技术异常响应使用固定提示，日志只记录受控类别、业务码和 traceId。
 *
 * <p>traceId 从 MDC 读取并写入响应（wl-skills-bd standards/09：异常响应必带 traceId）。
 * 业务和字段校验消息仍遵守其面向用户的契约，不把拒绝值、下游异常或请求 URI 附加到日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private enum FailureCategory { BUSINESS, DUPLICATE_KEY, OPTIMISTIC_LOCK, UNEXPECTED }

    private String traceId() {
        return MDC.get("traceId");
    }

    /** 业务异常：预期内失败，warn 级别即可。 */
    @ExceptionHandler(ServiceException.class)
    public Result<Void> handleServiceException(ServiceException e, HttpServletRequest request) {
        log.warn("HTTP request rejected category={} code={} traceId={}",
                FailureCategory.BUSINESS, e.getCode(), traceId());
        return Result.failed(e.getCode(), e.getMessage(), traceId());
    }

    /** @Valid 请求体校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("；"));
        return Result.failed(ResultCode.PARAM_INVALID, detail, traceId());
    }

    /** 表单绑定校验失败（消息格式与 @Valid 校验一致）。 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String detail = e.getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("；"));
        return Result.failed(ResultCode.PARAM_INVALID, detail, traceId());
    }

    /** @Validated 单参数校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .sorted()
                .collect(Collectors.joining("；"));
        return Result.failed(ResultCode.PARAM_INVALID,
                detail.isEmpty() ? ResultCode.PARAM_INVALID.getMessage() : detail, traceId());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException e) {
        return Result.failed(ResultCode.PARAM_INVALID, "缺少必要参数：" + e.getParameterName(), traceId());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.failed(ResultCode.PARAM_INVALID, "参数类型不匹配：" + e.getName(), traceId());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return Result.failed(ResultCode.PARAM_INVALID, "请求体解析失败", traceId());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.failed(ResultCode.PARAM_INVALID, "请求方式不支持", traceId());
    }

    /** 唯一键冲突：并发创建同键数据（前置检查的竞态兜底），语义化 40901。 */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(org.springframework.dao.DuplicateKeyException e,
                                           HttpServletRequest request) {
        log.warn("HTTP request rejected category={} traceId={}", FailureCategory.DUPLICATE_KEY, traceId());
        return Result.failed(ResultCode.DATA_DUPLICATED,
                ResultCode.DATA_DUPLICATED.getMessage(), traceId());
    }

    /** 乐观锁冲突：REVISION 不一致（数据已被他人修改），语义化 40902。 */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public Result<Void> handleOptimisticLock(
            org.springframework.dao.OptimisticLockingFailureException e,
            HttpServletRequest request) {
        log.warn("HTTP request rejected category={} traceId={}", FailureCategory.OPTIMISTIC_LOCK, traceId());
        return Result.failed(ResultCode.DATA_CONCURRENT_CONFLICT,
                ResultCode.DATA_CONCURRENT_CONFLICT.getMessage(), traceId());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        return Result.failed(ResultCode.NOT_FOUND, "请求的资源不存在", traceId());
    }

    /** 兜底异常不展开 Throwable；固定错误类别与 traceId 用于关联排查。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("HTTP request failed category={} traceId={}", FailureCategory.UNEXPECTED, traceId());
        return Result.failed(ResultCode.FAILED, ResultCode.FAILED.getMessage(), traceId());
    }
}
