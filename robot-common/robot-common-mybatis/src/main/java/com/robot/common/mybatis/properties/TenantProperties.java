package com.robot.common.mybatis.properties;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 租户拦截配置。
 *
 * <p>框架不内置任何业务表白名单：每个服务按自己的表结构显式登记全局表 （无 COMPANY_ID 列的表），未登记的表必须含 company_id 列并参与过滤。
 *
 * <pre>
 * robot:
 *   mybatis:
 *     tenant:
 *       enabled: true
 *       ignore-tables:          # 由服务按实际表结构声明（示例为中性名）
 *         - platform_user
 *         - platform_dict
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "robot.mybatis.tenant")
public class TenantProperties {

    /** 是否启用租户行级过滤（本地无登录态联调可关，生产必须 true）。 */
    private boolean enabled = true;

    /** 全局表（无 COMPANY_ID 列）白名单：由服务显式登记，框架默认为空。 */
    private List<String> ignoreTables = List.of();

    /**
     * 返回不可变白名单快照，避免调用方扩展租户豁免。
     *
     * @return 已登记的全局表清单
     */
    public List<String> getIgnoreTables() {
        return List.copyOf(ignoreTables);
    }

    /**
     * 保存独立配置快照，null 表示没有全局表豁免。
     *
     * @param ignoreTables 配置绑定的全局表清单
     */
    public void setIgnoreTables(List<String> ignoreTables) {
        this.ignoreTables = ignoreTables == null ? List.of() : List.copyOf(ignoreTables);
    }
}
