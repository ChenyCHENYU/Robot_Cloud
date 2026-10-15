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

    private ServiceAssert() {}

    /**
     * Requires a business precondition.
     *
     * @param expression precondition result
     * @param resultCode public failure classification
     * @throws ServiceException when the precondition fails
     */
    public static void isTrue(boolean expression, ResultCode resultCode) {
        if (!expression) {
            throw new ServiceException(resultCode);
        }
    }

    /**
     * Requires a precondition with a safe public explanation.
     *
     * @param expression precondition result
     * @param resultCode public failure classification
     * @param message safe public explanation
     * @throws ServiceException when the precondition fails
     */
    public static void isTrue(boolean expression, ResultCode resultCode, String message) {
        if (!expression) {
            throw new ServiceException(resultCode, message);
        }
    }

    /**
     * Requires a lookup to produce a business object.
     *
     * @param <T> object type
     * @param object lookup result
     * @param resultCode classification for a missing result
     * @return non-null lookup result
     * @throws ServiceException when the precondition fails
     */
    public static <T> T notNull(T object, ResultCode resultCode) {
        if (object == null) {
            throw new ServiceException(resultCode);
        }
        return object;
    }

    /**
     * Requires a lookup result, computing its failure message only when needed.
     *
     * @param <T> object type
     * @param object lookup result
     * @param resultCode classification for a missing result
     * @param message lazy supplier of a safe public explanation
     * @return non-null lookup result
     * @throws ServiceException when the precondition fails
     */
    public static <T> T notNull(T object, ResultCode resultCode, Supplier<String> message) {
        if (object == null) {
            throw new ServiceException(resultCode, message.get());
        }
        return object;
    }

    /**
     * Requires a lookup result with a safe public explanation.
     *
     * @param <T> object type
     * @param object lookup result
     * @param resultCode classification for a missing result
     * @param message safe public explanation
     * @return non-null lookup result
     * @throws ServiceException when the precondition fails
     */
    public static <T> T notNull(T object, ResultCode resultCode, String message) {
        if (object == null) {
            throw new ServiceException(resultCode, message);
        }
        return object;
    }

    /**
     * Requires an array and all its elements to be present.
     *
     * @param array candidate elements
     * @param resultCode classification for a null array or element
     * @throws ServiceException when the precondition fails
     */
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

    /**
     * Requires non-null text with at least one non-whitespace character.
     *
     * @param text candidate text
     * @param resultCode public failure classification
     * @throws ServiceException when the precondition fails
     */
    public static void notBlank(String text, ResultCode resultCode) {
        if (text == null || text.isBlank()) {
            throw new ServiceException(resultCode);
        }
    }

    /**
     * Requires nonblank text with a safe public explanation.
     *
     * @param text candidate text
     * @param resultCode public failure classification
     * @param message safe public explanation
     * @throws ServiceException when the precondition fails
     */
    public static void notBlank(String text, ResultCode resultCode, String message) {
        if (text == null || text.isBlank()) {
            throw new ServiceException(resultCode, message);
        }
    }
}
