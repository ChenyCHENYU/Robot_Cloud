package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户-公司成员关系（登录上下文 contextId 即本表 ID）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_company")
public class SysUserCompany extends TenantEntity {

    private String userId;

    /** 是否主公司（同一账号最多一个主公司）。 */
    private Integer primaryFlag;

    /** 成员状态：1=有效，0=移除。 */
    private Integer statusFlag;
}
