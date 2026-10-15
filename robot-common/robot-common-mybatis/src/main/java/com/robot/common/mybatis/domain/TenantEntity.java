package com.robot.common.mybatis.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户表基础字段：{@link BaseEntity} + COMPANY_ID 隔离键。
 *
 * <p>仅用于真实存在 company_id 列且参与租户过滤的表； 该字段只由审计填充（登录态公司）或业务显式赋值，禁止取自请求参数 （wl-skills-bd 不变式：租户唯一来源
 * AuthUtil）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class TenantEntity extends BaseEntity {

    /** 租户隔离键。 */
    @TableField(value = "company_id", fill = FieldFill.INSERT)
    private String companyId;
}
