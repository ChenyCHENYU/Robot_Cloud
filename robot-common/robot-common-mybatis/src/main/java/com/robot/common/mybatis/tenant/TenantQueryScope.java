package com.robot.common.mybatis.tenant;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.core.plugins.IgnoreStrategy;
import com.robot.common.security.util.AuthUtil;

import java.util.Objects;

import java.util.function.Supplier;

/**
 * 受控租户豁免作用域：仅限登录前内部供给查询等无法携带登录态/委托态的场景，
 * 在显式公司集合谓词的查询上临时关闭租户行级过滤（其余插件行为保留）。
 *
 * <p>使用约束（RC07 整改口径）：
 * <ul>
 *   <li>必须在 /inner/** 密钥校验通过的保护范围内使用，禁止业务常规路径调用；</li>
 *   <li>调用方查询必须自带显式公司/成员谓词（如按成员关系的公司集合过滤），
 *       不得借此读取全表；</li>
 *   <li>自主管理嵌套深度与 try-finally，退出最外层才清理线程策略；已有外部策略拒绝覆盖。</li>
 * </ul>
 * 禁止把 sys_role/sys_user_role 等表加入租户白名单作为替代修复。
 */
public final class TenantQueryScope {

    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private TenantQueryScope() {
    }

    /** 在无租户行级过滤的作用域内执行（保留其余插件行为）。 */
    public static <T> T withoutTenantLine(Supplier<T> business) {
        AuthUtil.requireTrustedService();
        Objects.requireNonNull(business);
        Integer depth = DEPTH.get();
        if (depth != null) {
            DEPTH.set(depth + 1);
            try {
                return business.get();
            } finally {
                DEPTH.set(depth);
            }
        }
        // MP 3.5.12 不提供线程策略读取 API。拒绝覆盖外部作用域，保留其全部策略。
        if (InterceptorIgnoreHelper.hasIgnoreStrategy()) {
            throw new IllegalStateException("租户豁免不能覆盖已有拦截器策略");
        }
        DEPTH.set(1);
        InterceptorIgnoreHelper.handle(IgnoreStrategy.builder().tenantLine(true).build());
        try {
            return business.get();
        } finally {
            InterceptorIgnoreHelper.clearIgnoreStrategy();
            DEPTH.remove();
        }
    }
}
