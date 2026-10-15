package com.robot.common.security.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 认证/授权异常处理：优先级高于全局兜底处理器。 401/403 保持 HTTP 状态码与业务码一致，便于网关与前端统一识别。 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class NotLoginExceptionHandler {

    private String traceId() {
        return MDC.get("traceId");
    }

    /**
     * Convert failed session authority to a fixed HTTP401 envelope without echoing exception data.
     *
     * @param e rejected Sa-Token session
     * @return fixed unauthenticated response
     */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleNotLogin(NotLoginException e) {
        return Result.failed(
                ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage(), traceId());
    }

    /**
     * Convert permission/role/internal trust rejection to a fixed HTTP403 response.
     *
     * @param e rejected authorization
     * @return fixed forbidden response
     */
    @ExceptionHandler({
        NotPermissionException.class,
        NotRoleException.class,
        com.robot.common.security.exception.InnerAccessDeniedException.class
    })
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleNoPermission(Exception e) {
        return Result.failed(ResultCode.FORBIDDEN, ResultCode.FORBIDDEN.getMessage(), traceId());
    }
}
