package com.robot.common.mybatis.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.util.AuthUtil;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;

import java.util.Collection;
import java.util.Set;

/**
 * 租户行级过滤：SQL 层统一追加 {@code COMPANY_ID = ?}。
 *
 * <p>这是 wl-skills-bd「SQL 显式 COMPANY_ID」不变式的机器化实现
 * （standards/11 允许的统一拦截器证据）：
 * <ul>
 *   <li>租户值只来自 {@code AuthUtil.getLoginCompanyId()}；未登录返回 NULL，
 *       使过滤条件恒假，宁可查空不可泄露跨租户数据；</li>
 *   <li>平台级全局表（用户/公司/菜单等）登记于 ignoreTables，跳过过滤；</li>
 *   <li>业务新增全局表必须显式登记，并在代码评审说明理由。</li>
 * </ul>
 */
public class RobotTenantLineHandler implements TenantLineHandler {

    /** 平台级全局表：无 COMPANY_ID 列，不参与租户过滤。 */
    private final Set<String> ignoreTables;

    public RobotTenantLineHandler(Collection<String> ignoreTables) {
        this.ignoreTables = Set.copyOf(ignoreTables);
    }

    @Override
    public Expression getTenantId() {
        String companyId = AuthUtil.getLoginCompanyId();
        if (companyId == null || companyId.isBlank()) {
            // 未登录（含内部任务未携带会话）时返回 NULL：等值条件恒假，数据零泄露
            return new NullValue();
        }
        return new StringValue(companyId);
    }

    @Override
    public String getTenantIdColumn() {
        return RobotConstants.TENANT_COLUMN;
    }

    @Override
    public boolean ignoreTable(String tableName) {
        String normalized = tableName == null ? "" : tableName.toLowerCase().trim();
        return ignoreTables.stream().map(t -> t.toLowerCase().trim())
                .anyMatch(t -> t.equals(normalized));
    }
}
