package com.robot.common.security.session;

import com.robot.common.security.context.LoginUser;

/** 由账号事实属主提供：验证账号、成员、公司及登录时凭据指纹仍然有效。 */
public interface SessionSubjectVerifier {

    boolean verify(LoginUser user, String credentialFingerprint);
}
