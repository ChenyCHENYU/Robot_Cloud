package com.robot.common.security.context;

/**
 * 内部委托可信上下文：登录后 auth 代表某个已验证会话调用 system 时，
 * 由 /inner/** 拦截器（在 X-Inner-Call 密钥校验通过后）从受保护头解析并绑定。
 *
 * <p>安全边界：
 * <ul>
 *   <li>外部请求到不了 /inner 路由；网关同时清洗伪造的委托头；</li>
 *   <li>密钥校验失败的内部请求在解析头之前已被 403；</li>
 *   <li>仅 ThreadLocal 请求级生命周期，afterCompletion 必清理。</li>
 * </ul>
 */
public final class InnerTrustContext {

    private static final ThreadLocal<InnerTrustContext> HOLDER = new ThreadLocal<>();

    private final String userId;
    private final String companyId;
    private final String username;

    private InnerTrustContext(String userId, String companyId, String username) {
        this.userId = userId;
        this.companyId = companyId;
        this.username = username;
    }

    /** 仅由内部接口鉴权拦截器在验证服务凭据后调用；无委托时仍记录服务鉴权结果。 */
    public static void bindVerifiedService(String userId, String companyId, String username) {
        HOLDER.set(new InnerTrustContext(userId, companyId, username));
    }

    public boolean hasDelegate() {
        return userId != null && companyId != null;
    }

    public static InnerTrustContext current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public String getUserId() {
        return userId;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getUsername() {
        return username;
    }
}
