package com.robot.auth.authn.service;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 认证编排：登录 → 上下文发现/激活 → 会话签发（委托 {@link AuthSessionManager}）→ 双 Token 轮换。
 *
 * <p>安全设计（wl-skills-bd standards/11 + 前端契约注释）：
 * <ul>
 *   <li>角色只由本服务在激活后授予，客户端提交的角色/公司字段一律不信任；</li>
 *   <li>loginTicket 5 分钟一次性（激活即焚）；refreshToken 7 天轮换式（旧换新作废）；</li>
 *   <li>用户名/密码错误共用错误码；公司发现保留公开契约，未知/停用账号返回空清单。</li>
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
    private final ObjectMapper objectMapper;

    /* ==================== 登录与上下文 ==================== */

    /**
     * 校验密码并发现有效成员上下文；显式选择或唯一上下文直接登录，多上下文返回激活票据。
     *
     * @param request 用户名、密码与可选成员上下文 ID
     * @return 独立 access/refresh 会话，或尚未激活的单次登录票据
     * @throws ServiceException 凭据错误、账号停用、无有效成员或系统供给不可用
     */
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

    /**
     * 为登录页返回账号可选的公司上下文，沿用用户名查询的公开契约。
     *
     * @param request 待登录用户名
     * @return 启用账号的公司清单；未知或停用账号返回空清单
     * @throws ServiceException 系统供给暂不可用，不能以空清单掩盖故障
     */
    public LoginCompaniesResponse getLoginCompanies(LoginCompaniesRequest request) {
        try {
            RemoteUserDTO user = loadActiveUser(request.getUsername());
            return new LoginCompaniesResponse(loadContexts(user.getId()).stream()
                    .map(AuthService::toLoginCompany).toList());
        } catch (ServiceException e) {
            // 沿用公司发现契约：未知/停用账号均返回空清单。
            if (e.getCode() == ResultCode.AUTH_LOGIN_FAILED.getCode()
                    || e.getCode() == ResultCode.AUTH_ACCOUNT_DISABLED.getCode()) {
                return new LoginCompaniesResponse(List.of());
            }
            throw e;
        }
    }

    /**
     * 复验票据绑定的账号安全版本与凭据，再原子消费票据并激活所选成员。
     *
     * @param request 单次登录票据及其待激活的成员上下文 ID
     * @return 所选上下文的独立 access/refresh 会话
     * @throws ServiceException 票据失效、上下文无权进入或系统供给不可用；临时供给失败保留票据
     */
    public LoginResponse activateContext(ActivateContextRequest request) {
        ServiceAssert.isTrue(AuthTokenSecrets.validSecret(request.getLoginTicket()),
                ResultCode.AUTH_LOGIN_TICKET_INVALID);
        String ticketKey = AuthKeys.loginTicket(request.getLoginTicket());
        String encoded = redisHelper.getString(ticketKey)
                .orElseThrow(() -> new ServiceException(ResultCode.AUTH_LOGIN_TICKET_INVALID));
        TicketState ticket = decodeTicket(encoded);
        String userId = ticket.userId();
        // RPC/上下文选择失败不烧毁票据；最终原子取走的请求才可签发。
        RemoteUserDTO user = loadActiveUserById(userId);
        if (!ticket.userSecurityVersion().equals(user.getSecurityVersion())
                || user.getPasswordHash() == null
                || !ticket.credentialFingerprint().equals(AuthTokenSecrets.digest(user.getPasswordHash()))) {
            redisHelper.consumeString(ticketKey);
            throw new ServiceException(ResultCode.AUTH_LOGIN_TICKET_INVALID);
        }
        List<RemoteUserContextDTO> contexts = loadContexts(userId);
        RemoteUserContextDTO context = selectContext(contexts, request.getContextId());
        String consumed = redisHelper.consumeString(ticketKey)
                .orElseThrow(() -> new ServiceException(ResultCode.AUTH_LOGIN_TICKET_INVALID));
        ServiceAssert.isTrue(encoded.equals(consumed), ResultCode.AUTH_LOGIN_TICKET_INVALID);
        return sessions.issue("activate", user, context, contexts);
    }

    /**
     * 重新加载账号及全部有效成员，仅轮换当前 token 所属族到目标公司。
     *
     * @param request 目标成员上下文 ID
     * @return 当前设备轮换后的会话，其他设备的族保持独立
     * @throws ServiceException 原成员已撤销、目标成员不可用或供给失败
     */
    public LoginResponse switchContext(SwitchContextRequest request) {
        String userId = StpUtil.getLoginIdAsString();
        RemoteUserDTO user = loadActiveUserById(userId);
        List<RemoteUserContextDTO> contexts = loadContexts(userId);
        return sessions.switchCurrent(user,
                selectContext(contexts, request.getContextId()), contexts);
    }

    /* ==================== 双 Token 轮换 ==================== */

    /**
     * 在账号、成员、安全版本及凭据复验通过后，以族状态 CAS 轮换当前凭据。
     *
     * @param refreshToken 客户端持有的当前不透明刷新凭据
     * @return 新 access/refresh；旧凭据及竞争失败候选不能再使用
     * @throws ServiceException 凭据失效或系统供给失败；临时供给失败允许原凭据重试
     */
    public RefreshTokenResponse refreshToken(String refreshToken) {
        AuthSessionManager.RefreshSession pending = sessions.requireRefresh(refreshToken);
        LoginResponse session = refreshVerified(pending);
        return RefreshTokenResponse.builder()
                .token(session.getToken())
                .refreshToken(session.getRefreshToken())
                .expiresIn(session.getExpiresIn())
                .build();
    }

    private LoginResponse refreshVerified(AuthSessionManager.RefreshSession pending) {
        RemoteUserDTO user;
        List<RemoteUserContextDTO> contexts;
        RemoteUserContextDTO context;
        try {
            user = loadActiveUserById(pending.userId());
            contexts = loadContexts(pending.userId());
            context = selectContext(contexts, pending.contextId());
        } catch (ServiceException e) {
            if (e.getCode() == ResultCode.AUTH_ACCOUNT_DISABLED.getCode()
                    || e.getCode() == ResultCode.AUTH_CONTEXT_DENIED.getCode()
                    || e.getCode() == ResultCode.AUTH_REFRESH_TOKEN_INVALID.getCode()) {
                sessions.revoke(pending);
            }
            throw e;
        }
        // CAS 输家不得撤销已由其他请求提交的新版本刷新族。
        return sessions.refresh(pending, user, context, contexts);
    }

    /** 删除当前 Bearer 原文定位到的族；access 自然过期仍可注销，已注销请求保持幂等。 */
    public void logout() {
        sessions.logoutCurrent();
    }

    /* ==================== 会话内聚合接口 ==================== */

    /** 按当前 token Session 的账号/公司委托 System 读取菜单，供给失败直接阻断本次读取。 */
    public List<RemoteMenuRouteDTO> getCurrentMenus() {
        return aggregate(remoteSystemService.getRoutesByUser(
                requireLoginUser().getUserId(), requireLoginUser().getCompanyId()));
    }

    /** 按当前 token Session 的账号/公司读取显式按钮授权，返回页面编码到按钮编码的映射。 */
    public Map<String, List<String>> getCurrentButtons() {
        return aggregate(remoteSystemService.getButtonList(
                requireLoginUser().getUserId(), requireLoginUser().getCompanyId()));
    }

    /** 返回 System 根据当前账号/公司有效角色计算的数据范围，客户端角色快照不参与计算。 */
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
        ServiceAssert.isTrue(user.getSecurityVersion() != null && user.getSecurityVersion() >= 0,
                ResultCode.AUTH_LOGIN_TICKET_INVALID);
        ServiceAssert.notBlank(user.getPasswordHash(), ResultCode.AUTH_LOGIN_TICKET_INVALID);
        String loginTicket = AuthTokenSecrets.randomToken();
        try {
            String encoded = objectMapper.writeValueAsString(new TicketState(user.getId(),
                    AuthTokenSecrets.digest(user.getPasswordHash()), user.getSecurityVersion()));
            redisHelper.setStringExact(AuthKeys.loginTicket(loginTicket), encoded, AuthKeys.TTL_LOGIN_TICKET);
        } catch (JsonProcessingException e) {
            throw new ServiceException(ResultCode.FAILED, "登录票据创建失败");
        }
        return LoginResponse.builder()
                .token("")
                .refreshToken("")
                .expiresIn(0L)
                .loginTicket(loginTicket)
                .availableContexts(contexts.stream()
                        .map(c -> AuthContextVO.fromRemote(c, false)).toList())
                .build();
    }

    private TicketState decodeTicket(String encoded) {
        try {
            TicketState state = objectMapper.readValue(encoded, TicketState.class);
            ServiceAssert.isTrue(state != null && state.userId() != null && !state.userId().isBlank()
                            && AuthTokenSecrets.validSecret(state.credentialFingerprint())
                            && state.userSecurityVersion() != null && state.userSecurityVersion() >= 0,
                    ResultCode.AUTH_LOGIN_TICKET_INVALID);
            return state;
        } catch (JsonProcessingException e) {
            throw new ServiceException(ResultCode.AUTH_LOGIN_TICKET_INVALID);
        }
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
        ServiceAssert.isTrue(result != null && result.successValue(),
                ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        ServiceAssert.isTrue(result.getData() != null, ResultCode.AUTH_LOGIN_FAILED);
        return assertActive(result.getData());
    }

    private RemoteUserDTO loadActiveUserById(String userId) {
        Result<RemoteUserDTO> result = remoteSystemService.getUserById(userId);
        ServiceAssert.isTrue(result != null && result.successValue(),
                ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        ServiceAssert.isTrue(result.getData() != null,
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
        ServiceAssert.isTrue(result != null && result.successValue(),
                ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        return result.getData() == null ? List.of() : result.getData();
    }

    private com.robot.common.security.context.LoginUser requireLoginUser() {
        com.robot.common.security.context.LoginUser user = AuthUtil.getLoginUser();
        return ServiceAssert.notNull(user, ResultCode.UNAUTHORIZED);
    }

    private <T> T aggregate(Result<T> result) {
        ServiceAssert.isTrue(result != null && result.successValue(),
                ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        return result.getData();
    }

    private record TicketState(String userId, String credentialFingerprint, Long userSecurityVersion) {

        /** 隐藏票据中的凭据指纹，防止异常日志意外输出认证材料。 */
        @Override
        public String toString() {
            return "TicketState[凭据指纹已隐藏]";
        }
    }
}
