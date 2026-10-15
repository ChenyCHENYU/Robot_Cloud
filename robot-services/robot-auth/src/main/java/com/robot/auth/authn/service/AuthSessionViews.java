package com.robot.auth.authn.service;

import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.auth.authn.dto.AuthContextVO;
import com.robot.auth.authn.dto.LoginResponse;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.context.LoginUser;
import java.util.List;

/**
 * Pure assembly from verified account/member facts, separate from Redis state transitions and
 * cleanup.
 */
final class AuthSessionViews {
    private AuthSessionViews() {}

    static LoginResponse response(
            AuthAccessTokens.IssuedAccess access,
            String refreshToken,
            RemoteUserDTO user,
            RemoteUserContextDTO context,
            List<RemoteUserContextDTO> allContexts) {
        return LoginResponse.builder()
                .token(access.token)
                .refreshToken(refreshToken)
                .expiresIn(access.expiresIn)
                .availableContexts(
                        allContexts.stream().map(c -> AuthContextVO.fromRemote(c, true)).toList())
                .activeContext(AuthContextVO.fromRemote(context, true))
                .user(
                        LoginResponse.UserVO.builder()
                                .id(user.getId())
                                .username(user.getUsername())
                                .displayName(user.getNickname())
                                .build())
                .build();
    }

    static LoginUser loginUser(RemoteUserContextDTO context, RemoteUserDTO user) {
        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getNickname())
                .tenantId(context.getTenantId())
                .companyId(context.getCompanyId())
                .contextId(context.getContextId())
                .userSecurityVersion(user.getSecurityVersion())
                .contextSecurityVersion(context.getSecurityVersion())
                .roleCodes(
                        context.getRoles() == null
                                ? List.of()
                                : context.getRoles().stream()
                                        .map(RemoteUserContextDTO.RoleItem::getCode)
                                        .toList())
                .superAdmin(
                        context.getRoles() != null
                                && context.getRoles().stream()
                                        .anyMatch(
                                                r ->
                                                        RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(
                                                                r.getCode())))
                .build();
    }

    static boolean validSecurityVersion(Long version) {
        return version != null && version >= 0;
    }

    static void requireSecurityVersion(Long version) {
        ServiceAssert.isTrue(validSecurityVersion(version), ResultCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    static String credentialFingerprint(RemoteUserDTO user) {
        ServiceAssert.notBlank(user.getPasswordHash(), ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        return AuthTokenSecrets.digest(user.getPasswordHash());
    }
}
