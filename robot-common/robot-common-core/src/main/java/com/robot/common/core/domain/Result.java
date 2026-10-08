package com.robot.common.core.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.io.Serial;

/**
 * 统一响应包装。
 *
 * <p>契约（与 Robot_Admin 前端对齐，前端读取 {@code message ?? msg}）：
 * <pre>{@code
 * {
 *   "code": 0,          // 0=成功；非 0 见 ResultCode
 *   "message": "成功",   // 标准字段
 *   "msg": "成功",       // 前端旧契约兼容字段，与 message 同值
 *   "data": { ... },
 *   "traceId": "..."     // 出现异常时必带，便于排查
 * }
 * }</pre>
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务状态码：0 成功，非 0 失败。 */
    private final int code;

    /** 提示信息（标准字段）。 */
    private final String message;

    /** 业务数据。 */
    private final T data;

    /** 链路追踪 ID（异常场景必返）。 */
    private final String traceId;

    private Result(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data, null);
    }

    public static <T> Result<T> failed(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null, null);
    }

    public static <T> Result<T> failed(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null, null);
    }

    public static <T> Result<T> failed(int code, String message) {
        return new Result<>(code, message, null, null);
    }

    public static <T> Result<T> failed(ResultCode resultCode, String message, String traceId) {
        return new Result<>(resultCode.getCode(), message, null, traceId);
    }

    public static <T> Result<T> failed(int code, String message, String traceId) {
        return new Result<>(code, message, null, traceId);
    }

    /** 是否成功。 */
    public boolean successValue() {
        return this.code == ResultCode.SUCCESS.getCode();
    }

    /** 前端旧契约兼容字段：与 message 同值（勿删，Robot_Admin generated 类型读取 msg）。 */
    @JsonProperty("msg")
    public String getMsg() {
        return message;
    }
}
