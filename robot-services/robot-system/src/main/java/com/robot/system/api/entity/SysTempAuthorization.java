package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 临时授权（限时角色授予，到期自动失效）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_temp_authorization")
public class SysTempAuthorization extends TenantEntity {

    /** 被授权用户。 */
    private String userId;

    /** 临时授予的角色。 */
    private String roleId;

    /** 生效开始时间。 */
    private LocalDateTime effectFrom;

    /** 生效结束时间（过期即失效）。 */
    private LocalDateTime effectTo;

    /** 授权理由（审计必填）。 */
    private String reason;

    /** 1=生效中，0=已撤销。 */
    private Integer statusFlag;
}
