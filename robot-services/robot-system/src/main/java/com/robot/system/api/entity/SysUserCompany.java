package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户-公司成员关系（登录上下文 contextId 即本表 ID）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_company")
public class SysUserCompany extends TenantEntity {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private String userId;

    /** 是否主公司（同一账号最多一个主公司）。 */
    private Integer primaryFlag;

    /** 当前公司的唯一主部门，省略保留、显式 NULL 清空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String departmentId;

    /** 成员状态：1=有效，0=移除。 */
    private Integer statusFlag;

    /** 成员撤销版本，只增不减；移出或停用时递增。 */
    private Long securityVersion = 0L;
}
