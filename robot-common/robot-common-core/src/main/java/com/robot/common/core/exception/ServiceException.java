package com.robot.common.core.exception;

import com.robot.common.core.domain.ResultCode;
import java.io.Serial;
import lombok.Getter;

/**
 * 业务异常：Service 层业务规则不满足时抛出，由全局异常处理器统一转 Result。
 *
 * <p>使用约束（wl-skills-bd standards/08）：
 *
 * <ul>
 *   <li>禁止用异常做流程控制；可预期业务校验优先使用 {@link ServiceAssert}；
 *   <li>message 面向终端用户展示，禁止携带堆栈、SQL、内部路径等敏感信息。
 * </ul>
 */
@Getter
public class ServiceException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final int code;

    /**
     * Creates an application failure for the public Result envelope.
     *
     * @param message safe public explanation
     */
    public ServiceException(String message) {
        super(message);
        this.code = ResultCode.FAILED.getCode();
    }

    /**
     * Creates an application failure for the public Result envelope.
     *
     * @param resultCode public code and default message
     */
    public ServiceException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    /**
     * Creates an application failure for the public Result envelope.
     *
     * @param resultCode public failure code
     * @param message safe public explanation
     */
    public ServiceException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    /**
     * Creates an application failure for the public Result envelope.
     *
     * @param code registered public failure code
     * @param message safe public explanation
     */
    public ServiceException(int code, String message) {
        super(message);
        this.code = code;
    }
}
