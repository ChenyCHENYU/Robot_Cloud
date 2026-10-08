package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 权限约束规则（越权防护配置，如禁止自授角色、敏感操作二次确认范围）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission_constraint")
public class SysPermissionConstraint extends TenantEntity {

    /** 规则名称。 */
    private String ruleName;

    /** 规则类型：self_grant_ban / sensitive_confirm / role_mutual_exclusive ... */
    private String ruleType;

    /** 规则配置 JSON。 */
    private String configJson;

    /** 1=启用，0=停用。 */
    private Integer statusFlag;
}
