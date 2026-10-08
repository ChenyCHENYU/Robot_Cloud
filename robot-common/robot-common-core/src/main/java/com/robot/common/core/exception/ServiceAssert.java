package com.robot.common.core.exception;

import com.robot.common.core.domain.ResultCode;

import java.util.function.Supplier;

/**
 * 业务前置断言：把「查询 + 判空 + 抛错」收敛为一行，替代散落的 if-throw。
 *
 * <pre>{@code
 * SysUser user = ServiceAssert.notNull(
 *         userMapper.selectById(id), ResultCode.USER_NOT_FOUND,
 *         () -> "用户不存在：" + id);
 * }</pre>
 */
public final class ServiceAssert {

    private ServiceAssert() {
    }

    public static void isTrue(boolean expression, ResultCode resultCode) {
        if (!expression) {
            throw new ServiceException(resultCode);
        }
    }

    public static void isTrue(boolean expression, ResultCode resultCode, String message) {
        if (!expression) {
            throw new ServiceException(resultCode, message);
        }
    }

    public static <T> T notNull(T object, ResultCode resultCode) {
        if (object == null) {
            throw new ServiceException(resultCode);
        }
        return object;
    }

    public static <T> T notNull(T object, ResultCode resultCode, Supplier<String> message) {
        if (object == null) {
            throw new ServiceException(resultCode, message.get());
        }
        return object;
    }

    public static <T> T notNull(T object, ResultCode resultCode, String message) {
        if (object == null) {
            throw new ServiceException(resultCode, message);
        }
        return object;
    }

    public static void noNullElement(Object[] array, ResultCode resultCode) {
        if (array == null) {
            throw new ServiceException(resultCode);
        }
        for (Object element : array) {
            if (element == null) {
                throw new ServiceException(resultCode);
            }
        }
    }

    public static void notBlank(String text, ResultCode resultCode) {
        if (text == null || text.isBlank()) {
            throw new ServiceException(resultCode);
        }
    }

    public static void notBlank(String text, ResultCode resultCode, String message) {
        if (text == null || text.isBlank()) {
            throw new ServiceException(resultCode, message);
        }
    }
}
