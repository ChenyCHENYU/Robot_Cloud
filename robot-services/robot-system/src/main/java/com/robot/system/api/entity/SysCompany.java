package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 公司（租户下的组织单元，COMPANY_ID 即数据隔离键）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_company")
public class SysCompany extends BaseEntity {

    private String tenantId;

    private String companyName;

    /** 1=启用，0=停用。 */
    private Integer statusFlag;
}
