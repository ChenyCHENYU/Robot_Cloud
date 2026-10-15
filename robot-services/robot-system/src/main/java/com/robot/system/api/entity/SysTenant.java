package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 租户（平台级，多租户顶层）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant")
public class SysTenant extends BaseEntity {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private String tenantName;

    /** 1=启用，0=停用。 */
    private Integer statusFlag;

    private String remark;
}
