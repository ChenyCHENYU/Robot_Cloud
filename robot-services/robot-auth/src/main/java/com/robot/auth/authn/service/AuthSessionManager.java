package com.robot.auth.authn.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.dto.AuthContextVO;
import com.robot.auth.authn.dto.LoginResponse;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.context.LoginUser;
import com.robot.common.security.util.AuthHolder;
import com.robot.common.security.util.AuthUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

/**
 * 会话生命周期管理：签发 / 轮换 / 注销 / 登录打点，收敛于一个组件。
 *
 * <p>职责边界：{@code AuthService} 只做流程编排与凭据校验决策，
 * 本组件负责所有「产生或销毁会话」的副作用：
 * <ul>
 *   <li>access token：Sa-Token 登录（30 分钟会话，网关/服务共享 Redis）；</li>
 *   <li>refresh token：7 天一次性轮换，payload 携带旧 access token 供轮换作废；</li>
 *   <li>LoginUser 会话上下文（角色由认证服务授予，客户端不信任）；</li>
 *   <li>登录日志打点（尽力而为，失败静默不影响认证主流程）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthSessionManager {

    /** 会话内登记的刷新令牌属性名（登出连带作废）。 */
    static final String SESSION_REFRESH_TOKEN = "robot:session:refresh-token";

    private final RemoteSystemService remoteSystemService;
    private final RedisHelper redisHelper;

    /** 签发完整会话（登录/激活/切换/刷新共用）。 */
    public LoginResponse issue(String loginType, RemoteUserDTO user,
                               RemoteUserContextDTO context,
                               List<RemoteUserContextDTO> allContexts) {
        StpUtil.login(user.getId());
        AuthHolder.setSessionAttr(AuthHolder.SESSION_LOGIN_USER, buildLoginUser(context, user));

        String refreshToken = IdUtil.fastSimpleUUID() + IdUtil.fastSimpleUUID();
        redisHelper.setString(AuthKeys.refreshToken(refreshToken),
                user.getId() + ":" + context.getContextId() + ":" + StpUtil.getTokenValue(),
                AuthKeys.TTL_REFRESH_TOKEN);
        AuthHolder.setSessionAttr(SESSION_REFRESH_TOKEN, refreshToken);

        recordLoginLog(loginType, user.getId(), user.getUsername(), context.getCompanyId(), 1);

        return LoginResponse.builder()
                .token(StpUtil.getTokenValue())
                .refreshToken(refreshToken)
                .expiresIn(StpUtil.getTokenTimeout())
                .availableContexts(allContexts.stream()
                        .map(c -> AuthContextVO.fromRemote(c, true)).toList())
                .activeContext(AuthContextVO.fromRemote(context, true))
                .user(LoginResponse.UserVO.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .displayName(user.getNickname())
                        .build())
                .build();
    }

    /** 注销当前会话：连带作废会话内登记的刷新令牌（防登出后凭旧 refresh 复活）。 */
    public void logoutCurrent() {
        String boundRefresh = AuthHolder.getSessionAttr(SESSION_REFRESH_TOKEN, String.class);
        if (boundRefresh != null && !boundRefresh.isBlank()) {
            redisHelper.delete(AuthKeys.refreshToken(boundRefresh));
        }
        Object loginId = StpUtil.getLoginIdDefaultNull();
        recordLoginLog("logout", loginId == null ? null : String.valueOf(loginId),
                AuthUtil.getLoginUsername(), AuthUtil.getLoginCompanyId(), 1);
        StpUtil.logout();
    }

    /** 轮换：作废旧刷新令牌 + 旧访问令牌立即失效（防双活会话与重放）。payload 形如 userId:contextId:accessToken。 */
    public void rotate(String oldRefreshToken, String payload) {
        redisHelper.delete(AuthKeys.refreshToken(oldRefreshToken));
        String[] parts = payload.split(":");
        if (parts.length > 2 && !parts[2].isBlank()) {
            StpUtil.logoutByTokenValue(parts[2]);
        }
    }

    /** 读取刷新令牌载荷（userId:contextId:accessToken）。 */
    public Optional<String> payloadOf(String refreshToken) {
        return redisHelper.getString(AuthKeys.refreshToken(refreshToken));
    }

    private LoginUser buildLoginUser(RemoteUserContextDTO context, RemoteUserDTO user) {
        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getNickname())
                .tenantId(context.getTenantId())
                .companyId(context.getCompanyId())
                .contextId(context.getContextId())
                .roleCodes(context.getRoles() == null ? List.of()
                        : context.getRoles().stream()
                        .map(RemoteUserContextDTO.RoleItem::getCode).toList())
                .superAdmin(context.getRoles() != null && context.getRoles().stream()
                        .anyMatch(r -> RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(r.getCode())))
                .build();
    }

    /** 登录日志打点（尽力而为：IP/UA 取当前请求，失败静默不影响认证主流程）。 */
    private void recordLoginLog(String loginType, String userId, String username,
                                String companyId, int status) {
        try {
            String ip = null;
            String userAgent = null;
            if (RequestContextHolder.getRequestAttributes()
                    instanceof ServletRequestAttributes attributes) {
                ip = attributes.getRequest().getHeader("X-Forwarded-For");
                if (ip == null || ip.isBlank()) {
                    ip = attributes.getRequest().getRemoteAddr();
                }
                userAgent = attributes.getRequest().getHeader("User-Agent");
            }
            remoteSystemService.recordLogin(RemoteLoginLogDTO.builder()
                    .userId(userId)
                    .username(username)
                    .companyId(companyId)
                    .loginType(loginType)
                    .status(status)
                    .ip(ip)
                    .userAgent(userAgent)
                    .build());
        } catch (Exception e) {
            log.debug("login log dropped: {}", e.getMessage());
        }
    }
}
