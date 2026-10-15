package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户-角色绑定（COMPANY_ID 取角色所属公司）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_role")
public class SysUserRole extends TenantEntity {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private String userId;

    private String roleId;
}
