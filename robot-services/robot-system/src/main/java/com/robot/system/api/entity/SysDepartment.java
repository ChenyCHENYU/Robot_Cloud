package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 部门（公司内树形组织）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_department")
public class SysDepartment extends TenantEntity {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private String parentId;

    private String deptName;

    private Integer sortNo;

    /** 1=启用，0=停用。 */
    private Integer statusFlag;
}
