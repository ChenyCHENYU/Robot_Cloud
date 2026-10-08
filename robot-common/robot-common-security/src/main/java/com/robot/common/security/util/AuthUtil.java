package com.robot.common.security.util;

import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.context.LoginUser;

/**
 * 登录上下文读取工具（对齐 wl-skills-bd「租户只来自 AuthUtil」的不变式）。
 *
 * <p>所有需要 {@code COMPANY_ID} 的业务代码必须经 {@link #getLoginCompanyId()} 取值，
 * 禁止从请求参数 / DTO 读取租户；未登录场景返回 null，由租户拦截器决定放行或拦截。
 */
public final class AuthUtil {

    private AuthUtil() {
    }

    /** 当前登录用户完整上下文（未登录返回 null）。 */
    public static LoginUser getLoginUser() {
        Object loginId = AuthHolder.getLoginId();
        if (loginId == null) {
            return null;
        }
        return AuthHolder.getSessionAttr(AuthHolder.SESSION_LOGIN_USER, LoginUser.class);
    }

    /** 当前登录用户 ID（未登录返回 null）。 */
    public static String getLoginUserId() {
        LoginUser user = getLoginUser();
        return user == null ? null : user.getUserId();
    }

    /** 当前登录用户名（未登录返回 null）。 */
    public static String getLoginUsername() {
        LoginUser user = getLoginUser();
        return user == null ? null : user.getUsername();
    }

    /** 当前租户（公司）ID：唯一合法租户来源（未登录返回 null）。 */
    public static String getLoginCompanyId() {
        LoginUser user = getLoginUser();
        return user == null ? null : user.getCompanyId();
    }

    /** 当前成员上下文 ID。 */
    public static String getLoginContextId() {
        LoginUser user = getLoginUser();
        return user == null ? null : user.getContextId();
    }

    /** 是否超级管理员。 */
    public static boolean isSuperAdmin() {
        LoginUser user = getLoginUser();
        if (user == null) {
            return false;
        }
        return user.isSuperAdmin()
                || (user.getRoleCodes() != null
                && user.getRoleCodes().contains(RobotConstants.SUPER_ADMIN_ROLE_CODE));
    }
}
