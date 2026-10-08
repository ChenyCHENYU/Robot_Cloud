package com.robot.common.mybatis.tenant;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.core.plugins.IgnoreStrategy;

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
 *   <li>execute 内建 try-finally，线程级策略必然清理，不会泄漏到同线程后续请求。</li>
 * </ul>
 * 禁止把 sys_role/sys_user_role 等表加入租户白名单作为替代修复。
 */
public final class TenantQueryScope {

    private TenantQueryScope() {
    }

    /** 在无租户行级过滤的作用域内执行（保留其余插件行为）。 */
    public static <T> T withoutTenantLine(Supplier<T> business) {
        return InterceptorIgnoreHelper.execute(
                IgnoreStrategy.builder().tenantLine(true).build(),
                business);
    }
}
