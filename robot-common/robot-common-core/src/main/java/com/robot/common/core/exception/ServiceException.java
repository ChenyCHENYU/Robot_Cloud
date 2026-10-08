package com.robot.common.core.exception;

import com.robot.common.core.domain.ResultCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常：Service 层业务规则不满足时抛出，由全局异常处理器统一转 Result。
 *
 * <p>使用约束（wl-skills-bd standards/08）：
 * <ul>
 *   <li>禁止用异常做流程控制；可预期业务校验优先使用 {@link ServiceAssert}；</li>
 *   <li>message 面向终端用户展示，禁止携带堆栈、SQL、内部路径等敏感信息。</li>
 * </ul>
 */
@Getter
public class ServiceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int code;

    public ServiceException(String message) {
        super(message);
        this.code = ResultCode.FAILED.getCode();
    }

    public ServiceException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public ServiceException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public ServiceException(int code, String message) {
        super(message);
        this.code = code;
    }
}
