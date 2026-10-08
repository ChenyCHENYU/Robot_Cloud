package com.robot.auth.authn.service;

import cn.dev33.satoken.stp.StpUtil;
import com.robot.api.system.dto.RemoteDataScopeDTO;
import com.robot.api.system.dto.RemoteMenuRouteDTO;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.api.system.feign.RemoteSystemService;
import com.robot.auth.authn.dto.ActivateContextRequest;
import com.robot.auth.authn.dto.AuthContextVO;
import com.robot.auth.authn.dto.LoginCompaniesRequest;
import com.robot.auth.authn.dto.LoginCompaniesResponse;
import com.robot.auth.authn.dto.LoginRequest;
import com.robot.auth.authn.dto.LoginResponse;
import com.robot.auth.authn.dto.RefreshTokenResponse;
import com.robot.auth.authn.dto.SwitchContextRequest;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.redis.core.RedisHelper;
import com.robot.common.security.util.AuthUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 认证编排：登录 → 上下文发现/激活 → 会话签发（委托 {@link AuthSessionManager}）→ 双 Token 轮换。
 *
 * <p>安全设计（wl-skills-bd standards/11 + 前端契约注释）：
 * <ul>
 *   <li>角色只由本服务在激活后授予，客户端提交的角色/公司字段一律不信任；</li>
 *   <li>loginTicket 5 分钟一次性（激活即焚）；refreshToken 7 天轮换式（旧换新作废）；</li>
 *   <li>登录失败统一话术防账号枚举；login-companies 未知账号返回空清单。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final RemoteSystemService remoteSystemService;
    private final RedisHelper redisHelper;
    private final AuthSessionManager sessions;
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwordEncoder;

    /* ==================== 登录与上下文 ==================== */

    public LoginResponse login(LoginRequest request) {
        RemoteUserDTO user = loadActiveUser(request.getUsername());
        ServiceAssert.isTrue(passwordMatches(request.getPassword(), user.getPasswordHash()),
                ResultCode.AUTH_LOGIN_FAILED);

        List<RemoteUserContextDTO> contexts = loadContexts(user.getId());
        ServiceAssert.isTrue(!contexts.isEmpty(), ResultCode.AUTH_COMPANY_MISSING);

        // 请求显式选择上下文 → 校验成员关系后直接签发会话
        if (request.getContextId() != null && !request.getContextId().isBlank()) {
            return sessions.issue("login", user, selectContext(contexts, request.getContextId()),
                    contexts);
        }
        // 唯一上下文 → 直接签发
        if (contexts.size() == 1) {
            return sessions.issue("login", user, contexts.get(0), contexts);
        }
        // 多上下文未选择 → 返回一次性登录票据，等待激活
        return pendingActivation(user, contexts);
    }

    public LoginCompaniesResponse getLoginCompanies(LoginCompaniesRequest request) {
        try {
            RemoteUserDTO user = loadActiveUser(request.getUsername());
            return new LoginCompaniesResponse(loadContexts(user.getId()).stream()
                    .map(AuthService::toLoginCompany).toList());
        } catch (ServiceException e) {
            // 契约要求：未知/停用账号返回空清单，不暴露账号是否存在
            if (e.getCode() == ResultCode.AUTH_LOGIN_FAILED.getCode()
                    || e.getCode() == ResultCode.AUTH_ACCOUNT_DISABLED.getCode()) {
                return new LoginCompaniesResponse(List.of());
            }
            throw e;
        }
    }

    public LoginResponse activateContext(ActivateContextRequest request) {
        String ticketKey = AuthKeys.loginTicket(request.getLoginTicket());
        Optional<String> userIdOpt = redisHelper.getString(ticketKey);
        ServiceAssert.isTrue(userIdOpt.isPresent(), ResultCode.AUTH_LOGIN_TICKET_INVALID);
        // 激活即焚：一次性票据
        redisHelper.delete(ticketKey);

        String userId = userIdOpt.get();
        RemoteUserDTO user = loadActiveUserById(userId);
        List<RemoteUserContextDTO> contexts = loadContexts(userId);
        return sessions.issue("activate", user,
                selectContext(contexts, request.getContextId()), contexts);
    }

    public LoginResponse switchContext(SwitchContextRequest request) {
        String userId = StpUtil.getLoginIdAsString();
        RemoteUserDTO user = loadActiveUserById(userId);
        List<RemoteUserContextDTO> contexts = loadContexts(userId);
        return sessions.issue("switch", user,
                selectContext(contexts, request.getContextId()), contexts);
    }

    /* ==================== 双 Token 轮换 ==================== */

    public RefreshTokenResponse refreshToken(String refreshToken) {
        Optional<String> payload = sessions.payloadOf(refreshToken);
        ServiceAssert.isTrue(payload.isPresent(), ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        // 轮换：旧刷新令牌即焚 + 旧访问令牌立即失效
        sessions.rotate(refreshToken, payload.get());

        String[] parts = payload.get().split(":");
        RemoteUserDTO user = loadActiveUserById(parts[0]);
        List<RemoteUserContextDTO> contexts = loadContexts(parts[0]);
        LoginResponse session = sessions.issue("refresh", user,
                selectContext(contexts, parts[1]), contexts);
        return RefreshTokenResponse.builder()
                .token(session.getToken())
                .refreshToken(session.getRefreshToken())
                .expiresIn(session.getExpiresIn())
                .build();
    }

    public void logout() {
        sessions.logoutCurrent();
    }

    /* ==================== 会话内聚合接口 ==================== */

    public List<RemoteMenuRouteDTO> getCurrentMenus() {
        return aggregate(remoteSystemService.getRoutesByUser(
                requireLoginUser().getUserId(), requireLoginUser().getCompanyId()));
    }

    public Map<String, List<String>> getCurrentButtons() {
        return aggregate(remoteSystemService.getButtonList(
                requireLoginUser().getUserId(), requireLoginUser().getCompanyId()));
    }

    public List<RemoteDataScopeDTO> getCurrentDataScopes() {
        return aggregate(remoteSystemService.getDataScopes(
                requireLoginUser().getUserId(), requireLoginUser().getCompanyId()));
    }

    /* ==================== 私有 ==================== */

    private boolean passwordMatches(String raw, String hash) {
        return passwordEncoder.matches(raw, hash);
    }

    private LoginResponse pendingActivation(RemoteUserDTO user,
                                            List<RemoteUserContextDTO> contexts) {
        String loginTicket = cn.hutool.core.util.IdUtil.fastSimpleUUID()
                + cn.hutool.core.util.IdUtil.fastSimpleUUID();
        redisHelper.setString(AuthKeys.loginTicket(loginTicket), user.getId(),
                AuthKeys.TTL_LOGIN_TICKET);
        return LoginResponse.builder()
                .token("")
                .refreshToken("")
                .expiresIn(0L)
                .loginTicket(loginTicket)
                .availableContexts(contexts.stream()
                        .map(c -> AuthContextVO.fromRemote(c, false)).toList())
                .build();
    }

    /** 从上下文清单精确选择（无权进入所选公司 → AUTH_CONTEXT_DENIED）。 */
    private RemoteUserContextDTO selectContext(List<RemoteUserContextDTO> contexts,
                                               String contextId) {
        return contexts.stream()
                .filter(c -> contextId.equals(c.getContextId()))
                .findFirst()
                .orElseThrow(() -> new ServiceException(ResultCode.AUTH_CONTEXT_DENIED));
    }

    private static LoginCompaniesResponse.LoginCompany toLoginCompany(
            RemoteUserContextDTO context) {
        return new LoginCompaniesResponse.LoginCompany(context.getContextId(),
                Boolean.TRUE.equals(context.getPrimaryFlag()),
                context.getTenantName(), context.getCompanyName());
    }

    private RemoteUserDTO loadActiveUser(String username) {
        Result<RemoteUserDTO> result = remoteSystemService.getUserByUsername(username);
        ServiceAssert.isTrue(result.successValue() && result.getData() != null,
                ResultCode.AUTH_LOGIN_FAILED);
        return assertActive(result.getData());
    }

    private RemoteUserDTO loadActiveUserById(String userId) {
        Result<RemoteUserDTO> result = remoteSystemService.getUserById(userId);
        ServiceAssert.isTrue(result.successValue() && result.getData() != null,
                ResultCode.AUTH_REFRESH_TOKEN_INVALID);
        return assertActive(result.getData());
    }

    private RemoteUserDTO assertActive(RemoteUserDTO user) {
        ServiceAssert.isTrue(user.getStatusFlag() != null && user.getStatusFlag() == 1,
                ResultCode.AUTH_ACCOUNT_DISABLED);
        return user;
    }

    private List<RemoteUserContextDTO> loadContexts(String userId) {
        Result<List<RemoteUserContextDTO>> result = remoteSystemService.getUserContexts(userId);
        ServiceAssert.isTrue(result.successValue(), ResultCode.FAILED, result.getMessage());
        return result.getData();
    }

    private com.robot.common.security.context.LoginUser requireLoginUser() {
        com.robot.common.security.context.LoginUser user = AuthUtil.getLoginUser();
        return ServiceAssert.notNull(user, ResultCode.UNAUTHORIZED);
    }

    private <T> T aggregate(Result<T> result) {
        ServiceAssert.isTrue(result.successValue(), ResultCode.FAILED, result.getMessage());
        return result.getData();
    }
}
