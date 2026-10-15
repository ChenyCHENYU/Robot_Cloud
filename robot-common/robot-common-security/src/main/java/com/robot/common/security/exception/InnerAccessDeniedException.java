package com.robot.common.security.exception;

/** 内部服务身份或委托身份不符合当前操作的边界。 */
public class InnerAccessDeniedException extends RuntimeException {
    /** Create a fixed rejection without attaching an internal identity, header or secret. */
    public InnerAccessDeniedException() {
        super("内部接口调用未授权");
    }
}
