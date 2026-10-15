package com.robot.common.security.session;

import com.robot.common.security.context.LoginUser;

/** 由账号事实属主提供：验证账号、成员、公司及登录时凭据指纹仍然有效。 */
public interface SessionSubjectVerifier {

    /**
     * Revalidate current account/member facts independently of cached token state.
     *
     * @param user trusted current access snapshot
     * @param credentialFingerprint login-time credential digest
     * @return whether current source-of-truth facts still match
     */
    boolean verify(LoginUser user, String credentialFingerprint);
}
