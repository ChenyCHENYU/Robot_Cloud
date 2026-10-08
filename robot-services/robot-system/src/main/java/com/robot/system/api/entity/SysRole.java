package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色（公司内定义，DATA_SCOPE 决定数据权限范围）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends TenantEntity {

    private String roleName;

    private String roleCode;

    /** 数据权限：all/department/department_below/self/custom。 */
    private String dataScope;

    private Integer sortNo;

    /** 1=启用，0=停用。 */
    private Integer statusFlag;

    private String remark;
}
