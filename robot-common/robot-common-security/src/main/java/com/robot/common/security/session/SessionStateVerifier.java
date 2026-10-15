package com.robot.common.security.session;

/** 已通过 Sa-Token 登录校验后的族状态验证，失败必须阻断业务请求。 */
public interface SessionStateVerifier {

    /** Revalidate current session authority and throw before the business handler if invalid. */
    void verify();
}
