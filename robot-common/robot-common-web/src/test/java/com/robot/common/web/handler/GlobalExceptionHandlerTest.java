package com.robot.common.web.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/** 全局异常处理器契约测试：语义化错误码映射 + traceId 透出 + 不泄露堆栈。 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = new MockHttpServletRequest("POST", "/sys/users");

    @BeforeEach
    void setUp() {
        MDC.put("traceId", "trace-test-001");
    }

    @Test
    @DisplayName("业务异常：码与消息透传，traceId 透出")
    void serviceExceptionShouldCarryCodeAndTraceId() {
        Result<Void> result =
                handler.handleServiceException(
                        new ServiceException(ResultCode.USER_USERNAME_DUPLICATED), request);

        assertEquals(ResultCode.USER_USERNAME_DUPLICATED.getCode(), result.getCode());
        assertEquals(ResultCode.USER_USERNAME_DUPLICATED.getMessage(), result.getMessage());
        assertEquals("trace-test-001", result.getTraceId());
    }

    @Test
    @DisplayName("唯一键冲突（并发竞态兜底）：映射 40901")
    void duplicateKeyShouldMapToSemanticCode() {
        Result<Void> result =
                handler.handleDuplicateKey(
                        new DuplicateKeyException("uk_sys_user_username"), request);

        assertEquals(ResultCode.DATA_DUPLICATED.getCode(), result.getCode());
        assertNotNull(result.getTraceId());
    }

    @Test
    @DisplayName("乐观锁冲突（REVISION 不一致）：映射 40902")
    void optimisticLockShouldMapToSemanticCode() {
        Result<Void> result =
                handler.handleOptimisticLock(
                        new OptimisticLockingFailureException("revision mismatch"), request);

        assertEquals(ResultCode.DATA_CONCURRENT_CONFLICT.getCode(), result.getCode());
        assertNotNull(result.getTraceId());
    }

    @Test
    @DisplayName("@Valid 校验失败：字段 + 消息拼接，不泄露内部路径")
    void validationFailureShouldListFieldDetails() {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "username", "不能为空"));
        Result<Void> result =
                handler.handleMethodArgumentNotValid(
                        new MethodArgumentNotValidException(null, bindingResult));

        assertEquals(ResultCode.PARAM_INVALID.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("username"));
        assertTrue(result.getMessage().contains("不能为空"));
    }

    @Test
    @DisplayName("兜底异常：统一话术，不含堆栈与类名")
    void unexpectedShouldNotLeakStack() {
        Result<Void> result =
                handler.handleUnexpected(
                        new RuntimeException("select * from secret_table"), request);

        assertEquals(ResultCode.FAILED.getCode(), result.getCode());
        assertEquals(ResultCode.FAILED.getMessage(), result.getMessage());
        assertTrue(result.getMessage() == null || !result.getMessage().contains("secret_table"));
    }
}
