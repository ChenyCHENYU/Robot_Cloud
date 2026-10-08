package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 权限审计日志（只追加：业务代码只允许 insert 与查询，禁止更新删除）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission_audit_log")
public class SysPermissionAuditLog extends TenantEntity {

    /** 操作人。 */
    private String actorUserNo;

    /** 动作：grant_role / revoke_role / save_menu / delete_menu / temp_authorize ... */
    private String action;

    /** 目标对象描述（如 userId / roleId / menuId）。 */
    private String target;

    /** 详情 JSON。 */
    private String detail;
}
